import sqlite3
from urllib.parse import urlsplit

from flask import Blueprint, current_app, g, jsonify, request
from werkzeug.exceptions import RequestEntityTooLarge

from . import audit
from . import db as db_module
from .errors import ApiError

EXTENSION_KEY = "shoppinglist_server"

# Endpoints of the site-root routes we register directly on the host app (outside
# any blueprint, per Spec §5's root URLs — the invite landing page, the SPA, the
# APK download). Tracked per-app so _add_security_headers can recognize them as
# ours: request.blueprint is None for an app-level route, so blueprint identity
# alone can't tell our own HTML pages apart from another blueprint's (T-106).
OWNED_ENDPOINTS_KEY = "shoppinglist_owned_endpoints"
_LANDING_ENDPOINTS = frozenset({"invite_landing_view"})
_APK_ENDPOINTS = frozenset({"android_apk"})
_WEBAPP_ENDPOINTS = frozenset({"web_index", "web_asset", "web_favicon"})

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
    url_prefix: str | None = None,
    name: str = "shoppinglist_server",
    serve_web_client: bool = True,
    serve_invite_landing_page: bool = True,
    serve_android_apk: bool = True,
    web_dist_dir: str | None = None,
    allow_registration: bool = True,
    admin_emails: list[str] | None = None,
) -> Blueprint:
    """Build a mountable blueprint instance.

    The path component of `base_url` is the instance's mount root (T-60): with
    `base_url="https://example.com/shopping/"`, the web client is served at
    `/shopping/`, the invite landing page at `/shopping/invite/<token>`, the
    APK at `/shopping/shoppinglist.apk`, and — unless `url_prefix` is passed
    explicitly — the API at `/shopping/api/v1`. With no path in `base_url`,
    everything sits at the domain root exactly as before.

    Safe to call more than once and register multiple instances on the same
    app (different `database_path`/`invite_hmac_key`/`url_prefix` each,
    isolated from one another) — but each extra instance beyond the first
    MUST pass a distinct `name`, and at most one instance per app may set
    `serve_web_client=True` / `serve_invite_landing_page=True` /
    `serve_android_apk=True` (all rooted at the mount root; there is only one
    `<root>/`, one `<root>/invite/<token>`, and one `<root>/shoppinglist.apk`
    per app, by construction).

    `allow_registration=False` rejects `POST /register` with a 403
    `registration_disabled` error and tells the served web client to disable
    its register option (T-61) — for instances that are invite/operator-only.
    An admin may override this at runtime (T-107), but the override is
    non-durable: this config value reasserts on restart.

    `admin_emails` (T-107) is the ONLY way to grant admin — a static list, matched
    case-insensitively against the logged-in account's email, checked live. No API
    path can set it, so there is no privilege-escalation route. Admins get the
    server-settings tab (registration toggle, reset a user's password, delete a
    user).
    """
    # "https://example.com/shopping/" -> "/shopping"; no path -> "".
    root_path = urlsplit(base_url).path.rstrip("/")
    if url_prefix is None:
        url_prefix = f"{root_path}/api/v1"

    bp = Blueprint(name, __name__, url_prefix=url_prefix, template_folder="templates")

    config = {
        "database_path": database_path,
        "invite_hmac_key": invite_hmac_key,
        "base_url": base_url,
        "allow_registration": allow_registration,
        # Pre-normalized (lower + strip, blanks dropped) so the live admin check is a plain set
        # membership on the account's lowercased email (T-107).
        "admin_emails": frozenset(
            e.strip().lower() for e in (admin_emails or []) if e and e.strip()
        ),
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
        app.register_error_handler(sqlite3.OperationalError, _handle_db_unavailable)

        # App-wide, not per-instance: cap the body size and attach security headers once. Guarded so
        # mounting several instances doesn't stack duplicate after_request callbacks (T-45).
        # Flask seeds MAX_CONTENT_LENGTH as None (unset), so key in `is None` — not setdefault — is
        # what lets an operator-set value win while still supplying our default.
        if app.config.get("MAX_CONTENT_LENGTH") is None:
            app.config["MAX_CONTENT_LENGTH"] = DEFAULT_MAX_CONTENT_LENGTH
        # The hook is app-wide (Flask has no way to scope after_request to a
        # subset of app-level routes), but it self-limits to routes THIS
        # extension owns via owned_endpoints below — so a co-mounted blueprint's
        # routes are left untouched (T-106). Guarded so several mounted instances
        # don't stack duplicate callbacks (T-45).
        owned_endpoints = app.extensions.setdefault(OWNED_ENDPOINTS_KEY, set())
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

            register_landing_routes(app, invite_hmac_key, base_url, root_path=root_path)
            owned_endpoints |= _LANDING_ENDPOINTS

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

            # Returns False (no route) when the APK file is absent — only claim
            # the endpoint as ours if it was actually registered.
            if register_apk_routes(app, root_path=root_path):
                owned_endpoints |= _APK_ENDPOINTS

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

            # Returns False (no routes) when no built web bundle is present —
            # only claim the endpoints as ours if they were actually registered.
            if web_dist_dir is not None:
                registered = register_webapp_routes(
                    app, web_dist_dir, root_path=root_path, allow_registration=allow_registration
                )
            else:
                registered = register_webapp_routes(
                    app, root_path=root_path, allow_registration=allow_registration
                )
            if registered:
                owned_endpoints |= _WEBAPP_ENDPOINTS

    @bp.teardown_app_request
    def _close_db(exception=None):
        conn = g.pop("shoppinglist_db", None)
        if conn is not None:
            conn.close()

    from .routes.account import register_routes as register_account_routes
    from .routes.admin import register_routes as register_admin_routes
    from .routes.auth import register_routes as register_auth_routes
    from .routes.invites import register_routes as register_invites_routes
    from .routes.lists import register_routes as register_lists_routes
    from .routes.sync import register_routes as register_sync_routes

    register_auth_routes(bp)
    register_account_routes(bp)
    register_lists_routes(bp)
    register_invites_routes(bp)
    register_sync_routes(bp)
    register_admin_routes(bp)

    return bp


def _handle_api_error(err: ApiError):
    # Every rejected-for-authorization outcome funnels through here, which makes this the one place
    # that sees all of them — cheaper and far harder to forget than a record() call at each of the
    # dozen-odd raise sites (T-121). Successes are logged at their route instead, where the
    # meaningful detail lives.
    if err.status in (401, 403):
        audit.record(
            "authz.denied",
            account_id=getattr(g, "account", None) and g.account.id,
            outcome="denied",
            code=err.code,
            path=request.path,
        )
    body = {"error": err.code, "message": err.message}
    if err.details:
        # Additive only — never let details shadow the canonical error/message keys.
        for key, value in err.details.items():
            body.setdefault(key, value)
    return jsonify(body), err.status


def _handle_payload_too_large(err: RequestEntityTooLarge):
    # Same JSON envelope shape as ApiError, so API clients parse it the same way (T-45).
    return jsonify({"error": "payload_too_large", "message": "Request body is too large."}), 413


def _handle_db_unavailable(err: sqlite3.OperationalError):
    """A busy/locked SQLite database is congestion, not a bug — answer 503 + Retry-After, not 500
    (T-114).

    SQLite allows a single writer, and stock `sqlite3` gives up after a 5 s busy timeout. Before
    this, one slow write made every concurrent write raise here and surface as an opaque 500,
    telling the client to give up when retrying was exactly the right move. Only contention is
    remapped: any other OperationalError (missing table, malformed schema) is a genuine fault and
    is re-raised so it still fails loudly rather than hiding behind a soothing 503.
    """
    message = str(err).lower()
    if "locked" not in message and "busy" not in message:
        raise err
    audit.record("db.contention", outcome="error", path=request.path)
    response = jsonify(
        {"error": "server_busy", "message": "The server is busy; please retry in a moment."}
    )
    response.headers["Retry-After"] = "2"
    return response, 503


def _add_security_headers(response):
    # Only touch responses for routes THIS extension owns (T-106). A blueprint is
    # a guest in the host app; imposing our policy on another blueprint's routes
    # would e.g. block its inline scripts (CSP default-src 'self'), or, via
    # no-referrer, send Origin: null on its same-site form POSTs and trip its
    # Origin-checking CSRF guard. setdefault already stops us OVERWRITING a header
    # the host set, but ADDING one it never set is just as much setting policy for
    # a route we don't own.
    #
    # "Ours" is our API blueprint(s) — request.blueprint — plus the site-root HTML
    # /APK routes we register directly on the app. Those latter are outside any
    # url_prefix (Spec §5), so request.blueprint is None for them; scoping by
    # blueprint alone would wrongly DROP headers there, which is exactly where CSP
    # and the token-hiding no-referrer matter most (the /invite/<token> page).
    extensions = current_app.extensions
    owned = (
        request.blueprint in extensions.get(EXTENSION_KEY, {})
        or request.endpoint in extensions.get(OWNED_ENDPOINTS_KEY, ())
    )
    if not owned:
        return response

    # nosniff and no-referrer are safe on all our responses; no-referrer in
    # particular keeps the secret token in an /invite/<token> URL out of the
    # Referer header on any navigation away. CSP and X-Frame-Options are only
    # meaningful for HTML, so scope them to it.
    response.headers.setdefault("X-Content-Type-Options", "nosniff")
    response.headers.setdefault("Referrer-Policy", "no-referrer")
    # Nothing we serve may sit in a shared cache unless the route asked for it (T-119). RFC 9111
    # lets a cache store a 200 GET carrying no freshness information on its own judgement, and a
    # TLS-terminating reverse proxy — mandatory for this deployment — is exactly such a cache. What
    # would land there: member email addresses (/lists/<id>/members), every account
    # (/admin/users), and, in a response body, a bearer token (POST /login) and a freshly reset
    # plaintext password (POST /admin/users/<id>/reset-password).
    #
    # setdefault, so the routes that HAVE made a caching decision keep it: the content-hashed SPA
    # assets and the APK are deliberately long-lived, and the SPA index is deliberately no-cache.
    response.headers.setdefault("Cache-Control", "no-store")
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
