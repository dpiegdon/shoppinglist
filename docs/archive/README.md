# Archive

Point-in-time design and planning documents — most from the original build, a
few from later features — kept as a record of how the project got here. **They are not maintained and are not a
description of current behavior.**

For anything you actually need:

- **How it works today** — the READMEs: [root](../../README.md),
  [`server/`](../../server/README.md), [`web/`](../../web/README.md),
  [`android/`](../../android/README.md).
- **The client/server interface** — [`docs/wire-contract.md`](../wire-contract.md),
  which is authoritative and maintained. It was extracted from
  `plans/2026-07-08-shopping-list-tickets.md`, whose copy is now frozen and
  outdated; do not use it.

## Contents

| File | What it was |
|---|---|
| [`specs/2026-07-08-shopping-list-server-design.md`](specs/2026-07-08-shopping-list-server-design.md) | The server & API spec written before implementation started. Still the best statement of *why* the sync model looks the way it does; endpoint details have since drifted. |
| [`specs/client-ui-notes.md`](specs/client-ui-notes.md) | Raw capture of client requirements, gathered while the server spec was being finalized. Both clients were subsequently built from it. |
| [`plans/2026-07-08-shopping-list-tickets.md`](plans/2026-07-08-shopping-list-tickets.md) | The original ticket breakdown for the whole system (server, Android, web epics). Ongoing work moved to the gittoc tracker on the `gittoc` branch afterward. |
| [`plans/2026-07-15-t65-collaborator-change-notifications.md`](plans/2026-07-15-t65-collaborator-change-notifications.md) | Implementation plan for one feature (collaborator-change notifications), kept as a worked example of the plan format. |
| [`specs/2026-08-11-android-update-check-design.md`](specs/2026-08-11-android-update-check-design.md) | Design for the in-app update check (T-135), written before it was built. Later than the rest of this directory. The endpoint it describes is documented for real in [`../wire-contract.md`](../wire-contract.md). |
| [`specs/2026-09-17-expense-lists-design.md`](specs/2026-09-17-expense-lists-design.md) | Design for the `expenses` list kind (T-150 and the `expenses` label): the decisions and the reasons behind them. The tickets carry the work; this carries the why. Once built, the wire shapes it describes are authoritative only in [`../wire-contract.md`](../wire-contract.md). |

These documents were written to be executed by AI coding agents, so they carry
agent-directed instructions ("use this skill", model tags per ticket, host-specific
build notes). Read them as history, not as directions.
