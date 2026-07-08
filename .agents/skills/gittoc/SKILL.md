---
name: gittoc
description: Git-repository-specific ticket system. Use when work spans multiple turns or sessions and needs a repo-local issue tracker with dependencies, ready-task discovery, and git history, without external services or nonstandard dependencies.
license: MIT. LICENSE.txt has complete terms.
compatibility: Requires python 3.9+ and git.
metadata:
  author: codeberg.org/dpiegdon/gittoc
  version: "0.8.0"
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

## Operating rules

- Prefer the CLI over reading the hidden tracker checkout directly.
- Keep tickets concise — store only durable task state, not long design notes.
- Use dependencies to model blocking relationships.
- Commit tracker changes with the code they describe when practical.

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
- `list` / `l` — open tickets by priority; `-a` for all states
- `list -s claimed,blocked` — filter by state (comma-separated)
- `list -l bug` / `list -l feature,ux` — filter by label (AND; comma-separated)
- `unblocked` / `ubl` — only tickets with no unmet dependencies
- `labels` / `labels -a` — all labels in use with counts
- `grep` / `g` `PATTERN [-i] [-n]` — search open ticket files; `-a` for all states, `-s closed,rejected` for specific
- `list --sort=id` — chronological order instead of priority

**Working with tickets**
- `new "Title" -p 2 -b "context" -l feature` — create a ticket
- `new "Blocked task" -d T-1,T-2` — create with dependencies
- `claim T-1` — claim a ticket (defaults owner to `$GITTOC_OWNER` / `$USER`)
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
- `close T-1` — close as done
- `reject T-1` — close and reject ticket as won't-do

**Remote sync**
- `init` — create tracker branch / attach worktree; auto-configures `gittoc.remote` if inferable
- `remote` — inspect tracker remote wiring
- `remote --set origin` — configure tracker remote
- `pull` / `pl` / `pul` `[remote]` — fetch and merge tracker branch (uses configured remote by default)
- `push` / `ps` / `pus` `[remote]` — push tracker branch (uses configured remote by default)
- auto-push/pull: enable with `git config gittoc.autopush true` (or `.agents/skills/gittoc/scripts/setup --autopush`);
  every mutating command will then pull before and push after the local write.
  Non-trivial pull merges automatically run `fsck` against the changed tracker files.

## Recommended workflow

At the start of multi-step work:

```bash
gittoc resume
```

Beginning a task:

```bash
gittoc claim T-1
```

When new follow-up work appears:

```bash
gittoc new "Add feature" -p 3
gittoc new "Blocked task" -d T-1,T-2     # create with dependencies
gittoc dep T-3 T-1   # T-3 depends on T-1 (T-1 must complete first)
```

Finishing:

```bash
git commit ...        # commit the fix FIRST
gittoc close T-1      # then close — the close event stamps the fix commit
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
- Mutating commands use optimistic concurrency; they refuse to commit if the
  tracker changed mid-command. Review the new state and re-run the command if still applicable.
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
