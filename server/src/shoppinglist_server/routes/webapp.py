"""Serves the built web client (Epic W), embedded in this package.

Registered directly on the host app (not the /api/v1 blueprint), same
reasoning as routes/landing.py: it must live at the site root so opening the
server's base URL in a browser boots the SPA. Route precedence relies on
Werkzeug ranking rules by specificity, not registration order: /api/v1/* and
/invite/<token> both have static prefix segments and rank above this
module's fully-dynamic catch-all regardless of when each is registered —
verified with real requests in tests/test_webapp.py, not just a route dump.
"""

import os
from pathlib import Path

from flask import send_from_directory

PACKAGE_DIR = Path(__file__).resolve().parent.parent
DEFAULT_WEB_DIST_DIR = str(PACKAGE_DIR / "web_dist")

# Immutable, content-hashed filenames (Vite's build output) can be cached
# indefinitely; index.html must always be revalidated so a rebuild's new
# asset hashes are picked up.
ASSET_MAX_AGE = 31536000
INDEX_MAX_AGE = 0


def register_routes(app, web_dist_dir: str = DEFAULT_WEB_DIST_DIR) -> bool:
    """Registers the web client routes on `app`. Returns False (no-op) if
    `web_dist_dir` doesn't contain a built app, so a package installed
    without ever running `npm run build` degrades gracefully instead of
    crashing at request time."""
    if not os.path.isfile(os.path.join(web_dist_dir, "index.html")):
        return False

    @app.route("/assets/<path:filename>")
    def web_asset(filename):
        return send_from_directory(
            os.path.join(web_dist_dir, "assets"), filename, max_age=ASSET_MAX_AGE
        )

    @app.route("/favicon.svg")
    def web_favicon():
        return send_from_directory(web_dist_dir, "favicon.svg", max_age=ASSET_MAX_AGE)

    @app.route("/")
    @app.route("/<path:spa_path>")
    def web_index(spa_path=None):
        return send_from_directory(web_dist_dir, "index.html", max_age=INDEX_MAX_AGE)

    return True
