---
name: gittoc
description: Repo-local ticket and task tracker stored in git. Use whenever work needs tracking - tickets, issues, backlog, TODOs, planning, resuming earlier work, dependencies between tasks, or hand-offs between sessions and agents - without external services or nonstandard dependencies.
license: MIT. LICENSE.txt has complete terms.
compatibility: Requires python 3.9+ and git.
metadata:
  author: github.com/dpiegdon/gittoc
  version: "0.10.1"
---

# Gittoc

`gittoc` is a repo-local task tracker for humans and agents. Tickets travel
with git, with no database or background daemon.

## When to use it

Use this skill when:

- work has multiple steps, blockers, or dependencies
- progress must survive session loss or compaction
- the repository needs a durable local backlog instead of chat-only planning

Do not use it for one-off work that can be completed in a single short turn.

## Lifecycle

First set your identity: `export GITTOC_OWNER=<your agent name>` (for example
`claude`). Without it every claim, note and close is attributed to `$USER`,
the human running the session.

Every tracked piece of work then goes through these steps, in this order:

1. `gittoc resume` — see what is claimed, ready, or in progress before starting
2. `gittoc claim T-n` — before changing anything
3. work, and `gittoc note T-n '…'` findings and decisions worth keeping
4. `git commit` with `(T-n)` in the message
5. `gittoc close T-n -n 'what was verified'` — after the commit, so the close
   event points at it. On a branch that will be rebased or squashed, close
   after the merge, from the branch that keeps the commit; otherwise the
   stamped ref dies with the rebase and shows as `(abc1234?)`.

Only close a ticket whose work is complete. If part remains, note what is
left, or move the remainder to a new ticket (`split-of: T-n`) and close only
what is done.

## Operating rules

- Prefer the CLI over reading the hidden tracker checkout directly.
- Keep tickets concise — store only durable task state, not long design notes.
- Use dependencies to model blocking relationships.
- Commit tracker changes with the code they describe when practical.

## Writing tickets

- The title states the problem or the action plainly; one concern per ticket.
- The body holds what a later session needs to resume cold: the problem, where
  it is, the evidence, and the proposed fix. Prose, not a form.
- Notes record why, what was tried, and what was verified; they are the audit
  trail that survives compaction and hand-offs.

## Storage model

- Canonical tracker state lives on the `gittoc` branch.
- A hidden git worktree at `.git/gittoc/` serves as the working checkout.
- Tickets live as `issues/<state>/T-<n>.json`; directory is canonical state.
- States: `open`, `claimed`, `blocked`, `closed`, `rejected`.
- Fields: `title`, `body`, `deps`, `labels`, `owner`, `priority`.
- Optional per-ticket event history in sibling `T-<n>.events.jsonl` files.

## Suggested labels

Labels are free-form. Projects can pin a canonical set by committing
`labels.json` to the tracker branch (see `documentation/labels.json.example`);
when present, `gittoc labels` shows those labels with descriptions and counts.
The default recommended set:

- `ready` — well-defined, clearly scoped, and ready to implement
- `agent` — safe for autonomous agent implementation without human supervision
- `human` — requires human review or decision before proceeding
- `feature` — new functionality
- `bug` — something is broken or behaves incorrectly
- `ux` — user experience improvements
- `docs` — documentation improvements
- `chore` — maintenance tasks, dependency updates, housekeeping
- `refactor` — internal code cleanup that preserves existing behaviour
- `structure` — reorganise files, modules, or repo layout without logic changes
- `perf` — performance improvements
- `reliability` — error handling, fault tolerance, resilience, and robustness
- `security` — security-related fix or improvement
- `ops` — build, test, deployment, or infrastructure tooling

Priority and labels are orthogonal axes, so they compose for triage — e.g.
`list -l agent --sort=priority` surfaces autonomously-safe work by urgency.

## Commands

Invoke as `git toc <command>`, `gittoc <command>` or `.agents/skills/gittoc/scripts/gittoc <command>`.
Use `--help` on any command for full argument documentation.

**Output format**
— listing commands accept `-f compact|normal|verbose|json`; other commands accept `-f text|json` where applicable

**Inspecting tickets**
- `resume` / `r` — context for the most relevant current ticket
- `resume T-1` — context for a specific ticket
- `show T-1` / `s T-1` — ticket fields + 3 recent notes
- `show T-1 -n` — all notes
- `show T-1 -a` — everything: all notes + full event history
- `show T-1 --limit 5` — cap entries shown
- `show T-1 -f json` — JSON output for scripting
- `log T-1` — git history for one ticket file (oldest-first)
- `log` — all recent tracker changes (oldest-first)
- `log --no-reverse` — newest-first, like standard git log
- `log --limit N` — cap to at most N entries
- `fsck` — validate issue JSON, event JSONL, dangling deps, cycles, and orphaned logs

**Backlog**
- `summary` / `sum` — ticket counts by state
- `list` / `l` — live tickets (open, claimed, blocked) by priority; `-a` for all states
- `list -s open` — specific states (comma-separated)
- `list -l bug` / `list -l feature,ux` — filter by label (AND; comma-separated)
- `unblocked` / `ubl` — only tickets with no unmet dependencies
- `labels` / `labels -a` — labels on live tickets with counts; `-a` includes closed/rejected
- `grep` / `g` `PATTERN [-i] [-n]` — search live ticket files; `-a` for all states, `-s closed,rejected` for specific
- `list --sort=id` — chronological order instead of priority

**Working with tickets**
- `new "Title" -p 2 -b "context" -l feature` — create a ticket
- `new "Blocked task" -d T-1,T-2` — create with dependencies
- `claim T-1` — claim a ticket (defaults owner to `$GITTOC_OWNER` / `$USER`); refuses a ticket someone else holds unless `--take`
- `claimed` / `c` — list all currently claimed issues
- `update` / `up` `T-1 --state blocked -p 4` — update fields
- `update T-1 -l bug,ux` — add labels
- `update T-1 -x ux` — remove labels
- `update T-1 -L task,docs` — replace all labels
- `depends` / `dep` `T-2 T-1` — make T-2 depend on T-1 (T-1 must complete first)
- `dep T-2 T-1,T-3,T-4` — add multiple blockers (comma-separated)
- `dep T-2 T-1 --remove` / `dep T-2 T-1 -r` — remove a dependency
- `note` / `n` `T-1 "context"` — append a durable note
- `note T-1 -F FILE` / `note T-1 -F -` — read note text from a file or stdin
- `new`/`update` `-F FILE` — read body from a file or stdin (alternative to `-b`)
- `close T-1 [T-2 …]` — close as done; `-n 'why'` or `-F FILE` records the reason in the close event
- `reject T-1 [T-2 …]` — close as won't-do; takes `-n` / `-F` like close

**Remote sync**
- `init` — create tracker branch / attach worktree; auto-configures `gittoc.remote` if inferable
- `remote` — inspect tracker remote wiring
- `remote --set origin` — configure tracker remote
- `pull` / `pl` / `pul` `[remote]` — fetch and merge tracker branch (uses configured remote by default)
- `push` / `ps` / `pus` `[remote]` — push tracker branch (uses configured remote by default)
- auto-push/pull: enable with `git config gittoc.autopush true` (or `.agents/skills/gittoc/scripts/setup --autopush`);
  every mutating command will then pull before and push after the local write.
  Non-trivial pull merges automatically run `fsck` against the changed tracker files.

## Workflow details

When new follow-up work appears:

```bash
gittoc new "Add feature" -p 3
gittoc new "Blocked task" -d T-1,T-2     # create with dependencies
gittoc dep T-3 T-1   # T-3 depends on T-1 (T-1 must complete first)
```

Finishing:

```bash
git commit ...                         # commit the fix FIRST
gittoc close T-1 -n 'tests pass'       # then close — the event stamps the fix commit
```

Every event records the code repo's HEAD at event time, and `show`/`resume`
surface it as a short hash (e.g. `closed (04bb189) owner:`). Commit the fix
**before** closing so the close event points at the fix commit, not the
pre-fix HEAD — this gives a free pointer from the ticket into code history,
with no manual SHA-in-note. A hash that no longer resolves (after a
rebase/squash/gc) is shown with a trailing `?`.

## Ticket relationships

Dependencies (`dep`) are the only structured relation. They gate readiness and
block claiming — use them for real ordering constraints.

All other cross-references are just notes; a greppable prefix is one way to keep
them findable:

```bash
gittoc note T-7 "dup-of: T-3"      # duplicate; then: gittoc close T-7
gittoc note T-5 "relates: T-3"     # related, non-blocking
gittoc note T-9 "split-of: T-53"   # carved out of another ticket
gittoc list -l auth-rewrite        # labels for grouping / epics
```

Notes are searchable via `gittoc grep`.

## Notes

- Note/body text given as a CLI argument is evaluated by the calling shell:
  backticks, `$(...)`, and `!` trigger substitution and can corrupt or break
  the command. **Single-quote** such text, or pass it via `-F FILE` / `-F -`
  (stdin) on `note`, `new`, and `update`. With a stdin heredoc, quote the
  delimiter (`<<'EOF'`) or the shell still expands the body. Agents writing
  backtick-heavy prose should prefer `-F` — a single trailing newline is
  stripped from file/stdin input.
- Mutating commands serialize through a lock file in `.git` (readers never
  wait) and additionally refuse to commit if the tracker changed mid-command.
  On "tracker changed during this command", review the new state and re-run if
  still applicable. A stale lock from a crashed process is removed automatically.
- `resume` without an ID prefers claimed tickets owned by the current user,
  then highest-priority ready issue, then highest-priority open issue.
- `pull` fetches and attempts a normal merge; conflicts are left for manual
  resolution in `.git/gittoc/`.
- after a non-trivial `pull` merge commit, `gittoc` automatically runs `fsck`
  on the changed tracker files and fails loudly if it finds integrity issues.
- `init` auto-configures `gittoc.remote` from the repo's inferred main remote.
- In sandboxed environments, writes under `.git/gittoc/` may require explicit
  approval. If mutations fail with a permission error, the sandbox may be
  blocking `.git` writes rather than the tool itself.
