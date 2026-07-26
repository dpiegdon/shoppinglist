# Shopping List — web client

React + TypeScript SPA for the shopping-list server (`../server/`). Online
only (no offline storage) — same-origin with its own API by construction, so
unlike the Android app it needs no server-URL setting. See
[`../docs/wire-contract.md`](../docs/wire-contract.md) for the interface this app
implements against.

**This app is not deployed separately.** `npm run build` writes straight into
`../server/src/shoppinglist_server/web_dist/`, which the server package embeds
and serves directly — see `../server/README.md`'s "Web client" section for how
that's wired up. Building here is the only step needed to update what the
running server serves.

## Development

```bash
npm install
npm run dev      # Vite dev server; proxies /api and /invite to a real
                  # backend (default http://localhost:5000, override via
                  # VITE_API_PROXY_TARGET) since this runs on its own port
npm run build     # production build -> ../server/src/shoppinglist_server/web_dist/
npm test          # Vitest + React Testing Library
npm run lint      # oxlint
npx tsc --noEmit -p tsconfig.app.json   # typecheck
```

A production build's output must be **committed** (it's package data of the
server) — until you rebuild and commit, the served web app doesn't include
your source changes.

## Structure

- `src/api/` — `contract.ts` (types mirroring the Wire Contract exactly) and
  `client.ts` (typed fetch wrapper, bearer token in memory + `sessionStorage`).
- `src/auth/` — login/register/logout state (`AuthContext`).
- `src/hooks/useSync.ts` + `SyncContext.tsx` — the sync store: pushes/pulls
  against `POST /sync`, shared across all pages via context. The cursor is
  kept in memory only (never persisted) — this app has no durable local
  mirror, so every fresh page load correctly requests a full snapshot rather
  than trusting a stale incremental cursor.
- `src/lib/grouping.ts` — pure category-grouping logic for the list view
  (todo and checked items mixed into one sorted list per category; backlog
  never shown).
- `src/pages/` — one file per route (login, overview, list, registry, list
  properties, settings, invite redeem).
