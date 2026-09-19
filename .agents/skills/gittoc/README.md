<img src="assets/gittoc_logo256.png" alt="gittoc logo" width="128" align="right">

# gittoc

`gittoc` is a git-backed issue tracker for humans and agents.

It tries to combine the best parts of older repo-local ticket systems with some
of the task-selection and persistence ideas that matter more in agent workflows:
dependencies, ready work discovery, compact machine-readable output, and durable
local context.

## Quick install

Requires Python 3.9+ and git. The setup script assumes gittoc repo is installed to .agents/skills/gittoc/ .
To do that, execute in root of your repo:

```bash
mkdir -p .agents/skills/ && git clone --depth=1 https://github.com/dpiegdon/gittoc .agents/skills/gittoc && ./.agents/skills/gittoc/scripts/setup
```

### Local-only install (shared repos)

If you want to use gittoc on a project without committing the tool upstream,
exclude it from git tracking:

```bash
echo '.agents/skills/gittoc/' >> .git/info/exclude
```

The tool stays local to your checkout — invisible to git, never pushed. Each
collaborator who wants gittoc installs it themselves the same way.


## Repository

- authoritative repository: https://github.com/dpiegdon/gittoc

## What it is

- tickets live in git, not in an external SaaS
- canonical tracker state lives on a dedicated `gittoc` branch
- a hidden worktree at `.git/gittoc/` keeps issue files out of normal feature branches
- one compact JSON file stores the durable state of each ticket
- optional `*.events.jsonl` files store notes and ticket history
- the CLI is plain Python and git, no extra runtime dependencies

Current ticket states are directory-based:

- `issues/open`
- `issues/claimed`
- `issues/blocked`
- `issues/closed`
- `issues/rejected`

## Previous Work

`gittoc` is not pretending to be invented from nothing. It is downstream of a
few older and newer ideas:

- [`ticgit`](https://github.com/jeffWelling/ticgit): repo-local issue tracking for humans, kept in git instead of a hosted tracker
- [`nitwit`](https://github.com/lukedupin/nitwit): CLI-first, offline, git-native ticket workflow with strong “tickets belong with the code” instincts
- [`beads`](https://github.com/steveyegge/beads): agent-oriented task graph ideas such as ready work, dependencies, claims, and durable multi-session context
- [`pearls`](https://github.com/mrorigo/pearls): a lightweight Git-native distributed issue tracker for agentic workflows, with a nearby problem statement from a different implementation direction

The design goal here is roughly:

“Beads semantics, ticgit storage, minimal moving parts.”

## Why this exists

The main problem with many older repo-local trackers is that they either clutter
the working tree or rely on large text files that become awkward for agents.
The main problem with more ambitious agent trackers is that they often add too
much infrastructure: new binaries, databases, daemons, or state that drifts away
from the repo people are already working in.

`gittoc` tries to sit in the middle:

- git is still the source of truth
- the active code branch stays clean
- humans can inspect files directly if they want
- agents can query small structured results instead of parsing huge markdown blobs

### Non-goals

To stay useful, `gittoc` deliberately resists growing into a full issue tracker:

- **Notes are the audit trail.** Pinning *why* and *what was verified* survives
  compaction and hand-offs — that is the point, not a fallback.
- **Repo-local and zero-infra.** No database, daemon, or service; state travels
  with git.
- **States plus labels are enough.** No custom workflows, transitions, or
  approval ceremony.
- **Multi-writer use is load-bearing.** Concurrent agents and humans are a real
  scenario, so optimistic locking, the `actor` field, and `ref` stamping are
  treated as invariants.

## Current commands

Use `--help` on any command for full argument documentation.

```bash
# backlog overview
gittoc summary
gittoc list
gittoc list -l bug                  # filter by label
gittoc list -l feature,ux           # AND of multiple labels (comma-separated)
gittoc list -a                      # all states
gittoc list -s claimed,blocked      # specific states (comma-separated)
gittoc labels                       # all labels in use with counts
gittoc unblocked                    # only tickets with no blockers
gittoc grep "pattern"               # search ticket files

# working with tickets
gittoc new "short title" -p 2 -b "longer context" -l bug
gittoc new "blocked task" -d T-1,T-2      # create with deps (comma-separated)
gittoc claim T-42
gittoc note T-42 "found a race during creation"
gittoc note T-42 -F notes.txt        # read note text from a file
gittoc note T-42 -F -                # read note text from stdin
gittoc update T-42 -p 1 -l bug,ux    # add labels
gittoc update T-42 -x ux             # remove a label
gittoc update T-42 -L task,docs      # replace all labels
gittoc dep T-42 T-7                 # T-42 blocked by T-7
gittoc dep T-42 T-7 --remove        # remove dependency
gittoc close T-42
gittoc reject T-42                  # mark as won't-do

# inspecting tickets
gittoc show T-42                    # ticket fields + 3 recent notes
gittoc show T-42 -n                 # all notes
gittoc show T-42 -a                 # everything: all notes + full event history
gittoc show T-42 -a --limit 5       # everything, capped to 5 entries
gittoc show T-42 -f json            # JSON output
gittoc resume                       # auto-select next ticket with context
gittoc resume T-42                  # show context for a specific ticket
gittoc log T-42                     # git history for one ticket
gittoc log                          # all recent tracker changes (oldest-first)
gittoc log --no-reverse             # newest-first, like standard git log
gittoc log --limit 20               # cap to 20 entries
gittoc fsck                         # validate issue JSON, deps, cycles, and event logs

# output format (-f on any command that supports it)
gittoc list -f json
gittoc resume -f json
gittoc summary -f json

# syncing with a remote
gittoc remote                       # show remote wiring status
gittoc remote --set origin          # configure which remote to use
gittoc pull origin
gittoc push origin

# auto-push/pull on every mutating command
git config gittoc.autopush true
```

`gittoc pull` runs a read-only integrity check on changed tracker files after a
non-trivial merge commit, and `gittoc fsck` scans the whole tracker on demand.
When the local tracker is already current, `pull` reports "already up to date"
instead of a pulled-to-hash line (`merge_kind` is `unchanged` in JSON output).

Push and pull are version-gated: if collaborators are on different gittoc versions
with incompatible tracker formats, the sync is rejected before any data is written,
with a clear message explaining what to upgrade.

Command aliases: `l`=list, `s`=show, `sum`=summary, `r`=resume, `c`=claim, `n`=note,
`dep`=depends, `g`=grep, `ubl`=unblocked, `up`=update, `pl`=pull, `ps`=push.

### Recovering from duplicate ticket IDs

Two people working offline can both mint the same `T-N` before syncing. After a
pull, `gittoc fsck` reports the collision:

```
error: issues/open/T-7.json: duplicate issue record id T-7; also present at issues/claimed/T-7.json
```

There is no auto-resolver — picking the survivor and a new number is a human
judgement call. The fix is a one-time manual renumber in the tracker worktree
(`.git/gittoc/`):

```bash
gittoc list -a --sort=id            # the last id is the current maximum; +1 is free
cd .git/gittoc
git mv issues/open/T-7.json issues/open/T-12.json                  # loser -> free id
git mv issues/open/T-7.events.jsonl issues/open/T-12.events.jsonl  # its log, if any
# edit issues/open/T-12.json and set "id" to "T-12"
# repoint any ticket whose deps listed T-7 at the survivor or at T-12
git commit -am "untangle duplicate T-7"
cd -
gittoc fsck                         # confirm the tracker is clean again
```

### Note/body text and the calling shell

Note and body text passed as a command-line argument is evaluated by **your
shell** before gittoc ever sees it — backticks, `$(...)`, and `!` trigger
command substitution or history expansion and can mangle or break the command.
gittoc cannot fix the caller's shell, so for text containing shell
metacharacters either **single-quote** the argument, or bypass the shell
argument entirely with `-F` on `note`, `new`, and `update`:

```bash
gittoc note T-42 -F notes.txt            # read text from a file (most robust)
gittoc note T-42 -F - <<'EOF'            # read from stdin; QUOTE the delimiter
fix in `lock()`; see $(git rev-parse HEAD)
EOF
gittoc new "title" -F body.md            # -F is an alternative to -b
gittoc update T-42 -F body.md
```

The heredoc delimiter must be quoted (`<<'EOF'`, not `<<EOF`); an unquoted
delimiter lets the shell expand the body anyway. A single trailing newline is
stripped from file/stdin input.

### Event refs and commit ordering

Every event records the code repository's HEAD at the moment it happened, and
`show`/`resume` surface it as a short commit hash:

```
  history:
    [..] created (5e40494) alice: ...
    [..] closed  (caab8f3) bob:
```

This makes the ticket a free pointer into code history, so **commit the fix
first, then close** — the close event then stamps the fix commit rather than
the pre-fix HEAD, with no manual SHA-in-note. Hashes are checked before
display; one that no longer resolves (after a rebase, squash, or gc) is shown
with a trailing `?` (e.g. `(caab8f3?)`) instead of as a dead pointer. The raw
`ref` is unchanged in `-f json` output.

### Ticket relationships

Dependencies (`dep`) are the only structured relation — they gate readiness and
block claiming. Everything else is just a note, so you might prefix relationship
notes to keep them greppable:

```bash
gittoc note T-7 "dup-of: T-3"        # then: gittoc close T-7
gittoc note T-5 "relates: T-3"       # non-blocking related ticket
gittoc note T-9 "split-of: T-53"     # carved out of another ticket
gittoc grep "dup-of:" -a             # find them later
gittoc list -l auth-rewrite          # use labels for grouping / epics
```

Blocking is `dep`; the note prefixes are for the non-blocking links deps don't
model. Notes are searchable via `gittoc grep`.

### Triage with priority and labels

Priority is a flat urgency score and labels are an independent axis, so they
compose. One pattern you may find useful is an autonomy axis — the `agent` and
`human` labels — crossed with priority:

```bash
gittoc list -l agent --sort=priority   # safe to automate, most urgent first
gittoc list -l human                   # needs a human decision
```

If you have a local git alias `git toc`, all commands work through that too:

```bash
git toc sum
git toc l -l bug
git toc r -f json
```

### Label configuration

A standard label set with descriptions can be stored as `labels.json` on the
`gittoc` branch. When present, `git toc labels` merges it with in-use counts:
defined labels appear even at count zero (useful for backlog grooming), and
each label's description is shown alongside the count.

To set up a label set, copy the example file into your tracker branch:

```bash
cp .agents/skills/gittoc/documentation/labels.json.example .git/gittoc/labels.json
git -C .git/gittoc add labels.json
git -C .git/gittoc commit -m "add standard label set"
```

Then edit `.git/gittoc/labels.json` to match your project's conventions.
The file is optional — if absent, `git toc labels` lists only labels currently
in use.

## Project layout

- `SKILL.md`: skill instructions
- `AGENTS.md`: agent information for agents working **on gittoc itself, in the upstream repository of it**
- `scripts/gittoc`: CLI entrypoint
- `scripts/gittoc_lib/`: internal modules
- `scripts/tests/test_gittoc.py`: end-to-end test
- `scripts/dev/`: dev-only lint + test pipeline in a venv (removed on install)
