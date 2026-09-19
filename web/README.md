# Tuppu — web client

React + TypeScript SPA for the shopping-list server (`../server/`). Online
only (no offline storage) — same-origin with its own API by construction, so
unlike the Android app it needs no server-URL setting. See
[`../docs/wire-contract.md`](../docs/wire-contract.md) for the interface this app
implements against.

**This app is not deployed separately.** `npm run build` writes straight into
`../server/src/shoppinglist_server/web_dist/`, which the server package embeds
and serves directly — see `../server/README.md`'s "Web client" section. With the
server installed in development mode (`pip install -e`), rebuilding is all it
takes to update what it serves; a deployment gets it with the next wheel.

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

- `src/api/` — `contract.ts` (types mirroring the wire contract) and
  `client.ts` (typed fetch wrapper; the bearer token lives in `localStorage`, so
  a restored tab or browser restart keeps the session).
- `src/auth/` — login/register/logout state (`AuthContext`).
- `src/hooks/useSync.ts` + `SyncContext.tsx` — the sync store: pushes and pulls
  against `POST /sync`, shared across all pages. The cursor is kept in memory
  only: with no durable local mirror, every page load asks for a full snapshot.
- `src/pages/` — one file per route. `ListRoute.tsx` picks the screen by list
  kind: `ListPage` for shopping lists and checklists, `ExpenseListPage` and
  `BalancesPage` for expense lists. `RegistryPage` is All items; `AdminPage` is
  Server admin.
- `src/lib/` — pure logic, tested on its own: category grouping, expense
  arithmetic (`expenses.ts`), name order (`nameOrder.ts`), money and date
  formatting (`format.ts`). The expense and name-order rules are tested against
  the same case tables as Android, in `../shared-test-cases/`.
- `src/i18n/` — the nine catalogs in `messages/` (English is the fallback), and
  `apiErrors.ts`, which translates server errors by their code.
  `crossClient.test.ts` fails if a string shared with the Android app reads
  differently there, in any language.
