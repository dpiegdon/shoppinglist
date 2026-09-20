# Working on this repo

Standing instructions from the maintainer for coding agents, gathered over past
sessions. The READMEs describe the code; this file describes how to work on it.

## Workflow

- **Every committed change needs a ticket.** That includes small UI tweaks. File
  it in gittoc before or during the work. Reference it in the commit message
  (`… (T-193)`), and close it in the same pass as the commit. The tracker lives on
  the `gittoc` branch, checked out as a worktree at `.git/gittoc`. Drive it with
  `git toc`; see `.agents/skills/gittoc/`. Commands: `new "<title>" -F body.md -l
  labels -p N`, `note <id> "text"` (or `-F file`; there is no `-b`), `close`,
  `reject`, `show`, `summary`.
- **Always commit finished work**, on `main` unless told otherwise. Don't wait
  to be asked.
- **Never rewrite history.** No amend, no rebase, no deleting or moving tags. Fix
  a mistake with a follow-up commit. If a commit went in without a ticket, file one
  afterwards.
- **Read `git status --short` before any `git add -A`.** `release.sh` bumps
  versions and rebuilds the web bundle *before* it verifies. An interrupted run
  leaves those changes in the tree; once they were swept into an unrelated
  commit (reverted in 52a6223).
- **No pushing or pulling.** The maintainer handles upstreaming. Don't mention
  pushing, remotes or "not yet pushed" in summaries.
- **Don't raise backups** of the repo, tracker or keystore. They exist off the
  machine; T-95 was rejected.
- `android/keystore/release.jks` and `android/keystore.properties` are never
  committed.

## Working in parallel

- Agents get their own git worktree (under `.claude/worktrees/`, ignored). A worktree
  is a fresh checkout: create its **own** `server/.venv` (never reuse the main
  checkout's — that editable install imports the main checkout's code, not
  yours); `ln -s <main checkout>/web/node_modules web/node_modules` instead of
  `npm install`, which rewrites the lockfile and fails the gate; write
  `android/local.properties`; `./gradlew --stop` when done.
- Agents commit on their branch and never touch gittoc, push, or release. The
  orchestrator reads every diff, cherry-picks onto `main`, runs the gate, closes
  the ticket, removes the worktree. Reviewing is not optional: it has caught
  real defects that a pick-and-gate script would have waved through.
- Commit trailer, exactly: `Co-Authored-By: Claude <model> <noreply@anthropic.com>`
  with the plain model name (`Claude Opus 5`, `Claude Fable 5.1`), no context-size
  suffix. The harness may suggest a longer form; this repo's convention wins.

## Both clients, always

- Every user-facing change goes to **web and Android together**, with the same
  layout, wording and behaviour. The maintainer checks parity; when unsure, look
  at how the other client does it.
- Every user-visible string exists in **all nine locales** (en, de, fr, es,
  pt-BR, uk, ar, ja, zh-Hans) on both clients: `web/src/i18n/messages/*.ts` and
  `android/app/src/main/res/values*/strings.xml`. `web/src/i18n/crossClient.test.ts`
  pairs the two by identical English and fails on any drift.
- Server errors are shown by code. A new code goes into
  `web/src/i18n/apiErrors.ts`, `android/…/ui/ErrorText.kt`, and the error table in
  `docs/wire-contract.md`.
- Logic both clients share (expense arithmetic, name order) is tested against
  `shared-test-cases/*.json`. Change the table, then both implementations.
- Update `docs/wire-contract.md` with every wire change. READMEs describe current
  behaviour only, without history or ticket anecdotes. Files in `docs/archive/`
  are not maintained.

## Server rules

- Sync rules apply only to a write that would win field-level last-write-wins. A
  stale write that loses is discarded silently, never refused.
- Refusing one row is `422` with `row_id`, so clients quarantine that row
  instead of wedging their push queue.
- Run `cd server && .venv/bin/isort . && .venv/bin/black .` before verifying.
  The lint stage only checks.
- **Protocol version.** Every API request carries `X-Client-Protocol`; anything
  older than the server's `PROTOCOL_VERSION` is refused `426 client_outdated`
  before authentication. Bump that number only for a wire change an installed
  client of the previous protocol cannot handle correctly — a bump turns away
  every app in the field, so the release that ships it is a **major** one, and
  `release.sh` refuses a release whose version and protocol disagree. The number
  lives in three files that must agree (`server/…/protocol.py`,
  `web/src/api/protocol.ts`, Android's `Protocol.kt`), and every bump adds a row
  to the changelog in the "Protocol version" section of `docs/wire-contract.md`.

## Tests

- Run `./verify-all.sh` before committing code; a docs-only change may skip it.
  While iterating, run just the suite you touched.
- A new test should fail when its change is reverted. Check by mutating the code.
- Android (Robolectric) pitfalls:
  - Use Room with `BundledSQLiteDriver()`: Robolectric has no native SQLite on
    aarch64. Room 2.8 cannot run migrations on the driver path; see
    `AppDbMigrationTest.kt` for the workaround.
  - Don't put a Compose test rule and `MainDispatcherRule` in one class; they
    wait on each other forever. Screen tests use `runBlocking<Unit>` (JUnit
    rejects a non-Unit return).
  - A test tag inside merged semantics needs `useUnmergedTree = true`. Long
    screens need `@Config(qualifiers = "w411dp-h891dp")`.
  - If a view model keeps requests running after a screen test, cancel its
    `viewModelScope` at the end. Otherwise later tests flake with "Dispatchers.Main
    is used concurrently".
  - Tests run under `RobolectricTestApp`, not the real `ShoppingListApp`: that one
    starts WorkManager, whose own Room database sits on Robolectric's SQLite
    shadows and outlives the test, so its invalidation refresh blames the *next*
    test with "uncaught exceptions before the test started" (T-209). A `tearDown`
    closing a `lateinit` field guards it with `::field.isInitialized`, so an
    aborted `setUp` reports its real cause.

## Releases

- Cut one only when asked. File the release ticket first, then run
  `./release.sh X.Y.Z T-nnn --notes FILE`. The notes are short prose for people
  using the app: what changed, grouped, no ticket numbers. Close the ticket once
  the tag is made.
- On an ARM host whose Android SDK ships an x86_64 `aapt2`, pass
  `AAPT2=<a working aapt2>`. The original Raspberry Pi used a qemu wrapper at
  `/home/claude/tools/bin/aapt2`.
- Afterwards, give the maintainer the wheel (`server/dist/*.whl`). They install it
  and test on a real device, so don't file device-verification tickets.
- Never re-cut a tagged version. By the time a defect shows up, the APK is
  already installed, so supersede it instead (1.9.0 → 1.9.1).

## Host notes

- On a low-RAM host (the original Pi had about 4 GB), stop the Gradle daemon
  after Android work (`cd android && ./gradlew --stop`), batch Android builds, and
  don't leave watchers or dev servers running. Only touch your own user's
  processes.
