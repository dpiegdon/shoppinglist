"""Core tracker logic: worktree management, issue storage, and state transitions."""

from __future__ import annotations

import json
import sys
from dataclasses import replace
from pathlib import Path

from . import CURRENT_FORMAT_VERSION, CURRENT_LAYOUT_VERSION, VERSION_FILE
from . import colors as col
from .common import (
    EVENT_SUFFIX,
    ISSUES_ROOT,
    STATE_ORDER,
    STATE_SET,
    TERMINAL_STATES,
    TRACKER_BRANCH,
    branch_exists,
    current_branch,
    default_owner,
    has_legacy_hidden_clone,
    infer_remote,
    is_worktree,
    issue_number,
    now_utc,
    remote_branch_exists,
    repo_and_worktree,
    run_git,
    validate_issue_id,
    validate_priority,
    validate_title,
)
from .event_log import EventLog
from .lock import LOCK_FILENAME, MutationLock
from .models import Issue
from .remote_sync import RemoteSync


class StaleTrackerError(Exception):
    """Raised when the tracker has been modified since it was opened."""


def write_version_file(
    checkout: Path, format_version: int, layout_version: int
) -> None:
    """Write the tracker's VERSION file (shared by bootstrap and migrations)."""
    data = {
        "format_version": format_version,
        "layout_version": layout_version,
        "migrated_at": now_utc(),
        "migrated_by": default_owner(),
    }
    with (checkout / VERSION_FILE).open("w", encoding="utf-8") as handle:
        json.dump(data, handle, indent=2, sort_keys=True)
        handle.write("\n")


class Tracker:
    """Manages the gittoc issue store on the dedicated tracker branch."""

    def __init__(self, repo: Path, checkout: Path):
        """Initialise with repo root and tracker worktree paths."""
        self.repo = repo
        self.checkout = checkout
        self.base_head = self.head()
        self._state_cache: dict[str, str] = {}
        # Worktree paths written by the in-flight mutation (see begin_write).
        self._pending: list[Path] = []
        self._warned_missing_deps: set[tuple[str, str]] = set()
        self.events = EventLog(self)
        self.remote = RemoteSync(self)

    @classmethod
    def open(cls) -> "Tracker":
        """Open the tracker, ensuring the worktree exists and running migrations."""
        repo, checkout = repo_and_worktree()
        checkout = cls._ensure_worktree(repo, checkout)
        tracker = cls(repo, checkout)
        # __init__ already read HEAD; only re-read if a migration committed.
        if tracker.run_pending_migrations():
            tracker.base_head = tracker.head()
        tracker.check_version_compatible()
        return tracker

    @staticmethod
    def _ensure_worktree(repo: Path, checkout: Path) -> Path:
        """Ensure the hidden gittoc worktree exists, creating or attaching it as needed."""
        if has_legacy_hidden_clone(checkout):
            raise SystemExit(
                f"legacy hidden clone detected at {checkout}; remove it before using worktree mode"
            )
        if is_worktree(checkout):
            # Worktree links are absolute paths, so a moved or copied repository
            # leaves the tracker checkout pointing at the old location.
            probe = run_git(["rev-parse", "--git-dir"], cwd=checkout, check=False)
            if probe.returncode != 0:
                raise SystemExit(
                    f"tracker worktree at {checkout} is detached from this repository "
                    "(was the repository moved?); repair the link with:\n"
                    f"  git worktree repair {checkout}"
                )
            if current_branch(checkout) != TRACKER_BRANCH:
                run_git(["switch", "-q", TRACKER_BRANCH], cwd=checkout)
            return checkout
        if branch_exists(repo, TRACKER_BRANCH):
            run_git(
                ["worktree", "add", "--force", str(checkout), TRACKER_BRANCH], cwd=repo
            )
            return checkout
        remote = infer_remote(repo)
        if remote and remote_branch_exists(repo, remote, TRACKER_BRANCH):
            run_git(
                ["branch", "--track", TRACKER_BRANCH, f"{remote}/{TRACKER_BRANCH}"],
                cwd=repo,
            )
            run_git(
                ["worktree", "add", "--force", str(checkout), TRACKER_BRANCH], cwd=repo
            )
            return checkout
        return Tracker._bootstrap_worktree(repo, checkout)

    @staticmethod
    def _bootstrap_worktree(repo: Path, checkout: Path) -> Path:
        """Create an orphan tracker branch with an empty issues directory structure."""
        import shutil

        # git worktree add requires at least one commit; create one if the repo is empty.
        proc = run_git(["rev-parse", "--verify", "HEAD"], cwd=repo, check=False)
        if proc.returncode != 0:
            run_git(["commit", "--allow-empty", "-q", "-m", "Initial commit"], cwd=repo)
        run_git(
            ["worktree", "add", "--detach", "--force", str(checkout), "HEAD"], cwd=repo
        )
        run_git(["checkout", "-q", "--orphan", TRACKER_BRANCH], cwd=checkout)
        run_git(["rm", "-rf", "--cached", "."], cwd=checkout, check=False)
        for path in checkout.iterdir():
            if path.name == ".git":
                continue
            if path.is_dir():
                shutil.rmtree(path)
            else:
                path.unlink()
        for state in STATE_ORDER:
            (checkout / ISSUES_ROOT / state).mkdir(parents=True, exist_ok=True)
        keep = checkout / ISSUES_ROOT / ".gitkeep"
        keep.write_text("", encoding="utf-8")
        write_version_file(checkout, CURRENT_FORMAT_VERSION, CURRENT_LAYOUT_VERSION)
        run_git(["add", "issues", str(VERSION_FILE)], cwd=checkout)
        run_git(
            ["commit", "-q", "-m", "Initialize gittoc tracker"],
            cwd=checkout,
        )
        return checkout

    def head(self) -> str:
        """Return the current HEAD commit hash of the tracker branch."""
        proc = run_git(
            ["rev-parse", "--verify", "HEAD"], cwd=self.checkout, check=False
        )
        return proc.stdout.strip() if proc.returncode == 0 else ""

    def read_version(self) -> tuple[int, int]:
        """Read the VERSION file and return (format_version, layout_version).

        Returns (0, 0) if the file does not exist (pre-versioning baseline).
        Raises SystemExit on malformed or unreadable VERSION files.
        """
        path = self.checkout / VERSION_FILE
        if not path.exists():
            return (0, 0)
        try:
            with path.open("r", encoding="utf-8") as handle:
                data = json.load(handle)
            return (data["format_version"], data["layout_version"])
        except (json.JSONDecodeError, KeyError, TypeError) as exc:
            raise SystemExit(f"malformed VERSION file ({path}): {exc}") from exc

    def _write_version(
        self, format_version: int, layout_version: int, *, commit: bool = True
    ) -> None:
        """Write the VERSION file and optionally commit it."""
        write_version_file(self.checkout, format_version, layout_version)
        if commit:
            run_git(["add", str(VERSION_FILE)], cwd=self.checkout)
            run_git(
                [
                    "commit",
                    "-q",
                    "-m",
                    f"gittoc: migrate to format v{format_version} layout v{layout_version}",
                ],
                cwd=self.checkout,
            )

    def check_version_compatible(self) -> None:
        """Abort if the local tracker version is newer than this client supports."""
        fmt, layout = self.read_version()
        if fmt > CURRENT_FORMAT_VERSION:
            raise SystemExit(
                f"tracker requires format version {fmt}, "
                f"but this gittoc only supports up to {CURRENT_FORMAT_VERSION} — "
                f"please upgrade gittoc"
            )
        if layout > CURRENT_LAYOUT_VERSION:
            raise SystemExit(
                f"tracker requires layout version {layout}, "
                f"but this gittoc only supports up to {CURRENT_LAYOUT_VERSION} — "
                f"please upgrade gittoc"
            )

    def ensure_not_stale(self) -> None:
        """Raise StaleTrackerError if the tracker has been modified since it was opened."""
        current = self.head()
        if current != self.base_head:
            raise StaleTrackerError(
                "tracker changed during this command; re-run your command to retry"
            )

    def lock_for_mutation(self) -> None:
        """Serialize this command against other writers of the shared worktree.

        Taken once per mutating command, before the auto-pull and before any
        issue is read, and released when the process exits. The tracker HEAD
        is re-read afterwards: other writers may have committed while we
        waited, and nothing has been read yet, so that is not a conflict.
        """
        self.mutation_lock = MutationLock(self.checkout.parent / LOCK_FILENAME)
        self.mutation_lock.acquire()
        self.base_head = self.head()
        self._state_cache.clear()

    def begin_write(self, *paths: Path) -> None:
        """Gate a worktree write: check staleness first, then record the paths.

        Every writer (issue JSON, event log) calls this before touching disk.
        The staleness check runs before the *first* write of a mutation, so a
        tracker that another process committed to since ``open()`` is detected
        before anything lands in the shared worktree. The recorded paths are
        the only ones ``commit_if_needed`` stages, and they let it roll the
        mutation back if the final pre-commit check loses the race.
        """
        if not self._pending:
            self.ensure_not_stale()
        self._pending.extend(paths)

    def discard_pending(self) -> None:
        """Restore every path recorded by begin_write to its HEAD content.

        Paths present in HEAD are checked out from it (which also recreates
        files the mutation deleted or moved); paths absent from HEAD, even if
        staged, are removed. Only the
        paths this mutation touched are reverted, so another writer's
        uncommitted files in the shared worktree are left alone.
        """
        rel = self._pending_rel()
        self._pending.clear()
        if not rel:
            return
        # Decide "tracked" against HEAD, not the index: a path another process
        # staged but has not committed is absent from HEAD, and checking it
        # out from HEAD would fail. Such paths are simply removed.
        listed = run_git(
            ["ls-tree", "--name-only", "HEAD", "--", *rel], cwd=self.checkout
        ).stdout
        tracked = {line for line in listed.splitlines() if line}
        restore = [r for r in rel if r in tracked]
        if restore:
            run_git(["checkout", "-q", "HEAD", "--", *restore], cwd=self.checkout)
        for r in rel:
            if r not in tracked:
                target = self.checkout / r
                if target.exists():
                    target.unlink()
        self._state_cache.clear()

    def issues_root(self) -> Path:
        """Return the path to the issues root directory in the tracker worktree."""
        return self.checkout / ISSUES_ROOT

    def state_dir(self, state: str) -> Path:
        """Return the directory path for a given issue state, raising on invalid state."""
        if state not in STATE_SET:
            raise SystemExit(
                f"invalid state: {state} (valid: {', '.join(STATE_ORDER)})"
            )
        return self.issues_root() / state

    def issue_path(self, issue_id: str, state: str) -> Path:
        """Return the expected JSON file path for an issue in the given state."""
        return self.state_dir(state) / f"{validate_issue_id(issue_id)}.json"

    def find_issue_path(self, issue_id: str) -> Path:
        """Return the path where the issue lives, or exit if it does not exist."""
        issue_id = validate_issue_id(issue_id)
        state = self._issue_state(issue_id)
        if state is None:
            raise SystemExit(f"issue not found: {issue_id}")
        return self.issue_path(issue_id, state)

    def _pending_rel(self) -> list[str]:
        """Return the unique worktree-relative paths recorded by begin_write."""
        return [
            str(path.relative_to(self.checkout))
            for path in dict.fromkeys(self._pending)
        ]

    def commit_if_needed(self, message: str, actor: str | None = None) -> None:
        """Stage and commit the paths this mutation wrote, if any changed.

        Only the paths recorded by ``begin_write`` are staged. The shared
        worktree may hold another process's not-yet-committed files, and
        staging the whole ``issues`` tree would sweep those into this commit
        under the wrong message and actor.
        """
        rel = self._pending_rel()
        if not rel:
            return
        proc = run_git(["status", "--porcelain", "--", *rel], cwd=self.checkout)
        if not proc.stdout.strip():
            self._pending.clear()
            return
        try:
            self.ensure_not_stale()
        except StaleTrackerError:
            # Lost the race after writing: undo our files so the next writer
            # (or our own re-run) does not sweep them into an unrelated commit.
            self.discard_pending()
            raise
        run_git(["add", "-A", "--", *rel], cwd=self.checkout)
        commit_actor = actor or default_owner()
        run_git(
            ["commit", "-q", "-m", f"{message} ({commit_actor})"], cwd=self.checkout
        )
        self.base_head = self.head()
        self._pending.clear()

    def write_issue(self, issue: Issue, previous_path: Path | None = None) -> Path:
        """Write the issue JSON to disk, removing the old path if it has moved."""
        path = self.issue_path(issue.issue_id, issue.state)
        self.begin_write(*(p for p in (path, previous_path) if p is not None))
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(
            json.dumps(issue.to_record(), indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        if previous_path and previous_path != path and previous_path.exists():
            previous_path.unlink()
        self._state_cache[issue.issue_id] = issue.state
        return path

    def load_defined_labels(self) -> dict[str, str]:
        """Return the defined label set from labels.json on the tracker branch.

        Returns a mapping of label name to description.  If labels.json does
        not exist or cannot be parsed, returns an empty dict.
        """
        path = self.checkout / "labels.json"
        if not path.exists():
            return {}
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
            if isinstance(data, dict):
                return {k: str(v) for k, v in data.items()}
        except (OSError, json.JSONDecodeError, ValueError):
            pass
        return {}

    def run_pending_migrations(self) -> bool:
        """Run any pending tracker migrations sequentially.

        Each migration is guarded by a version check and commits its own
        VERSION bump.  Migrations must be idempotent — safe to re-run.
        Returns True if a migration committed (so HEAD moved), else False.

        NOTE for future format changes (v2+): when designing a new format
        version, consider adding or renaming a required field so that older
        parsers fail loudly on the new data rather than silently
        misinterpreting it.  This turns an unprotected old-client pull into
        a parse error instead of silent corruption.
        """
        fmt, layout = self.read_version()
        if fmt == 0 and layout == 0:
            self._write_version(CURRENT_FORMAT_VERSION, CURRENT_LAYOUT_VERSION)
            return True
        return False

    def next_issue_id(self) -> str:
        """Scan existing issue files and return the next unused T-<n> identifier."""
        highest = 0
        for path in self.issues_root().rglob("T-*.json"):
            if path.name.endswith(EVENT_SUFFIX):
                continue
            highest = max(highest, issue_number(path.stem))
        return f"T-{highest + 1}"

    def issue_paths(self, states: tuple[str, ...] | None = None) -> list[Path]:
        """Return sorted JSON file paths for issues in the given states (default: open)."""
        states = states or ("open",)
        paths: list[Path] = []
        for state in states:
            paths.extend(
                sorted(
                    (
                        path
                        for path in self.state_dir(state).glob("T-*.json")
                        if not path.name.endswith(EVENT_SUFFIX)
                    ),
                    key=lambda path: issue_number(path.stem),
                )
            )
        return paths

    def sort_key(self, issue: Issue) -> tuple[int, int, int]:
        """Return a (priority, state-order, issue-number) tuple for consistent sorting."""
        return (
            issue.priority,
            STATE_ORDER.index(issue.state),
            issue_number(issue.issue_id),
        )

    def list_issues(self, states: tuple[str, ...] | None = None) -> list[Issue]:
        """Load and return issues in the given states, sorted by priority."""
        return sorted(
            [Issue.from_path(path) for path in self.issue_paths(states)],
            key=self.sort_key,
        )

    def load_issue(self, issue_id: str) -> tuple[Issue, Path]:
        """Load an issue by ID and return it together with its file path."""
        path = self.find_issue_path(issue_id)
        return Issue.from_path(path), path

    def create_issue(
        self,
        title: str,
        body: str,
        labels: list[str],
        priority: int,
        state: str = "open",
        deps: list[str] | None = None,
    ) -> Issue:
        """Create a new issue, write it to disk, append a created event, and commit.

        Dependencies are validated (every id must exist) before anything is
        written, so an invalid ``-d`` aborts without persisting a ticket. A
        brand-new issue has no dependents yet, so it cannot close a cycle. The
        issue and its deps land in one commit; the event log still records the
        same ``dependency`` event ``set_dependencies`` would have written.
        """
        priority = validate_priority(priority)
        resolved_deps = self._resolve_deps(deps or [])
        timestamp = now_utc()
        issue = Issue(
            issue_id=self.next_issue_id(),
            title=validate_title(title),
            body=body,
            deps=tuple(sorted(resolved_deps, key=issue_number)),
            labels=tuple(labels),
            owner="",
            priority=priority,
            created_at=timestamp,
            updated_at=timestamp,
            state=state,
        )
        self.write_issue(issue)
        self.events.append(issue, "created", issue.title)
        if resolved_deps:
            self.events.append(issue, "dependency", " ".join(resolved_deps))
        self.commit_if_needed(f"Add issue {issue.issue_id}: {issue.title}")
        return issue

    def _issue_state(self, issue_id: str) -> str | None:
        """Return the state of an issue, or None if no issue file exists.

        Uses the state cache when available. A missing issue is *not* cached,
        so a file created later in the same process is still found.
        """
        if issue_id in self._state_cache:
            return self._state_cache[issue_id]
        for state in STATE_ORDER:
            if self.issue_path(issue_id, state).exists():
                self._state_cache[issue_id] = state
                return state
        return None

    def _build_state_cache(self) -> None:
        """Populate the state cache from all issue files on disk."""
        for state in STATE_ORDER:
            for path in self.state_dir(state).glob("T-*.json"):
                if not path.name.endswith(EVENT_SUFFIX):
                    self._state_cache[path.stem] = state

    def dependency_closed(self, issue_id: str, dep_id: str) -> bool:
        """Return True if *dep_id*, a dependency of *issue_id*, is in a terminal state.

        A dependency with no issue file (after a bad merge or hand edit) is
        treated as unresolved so the referencing issue is never reported as
        ready; one warning per (issue, dep) pair is printed to stderr instead
        of aborting every read command that touches readiness. ``fsck``
        reports the same condition as a dangling dependency.
        """
        state = self._issue_state(dep_id)
        if state is None:
            self._warn_missing_dep(issue_id, dep_id)
            return False
        return state in TERMINAL_STATES

    def _warn_missing_dep(self, issue_id: str, dep_id: str) -> None:
        """Warn once per (issue, dep) pair about a dependency with no issue file."""
        key = (issue_id, dep_id)
        if key in self._warned_missing_deps:
            return
        self._warned_missing_deps.add(key)
        print(
            col.warn(
                f"warning: {issue_id} depends on {dep_id}, which does not exist; "
                "treating as unresolved (run `gittoc fsck`)"
            ),
            file=sys.stderr,
        )

    def ready(self, issue: Issue) -> bool:
        """Return True if the issue is open and all its dependencies are closed."""
        return issue.state == "open" and all(
            self.dependency_closed(issue.issue_id, dep_id) for dep_id in issue.deps
        )

    def ensure_claimable(
        self, issue: Issue, *, owner: str | None = None, take: bool = False
    ) -> None:
        """Raise SystemExit if *issue* cannot transition to the claimed state.

        A claimed issue may be re-claimed by its owner; taking it from someone
        else requires *take* (``claim --take``), so two agents racing for one
        ticket cannot both walk away believing they hold it. An open issue
        must be ready; any other state cannot be claimed. Shared by
        ``update_issue`` and ``claim`` so a batch claim can pre-validate every
        id before mutating any of them.
        """
        if issue.state == "claimed":
            if owner is not None and issue.owner and owner != issue.owner and not take:
                raise SystemExit(
                    f"{issue.issue_id} is already claimed by {issue.owner};"
                    " use --take to transfer ownership"
                )
            return
        if issue.state != "open":
            raise SystemExit(
                f"cannot claim issue from state {issue.state}: {issue.issue_id}"
            )
        if not self.ready(issue):
            raise SystemExit(
                f"cannot claim non-ready issue: {issue.issue_id}"
                f" (has unresolved dependencies)"
            )

    def _would_introduce_cycle(self, issue_id: str, dep_id: str) -> bool:
        """Return True if adding dep_id as a dependency of issue_id would create a cycle."""
        if dep_id == issue_id:
            return True
        seen: set[str] = set()
        stack = [(issue_id, dep_id)]
        while stack:
            parent, current = stack.pop()
            if current == issue_id:
                return True
            if current in seen:
                continue
            seen.add(current)
            if self._issue_state(current) is None:
                # Referenced dep does not exist; treat as a leaf node.
                self._warn_missing_dep(parent, current)
                continue
            current_issue, _ = self.load_issue(current)
            stack.extend((current, child) for child in current_issue.deps)
        return False

    def ready_issues(self) -> list[Issue]:
        """Return all open issues with no unresolved dependencies, sorted by priority."""
        return sorted(
            [issue for issue in self.list_issues(("open",)) if self.ready(issue)],
            key=self.sort_key,
        )

    def resume_issue(self, owner: str) -> tuple[Issue | None, str | None]:
        """Select the best issue to resume: owner's claimed > ready > open."""
        mine = [
            issue for issue in self.list_issues(("claimed",)) if issue.owner == owner
        ]
        if mine:
            return mine[0], "claimed-by-owner"
        ready = self.ready_issues()
        if ready:
            return ready[0], "highest-priority-ready"
        open_issues = self.list_issues(("open",))
        if open_issues:
            return open_issues[0], "highest-priority-open"
        return None, None

    def summary(self) -> dict[str, int]:
        """Return a dict of issue counts per state plus a 'ready' count.

        Counts for non-open states are computed by file glob to avoid parsing
        every issue JSON. Only open issues are fully loaded (for readiness).
        """
        counts = {state: 0 for state in STATE_ORDER}
        for state in STATE_ORDER:
            if state == "open":
                continue
            counts[state] = len(
                list(
                    p
                    for p in self.state_dir(state).glob("T-*.json")
                    if not p.name.endswith(EVENT_SUFFIX)
                )
            )
        self._build_state_cache()
        open_issues = self.list_issues(("open",))
        counts["open"] = len(open_issues)
        ready = sum(1 for issue in open_issues if self.ready(issue))
        counts["ready"] = ready
        return counts

    def update_issue(
        self,
        issue_id: str,
        *,
        title: str | None = None,
        body: str | None = None,
        state: str | None = None,
        owner: str | None = None,
        labels: list[str] | None = None,
        priority: int | None = None,
        event_text: str = "",
        event_actor: str | None = None,
        take: bool = False,
    ) -> Issue:
        """Apply one or more field changes to an issue and commit the result.

        The event kind and commit message follow the *state transition*, not
        the calling command: moving to ``closed``/``rejected``/``claimed``
        records that kind (with the matching commit message) whether it came
        from ``close``/``reject``/``claim`` or from ``update --state``, so the
        audit trail cannot be bypassed. Any other change records ``updated``
        with *event_text*.
        """
        issue, path = self.load_issue(issue_id)
        target_state = issue.state if state is None else state
        if target_state == "claimed":
            self.ensure_claimable(issue, owner=owner, take=take)
        if (
            target_state == "claimed"
            and issue.state == "claimed"
            and owner is not None
            and owner != issue.owner
        ):
            print(
                col.warn(
                    f"warning: {issue.issue_id} was already claimed by {issue.owner};"
                    f" ownership transferred to {owner}"
                ),
                file=sys.stderr,
            )
        resolved_owner: str
        if (
            target_state in ("open", "blocked")
            and issue.state == "claimed"
            and owner is None
        ):
            resolved_owner = ""
            print(
                col.warn(
                    f"note: cleared owner ({issue.owner}) on {issue.issue_id}"
                    f" because state changed to {target_state}"
                ),
                file=sys.stderr,
            )
        else:
            resolved_owner = issue.owner if owner is None else owner
        # A claimed ticket must always have an owner; default it like `claim`
        # so `update --state claimed` without --owner does not orphan the claim.
        if target_state == "claimed" and not resolved_owner:
            resolved_owner = default_owner()
        updated = replace(
            issue,
            title=issue.title if title is None else validate_title(title),
            body=issue.body if body is None else body,
            state=target_state,
            owner=resolved_owner,
            labels=issue.labels if labels is None else tuple(labels),
            priority=(
                issue.priority if priority is None else validate_priority(priority)
            ),
            updated_at=now_utc(),
        )
        event_kind, event_text, message = self._transition_event(
            issue, updated, state, event_text
        )
        self.events.move_file(updated.issue_id, updated.state, path)
        self.write_issue(updated, previous_path=path)
        self.events.append(updated, event_kind, event_text, actor=event_actor)
        self.commit_if_needed(message, actor=event_actor)
        return updated

    @staticmethod
    def _transition_event(
        before: Issue, after: Issue, requested_state: str | None, event_text: str
    ) -> tuple[str, str, str]:
        """Return (event kind, event text, commit message) for an issue change.

        An explicitly requested ``closed``/``rejected``/``claimed`` state gets
        its own kind and commit message even when the issue was already in
        that state, so ``close``, ``reject`` and a same-owner re-``claim``
        keep recording what was asked for. A (re-)claim records the owner as
        the event text, mirroring the ``claim`` command, and an owner change
        on a claimed issue counts as a claim. Everything else is a plain
        ``updated`` event. *event_text* is recorded on every kind except
        ``claimed``.
        """
        issue_id = after.issue_id
        # The caller's text rides along on terminal transitions: a close note,
        # or "fields updated" when update changed fields in the same call.
        if requested_state == "closed":
            return "closed", event_text, f"Close issue {issue_id}"
        if requested_state == "rejected":
            return "rejected", event_text, f"Reject issue {issue_id}"
        if requested_state == "claimed" or (
            after.state == "claimed" and after.owner != before.owner
        ):
            return "claimed", after.owner, f"Claim issue {issue_id} for {after.owner}"
        return "updated", event_text, f"Update issue {issue_id}"

    def _resolve_deps(
        self, dep_ids: list[str], *, issue_id: str | None = None
    ) -> list[str]:
        """Validate dependency ids and return them unique, in input order.

        Every id must be well-formed and name an existing issue. When
        *issue_id* names the issue receiving the dependencies, ids that would
        close a cycle are rejected too (a brand-new issue has no dependents,
        so ``create_issue`` passes none). Both ``create_issue`` and
        ``set_dependencies`` go through here so the rules and the recorded
        event text stay identical.
        """
        resolved: list[str] = []
        for dep_id in dep_ids:
            dep = validate_issue_id(dep_id)
            self.find_issue_path(dep)
            if issue_id is not None and self._would_introduce_cycle(issue_id, dep):
                raise SystemExit(
                    f"dependency would introduce a cycle: {issue_id} -> {dep}"
                )
            if dep not in resolved:
                resolved.append(dep)
        return resolved

    def set_dependencies(self, issue_id: str, dep_ids: list[str]) -> Issue:
        """Add blocking dependencies to an issue, rejecting cycles."""
        issue, path = self.load_issue(issue_id)
        new_deps = self._resolve_deps(dep_ids, issue_id=issue.issue_id)
        deps = set(issue.deps) | set(new_deps)
        updated = replace(
            issue, deps=tuple(sorted(deps, key=issue_number)), updated_at=now_utc()
        )
        self.write_issue(updated, previous_path=path)
        self.events.append(updated, "dependency", " ".join(new_deps))
        self.commit_if_needed(f"Add dependencies to {updated.issue_id}")
        return updated

    def remove_dependencies(self, issue_id: str, dep_ids: list[str]) -> Issue:
        """Remove blocking dependencies from an issue."""
        issue, path = self.load_issue(issue_id)
        to_remove = set()
        for dep_id in dep_ids:
            validate_issue_id(dep_id)
            if dep_id not in issue.deps:
                raise SystemExit(f"{dep_id} is not a dependency of {issue.issue_id}")
            to_remove.add(dep_id)
        new_deps = tuple(d for d in issue.deps if d not in to_remove)
        updated = replace(issue, deps=new_deps, updated_at=now_utc())
        self.write_issue(updated, previous_path=path)
        self.events.append(updated, "dependency", f"removed {' '.join(dep_ids)}")
        self.commit_if_needed(f"Remove dependencies from {updated.issue_id}")
        return updated

    def add_note(self, issue_id: str, text: str, actor: str | None = None) -> Issue:
        """Append a free-text note event to an issue and commit."""
        issue, _ = self.load_issue(issue_id)
        self.events.append(issue, "note", text, actor=actor)
        self.commit_if_needed(f"Add note to {issue.issue_id}", actor=actor)
        return issue
