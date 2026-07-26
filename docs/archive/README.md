# Archive

Point-in-time design and planning documents from the original build, kept as a
record of how the project got here. **They are not maintained and are not a
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

These documents were written to be executed by AI coding agents, so they carry
agent-directed instructions ("use this skill", model tags per ticket, host-specific
build notes). Read them as history, not as directions.
