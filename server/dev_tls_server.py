"""Minimal TLS-enabled dev server for client-side testing (Android/web) against
a real HTTPS endpoint, on port 8723.

Uses Flask/Werkzeug's ad-hoc mode: a self-signed certificate generated in
memory at startup (requires the `cryptography` package, part of the `dev`
extra). This is NOT for production — see README.md's "Deployment
requirements" for real TLS termination via a reverse proxy.

Run from the server/ directory:

    python dev_tls_server.py

Override BASE_URL if a client needs to reach this from somewhere other than
localhost (e.g. a physical device on the same LAN, or an Android emulator via
its host-loopback address):

    BASE_URL=https://192.168.1.42:8723 python dev_tls_server.py
"""

import os

PORT = 8723

os.environ.setdefault("DATABASE_PATH", "dev_tls.db")
os.environ.setdefault("INVITE_HMAC_KEY", "dev-tls-invite-hmac-key")
os.environ.setdefault("BASE_URL", f"https://localhost:{PORT}")

from app import app  # noqa: E402  (env vars must be set before this import)

if __name__ == "__main__":
    print(f"Serving HTTPS (self-signed) on 0.0.0.0:{PORT}, BASE_URL={os.environ['BASE_URL']}")
    app.run(host="0.0.0.0", port=PORT, ssl_context="adhoc", debug=False)
