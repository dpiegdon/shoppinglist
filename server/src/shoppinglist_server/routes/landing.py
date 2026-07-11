"""GET /invite/<token> — the only non-JSON, non-authed, unprefixed route.

Registered directly on the host app (not the /api/v1 blueprint) so invite
links stay short and universal: https://<server>/invite/<token> (Spec §5).
Deliberately stateless: it only verifies the token's signature and reads the
expiry embedded in its signed payload, never touching the database, so it
cannot leak anything beyond what the token itself already carries.
"""

from urllib.parse import quote, urlsplit

from flask import current_app, render_template

from ..auth import now_ms
from ..errors import ApiError
from ..invites import decode_token

ANDROID_PACKAGE = "org.p23q.shoppinglist"


def _intent_url(base_url: str, token: str) -> str:
    host = urlsplit(base_url).netloc
    return f"intent://{host}/invite/{token}#Intent;scheme=https;package={ANDROID_PACKAGE};end"


def register_routes(app, invite_hmac_key: bytes, base_url: str):
    # invite_hmac_key/base_url are closure-captured here, NOT read via the
    # shared get_config() at request time - this route is registered directly
    # on the app (outside any blueprint), so request.blueprint would be None
    # for it; closure capture is what correctly scopes it to the specific
    # create_blueprint() call that registered it, even with other instances
    # also mounted on the same app.
    @app.route("/invite/<token>", methods=["GET"])
    def invite_landing_view(token):
        try:
            _invite_id, _list_id, invited_email, expires_at = decode_token(invite_hmac_key, token)
        except ApiError:
            # Blend in with "not found" rather than confirming a token-shaped
            # value was received at all.
            return render_template("invite.html", state="not_found"), 404

        if now_ms() >= expires_at:
            return render_template("invite.html", state="expired"), 410

        # Offer an in-browser "redeem" link for desktop users, but only when this app actually
        # serves the web client (its SPA catch-all is what handles /redeem) — otherwise the link
        # would 404. Presence of the web client's catch-all view is the reliable signal (T-44).
        web_redeem_url = (
            f"/redeem?token={quote(token, safe='')}"
            if "web_index" in current_app.view_functions
            else None
        )
        return (
            render_template(
                "invite.html",
                state="valid",
                token=token,
                invited_email=invited_email,
                intent_url=_intent_url(base_url, token),
                web_redeem_url=web_redeem_url,
            ),
            200,
        )
