"""GET /invite/<token> — the only non-JSON, non-authed, unprefixed route.

Registered directly on the host app (not the /api/v1 blueprint) so invite
links stay short and universal: https://<server>/invite/<token> (Spec §5).
Deliberately stateless: it only verifies the token's signature and reads the
expiry embedded in its signed payload, never touching the database, so it
cannot leak anything beyond what the token itself already carries.
"""

from urllib.parse import urlsplit

from flask import render_template

from .. import get_config
from ..auth import now_ms
from ..errors import ApiError
from ..invites import decode_token

ANDROID_PACKAGE = "org.p23q.shoppinglist"


def _intent_url(base_url: str, token: str) -> str:
    host = urlsplit(base_url).netloc
    return f"intent://{host}/invite/{token}#Intent;scheme=https;package={ANDROID_PACKAGE};end"


def register_routes(app):
    @app.route("/invite/<token>", methods=["GET"])
    def invite_landing_view(token):
        config = get_config()
        try:
            _invite_id, _list_id, invited_email, expires_at = decode_token(
                config["invite_hmac_key"], token
            )
        except ApiError:
            # Blend in with "not found" rather than confirming a token-shaped
            # value was received at all.
            return render_template("invite.html", state="not_found"), 404

        if now_ms() >= expires_at:
            return render_template("invite.html", state="expired"), 410

        return (
            render_template(
                "invite.html",
                state="valid",
                token=token,
                invited_email=invited_email,
                intent_url=_intent_url(config["base_url"], token),
            ),
            200,
        )
