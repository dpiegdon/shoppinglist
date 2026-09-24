"""Publishes the version of the Android app this server carries (T-135).

The client has no app store to ask, so it asks the server it already syncs with:
this returns the version of the APK available at `<root>/shoppinglist.apk` plus
that file's absolute URL, and the client compares it against its own build.

The version reported is this package's version, not one parsed out of the APK.
That is exact rather than approximate: one built wheel is a single deployable
artifact whose parts deliberately share one version number (see the README's
"Versioning and releases"), so the package version IS the embedded APK's version.

Unauthenticated, like `/registration-status` — checking for an update is not an
account operation, and the APK download it points at is public anyway. It is also
the one route exempt from the protocol gate (T-243): a client refused with `426
client_outdated` everywhere else finds its update here, and reads this server's
`protocol` from the same answer.

404 `no_app_package` when this instance serves no APK (`serve_android_apk=False`,
no APK packaged, or a source checkout). That 404 still carries `protocol` (T-297):
the protocol is a property of the server, not of the app package, and a client
asks for it before it signs in. A 404 without `protocol` is what a server from
before this endpoint answers, so that is how a client tells the two apart.
"""

from importlib.metadata import PackageNotFoundError, version

from flask import jsonify

from .. import get_config
from ..errors import ApiError
from ..protocol import PROTOCOL_VERSION
from .apk import APK_FILENAME, apk_present

# Answered even when there is no app package: a client reads the server's protocol here
# before signing in, and a 404 without it is a server from before this endpoint (T-297).
_PROTOCOL = {"protocol": PROTOCOL_VERSION}


def register_routes(bp):
    @bp.route("/app-version", methods=["GET"])
    def app_version_view():
        config = get_config()
        # Resolved per request, not at registration: the APK route is registered from
        # @bp.record_once, which runs AFTER this blueprint's routes are defined, so at
        # definition time there is nothing to ask yet.
        if not config.get("serve_android_apk", True) or not apk_present():
            raise ApiError(
                404,
                "no_app_package",
                "This server does not carry an Android app package.",
                details=_PROTOCOL,
            )
        try:
            pkg_version = version("shoppinglist-server")
        except PackageNotFoundError:
            # Running from a source checkout rather than an installed wheel. Report no
            # version rather than a fake one: a client can't compare against "unknown",
            # and inventing a value here could push a spurious update prompt.
            raise ApiError(
                404,
                "no_app_package",
                "This server cannot determine its app package version.",
                details=_PROTOCOL,
            ) from None
        return (
            jsonify(
                {
                    "version": pkg_version,
                    # Absolute, so the client can hand it straight to an Intent. Built from
                    # this instance's base_url, so a blueprint mounted under a subpath
                    # advertises the right URL.
                    "download_url": f"{config['base_url'].rstrip('/')}/{APK_FILENAME}",
                    # This server's protocol version (T-243). Here because this endpoint is the
                    # one an outdated client can still reach, so it is where a client turned
                    # away with 426 can see what it is being asked to catch up to.
                    "protocol": PROTOCOL_VERSION,
                }
            ),
            200,
        )
