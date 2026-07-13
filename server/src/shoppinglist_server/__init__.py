from flask import Blueprint, current_app, g, jsonify, request
from werkzeug.exceptions import RequestEntityTooLarge

from . import db as db_module
from .errors import ApiError

EXTENSION_KEY = "shoppinglist_server"

# App-level request-body cap (T-45): request.get_json() would otherwise buffer an unbounded body —
# /sync especially. 4 MB sits comfortably above realistic sync batches; operators can override via
# the standard Flask MAX_CONTENT_LENGTH config (documented in the README). setdefault, so an
# operator-set value wins.
DEFAULT_MAX_CONTENT_LENGTH = 4 * 1024 * 1024

# CSP for the HTML-serving routes (invite landing, embedded SPA). script-src falls back to the
# strict default-src 'self' — neither page uses inline scripts, so this blocks injected script
# outright. style-src allows 'unsafe-inline' because invite.html carries an inline <style> and the
# SPA sets inline styles at runtime; inline style is far lower risk than inline script (T-45).
HTML_SECURITY_CSP = (
    "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; "
    "object-src 'none'; base-uri 'self'; frame-ancestors 'none'"
)


def create_blueprint(
    database_path: str,
    invite_hmac_key: bytes,
    base_url: str,
    url_prefix: str = "/api/v1",
    name: str = "shoppinglist_server",
    serve_web_client: bool = True,
    serve_invite_landing_page: bool = True,
    serve_android_apk: bool = True,
    web_dist_dir: str | None = None,
) -> Blueprint:
    """Build a mountable blueprint instance.

    Safe to call more than once and register multiple instances on the same
    app (different `database_path`/`invite_hmac_key`/`url_prefix` each,
    isolated from one another) — but each extra instance beyond the first
    MUST pass a distinct `name`, and at most one instance per app may set
    `serve_web_client=True` / `serve_invite_landing_page=True` /
    `serve_android_apk=True` (all are unprefixed, site-root routes; there is
    only one `/`, one `/invite/<token>`, and one `/shoppinglist.apk` per app,
    by construction).
    """
    bp = Blueprint(name, __name__, url_prefix=url_prefix, template_folder="templates")

    config = {
        "database_path": database_path,
        "invite_hmac_key": invite_hmac_key,
        "base_url": base_url,
    }

    @bp.record_once
    def _record(setup_state):
        app = setup_state.app
        # Keyed by blueprint name, NOT a single flat slot: get_config() below
        # resolves the caller's own instance via request.blueprint at request
        # time, so multiple mounted instances never see each other's config
        # (database_path, invite_hmac_key, ...) — a single shared slot here
        # was the original bug this docstring/design replaced.
        app.extensions.setdefault(EXTENSION_KEY, {})[bp.name] = config
        app.register_error_handler(ApiError, _handle_api_error)
        app.register_error_handler(RequestEntityTooLarge, _handle_payload_too_large)

        # App-wide, not per-instance: cap the body size and attach security headers once. Guarded so
        # mounting several instances doesn't stack duplicate after_request callbacks (T-45).
        # Flask seeds MAX_CONTENT_LENGTH as None (unset), so key in `is None` — not setdefault — is
        # what lets an operator-set value win while still supplying our default.
        if app.config.get("MAX_CONTENT_LENGTH") is None:
            app.config["MAX_CONTENT_LENGTH"] = DEFAULT_MAX_CONTENT_LENGTH
        if not app.extensions.get("shoppinglist_security_headers"):
            app.extensions["shoppinglist_security_headers"] = True
            app.after_request(_add_security_headers)

        # The invite landing page is deliberately NOT under url_prefix (Spec
        # §5's share URL is https://<server>/invite/<token>, no /api/v1), so
        # it's registered directly on the app rather than through `bp`. A
        # blueprint's template_folder is searched app-wide regardless of which
        # blueprint (if any) a view belongs to, so invite.html still resolves.
        # invite_hmac_key/base_url are passed directly (closure-captured, not
        # read via the shared get_config()) so this route is correctly scoped
        # to THIS instance's key even when other instances are also mounted.
        if serve_invite_landing_page:
            if "invite_landing_view" in app.view_functions:
                raise ValueError(
                    "serve_invite_landing_page=True on this create_blueprint() call, but "
                    "the invite landing page is already registered on this app by another "
                    "mounted instance. Only one instance per app may serve it — pass "
                    "serve_invite_landing_page=False here."
                )
            from .routes.landing import register_routes as register_landing_routes

            register_landing_routes(app, invite_hmac_key, base_url)

        # The Android APK download (T-59), also a site-root route, registered
        # BEFORE the landing page's render decisions matter: the landing/login
        # surfaces only link to it when this route exists.
        if serve_android_apk:
            if "android_apk" in app.view_functions:
                raise ValueError(
                    "serve_android_apk=True on this create_blueprint() call, but the APK "
                    "download is already registered on this app by another mounted "
                    "instance. Only one instance per app may serve it — pass "
                    "serve_android_apk=False here."
                )
            from .routes.apk import register_routes as register_apk_routes

            register_apk_routes(app)

        # The embedded web client (Epic W) is likewise registered directly on
        # the app, outside url_prefix, so opening the server's base URL boots
        # the SPA. Registered last: its catch-all route is the least specific
        # of everything mounted here, and Werkzeug ranks by rule specificity
        # regardless of registration order, but this keeps the precedence
        # obvious to a reader too.
        if serve_web_client:
            if "web_index" in app.view_functions:
                raise ValueError(
                    "serve_web_client=True on this create_blueprint() call, but the web "
                    "client is already registered on this app by another mounted instance. "
                    "Only one instance per app may serve it — pass serve_web_client=False "
                    "here."
                )
            from .routes.webapp import register_routes as register_webapp_routes

            if web_dist_dir is not None:
                register_webapp_routes(app, web_dist_dir)
            else:
                register_webapp_routes(app)

    @bp.teardown_app_request
    def _close_db(exception=None):
        conn = g.pop("shoppinglist_db", None)
        if conn is not None:
            conn.close()

    from .routes.account import register_routes as register_account_routes
    from .routes.auth import register_routes as register_auth_routes
    from .routes.invites import register_routes as register_invites_routes
    from .routes.lists import register_routes as register_lists_routes
    from .routes.sync import register_routes as register_sync_routes

    register_auth_routes(bp)
    register_account_routes(bp)
    register_lists_routes(bp)
    register_invites_routes(bp)
    register_sync_routes(bp)

    return bp


def _handle_api_error(err: ApiError):
    body = {"error": err.code, "message": err.message}
    if err.details:
        # Additive only — never let details shadow the canonical error/message keys.
        for key, value in err.details.items():
            body.setdefault(key, value)
    return jsonify(body), err.status


def _handle_payload_too_large(err: RequestEntityTooLarge):
    # Same JSON envelope shape as ApiError, so API clients parse it the same way (T-45).
    return jsonify({"error": "payload_too_large", "message": "Request body is too large."}), 413


def _add_security_headers(response):
    # Applied app-wide (T-45). nosniff and no-referrer are safe on every response — no-referrer in
    # particular keeps the secret token in an /invite/<token> URL out of the Referer header on any
    # navigation away. CSP and X-Frame-Options are only meaningful for HTML, so scope them there.
    response.headers.setdefault("X-Content-Type-Options", "nosniff")
    response.headers.setdefault("Referrer-Policy", "no-referrer")
    if response.mimetype == "text/html":
        response.headers.setdefault("Content-Security-Policy", HTML_SECURITY_CSP)
        response.headers.setdefault("X-Frame-Options", "DENY")
    return response


def get_config() -> dict:
    """This instance's config, resolved via the blueprint that matched the
    current request — never a single shared slot, so multiple mounted
    instances on the same app never see each other's database_path /
    invite_hmac_key / base_url."""
    instances = current_app.extensions[EXTENSION_KEY]
    name = request.blueprint
    if name is None or name not in instances:
        raise RuntimeError(
            "get_config() was called outside a request routed through a "
            "shoppinglist_server blueprint instance; it has no way to know which "
            "mounted instance's configuration to use."
        )
    return instances[name]


def get_db():
    if "shoppinglist_db" not in g:
        config = get_config()
        g.shoppinglist_db = db_module.connect(config["database_path"])
    return g.shoppinglist_db


def get_config_by_name(app, name: str | None = None) -> dict:
    """For CLI use (`cli.py`): outside a request, there is no `request.blueprint`
    to resolve an instance automatically. With exactly one instance mounted,
    `name` may be omitted. With multiple, it's required — raises ValueError
    listing the available names otherwise."""
    instances = app.extensions.get(EXTENSION_KEY, {})
    if not instances:
        raise ValueError("No shoppinglist_server blueprint instance is registered on this app.")
    if name is not None:
        if name not in instances:
            available = ", ".join(sorted(instances))
            raise ValueError(f"No such instance '{name}'. Available: {available}")
        return instances[name]
    if len(instances) == 1:
        return next(iter(instances.values()))
    available = ", ".join(sorted(instances))
    raise ValueError(
        f"Multiple blueprint instances are registered ({available}); specify which one."
    )
