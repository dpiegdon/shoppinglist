"""Serves the built web client (Epic W), embedded in this package.

Registered directly on the host app (not the /api/v1 blueprint), same
reasoning as routes/landing.py: it must live at the instance's mount root so
opening the server's base URL in a browser boots the SPA. Route precedence
relies on Werkzeug ranking rules by specificity, not registration order: the
API prefix, /invite/<token>, and /shoppinglist.apk all have static path
segments and rank above this module's fully-dynamic catch-all regardless of
when each is registered — verified with real requests in tests/test_webapp.py
and tests/test_prefix_mount.py, not just a route dump.

The SPA is built once, path-agnostic; mount-specific facts are injected when
index.html is served (T-60/T-61): asset URLs get the mount root prefixed, and
<meta> tags tell the client its router basename (so /shopping deployments don't
escape to domain-root /login) and whether registration is enabled. Meta tags —
not an inline <script> — because the security CSP (T-45) has a strict
`script-src` with no 'unsafe-inline', which blocks inline scripts in a real
browser (curl and jsdom don't enforce it, so this bit the field, not the tests).
The transformed page is prepared once at registration time and served from memory.
"""

import html
import os
from importlib.metadata import PackageNotFoundError, version
from pathlib import Path

from flask import send_from_directory

PACKAGE_DIR = Path(__file__).resolve().parent.parent
DEFAULT_WEB_DIST_DIR = str(PACKAGE_DIR / "web_dist")

# Immutable, content-hashed filenames (Vite's build output) can be cached
# indefinitely; index.html must always be revalidated so a rebuild's new
# asset hashes are picked up.
ASSET_MAX_AGE = 31536000


def _transformed_index(web_dist_dir: str, root_path: str, allow_registration: bool) -> str:
    with open(os.path.join(web_dist_dir, "index.html"), encoding="utf-8") as f:
        index_html = f.read()
    if root_path:
        index_html = index_html.replace('"/assets/', f'"{root_path}/assets/')
        index_html = index_html.replace('"/favicon.svg"', f'"{root_path}/favicon.svg"')
    # The package version rides along so "what is this server actually running?"
    # is answerable with one curl of the page — deployment staleness (stale
    # process, shadowed package) is otherwise invisible from the outside.
    try:
        pkg_version = version("shoppinglist-server")
    except PackageNotFoundError:
        pkg_version = "unknown"
    meta = (
        f'<meta name="app-basename" content="{html.escape(root_path, quote=True)}">'
        f'<meta name="app-allow-registration" content="{"true" if allow_registration else "false"}">'
        f'<meta name="app-version" content="{html.escape(pkg_version, quote=True)}">'
    )
    # Meta tags in <head> parse before the deferred module bundle runs, so the
    # client reads them synchronously at startup — and, unlike an inline script,
    # they are not subject to the CSP's script-src.
    return index_html.replace("<head>", "<head>" + meta, 1)


def register_routes(
    app,
    web_dist_dir: str = DEFAULT_WEB_DIST_DIR,
    root_path: str = "",
    allow_registration: bool = True,
) -> bool:
    """Registers the web client routes on `app` under `root_path` ("" = domain
    root). Returns False (no-op) if `web_dist_dir` doesn't contain a built
    app, so a package installed without ever running `npm run build` degrades
    gracefully instead of crashing at request time."""
    if not os.path.isfile(os.path.join(web_dist_dir, "index.html")):
        return False

    index_html = _transformed_index(web_dist_dir, root_path, allow_registration)

    @app.route(f"{root_path}/assets/<path:filename>")
    def web_asset(filename):
        return send_from_directory(
            os.path.join(web_dist_dir, "assets"), filename, max_age=ASSET_MAX_AGE
        )

    @app.route(f"{root_path}/favicon.svg")
    def web_favicon():
        return send_from_directory(web_dist_dir, "favicon.svg", max_age=ASSET_MAX_AGE)

    @app.route(f"{root_path}/")
    @app.route(f"{root_path}/<path:spa_path>")
    def web_index(spa_path=None):
        response = app.response_class(index_html, mimetype="text/html")
        # Same must-revalidate behavior the old send_from_directory(max_age=0) gave.
        response.cache_control.no_cache = True
        return response

    return True
