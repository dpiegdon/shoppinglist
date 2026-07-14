"""Serves the Android release APK, embedded in this package (T-59).

Registered directly on the host app (not the /api/v1 blueprint), same reasoning
as routes/webapp.py: a stable, short download URL at the site root. The APK is
package data (apk/shoppinglist.apk) exactly like the built web client, so a
wheel built from this package is a single deployable artifact: API + web client
+ invite landing page + the app download.
"""

import os
from pathlib import Path

from flask import send_from_directory

PACKAGE_DIR = Path(__file__).resolve().parent.parent
DEFAULT_APK_DIR = str(PACKAGE_DIR / "apk")
APK_FILENAME = "shoppinglist.apk"

# Short-lived cache: the filename is stable across releases (no content hash),
# so clients must revalidate to pick up a newly shipped version promptly.
APK_MAX_AGE = 300


def register_routes(app, apk_dir: str = DEFAULT_APK_DIR, root_path: str = "") -> bool:
    """Registers the APK download route on `app` under `root_path` ("" = domain
    root). Returns False (no-op) if `apk_dir` doesn't contain the APK, so a
    package built without the Android artifact degrades gracefully instead of
    serving 404s from a live link."""
    if not os.path.isfile(os.path.join(apk_dir, APK_FILENAME)):
        return False

    @app.route(f"{root_path}/{APK_FILENAME}")
    def android_apk():
        return send_from_directory(
            apk_dir,
            APK_FILENAME,
            mimetype="application/vnd.android.package-archive",
            as_attachment=True,
            max_age=APK_MAX_AGE,
        )

    return True
