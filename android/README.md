# Shopping List — Android client

Jetpack Compose client for the shopping-list server (`server/`). See
`docs/superpowers/specs/client-ui-notes.md` for the product requirements and
`docs/superpowers/plans/2026-07-08-shopping-list-tickets.md` for the Wire
Contract this app implements against.

## Invite links / App Links

Invites are shared as `https://<your-server>/invite/<token>` links. The app
declares an intent-filter for `https://*/invite/*` (see `AndroidManifest.xml`)
so tapping such a link opens straight into the redeem flow when this is the
only app installed that claims it.

This is **not** a fully verified [Android App
Link](https://developer.android.com/training/app-links/verify-android-applinks)
out of the box, because the server has no fixed public host (Notes: "no fixed
public URL, self-hosted") — Android's App Link verification (`autoVerify`)
checks a Digital Asset Links file at exactly one declared host, and this app
doesn't know your host ahead of time.

If you self-host the server and want the stronger, verified App Link behavior
(the link opens this app directly, with no disambiguation dialog, even when
other apps could also handle `https://` links), publish a Digital Asset
Links file for your server's host:

```
https://<your-server>/.well-known/assetlinks.json
```

containing an entry naming this app's package (`org.p23q.shoppinglist`) and
your release signing certificate's SHA-256 fingerprint. See the Digital Asset
Links documentation linked above for the exact JSON shape. The server package
in this repo does not currently serve this file for you — it would need to be
added to whatever process hosts your server deployment.

Without it, invite links still work via the paste-a-code fallback ("Join
list" in the app's drawer menu), or by the OS's normal disambiguation prompt
when more than one app can open the link.
