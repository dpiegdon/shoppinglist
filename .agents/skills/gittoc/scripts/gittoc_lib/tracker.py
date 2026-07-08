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
)
from .event_log import EventLog
from .models import Issue
from .remote_sync import RemoteSync


class StaleTrackerError(Exception):
    """Raised when the tracker has been modified since it was opened."""


class Tracker:
    """Manages the gittoc issue store on the dedicated tracker branch."""

    def __init__(self, repo: Path, checkout: Path):
        """Initialise with repo root and tracker worktree paths."""
        self.repo = repo
        self.checkout = checkout
        self.base_head = self.head()
        self._state_cache: dict[str, str] = {}
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
        version_path = checkout / VERSION_FILE
        version_data = {
            "format_version": CURRENT_FORMAT_VERSION,
            "layout_version": CURRENT_LAYOUT_VERSION,
            "migrated_at": now_utc(),
            "migrated_by": default_owner(),
        }
        with version_path.open("w", encoding="utf-8") as handle:
            json.dump(version_data, handle, indent=2, sort_keys=True)
            handle.write("\n")
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
        path = self.checkout / VERSION_FILE
        data = {
            "format_version": format_version,
            "layout_version": layout_version,
            "migrated_at": now_utc(),
            "migrated_by": default_owner(),
        }
        with path.open("w", encoding="utf-8") as handle:
            json.dump(data, handle, indent=2, sort_keys=True)
            handle.write("\n")
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
        """Search all state directories and return the path where the issue lives."""
        issue_id = validate_issue_id(issue_id)
        for state in STATE_ORDER:
            path = self.issue_path(issue_id, state)
            if path.exists():
                return path
        raise SystemExit(f"issue not found: {issue_id}")

    def commit_if_needed(self, message: str, actor: str | None = None) -> None:
        """Stage and commit any pending changes to the issues tree, if any exist."""
        proc = run_git(["status", "--porcelain", "--", "issues"], cwd=self.checkout)
        if not proc.stdout.strip():
            return
        self.ensure_not_stale()
        run_git(["add", "issues"], cwd=self.checkout)
        commit_actor = actor or default_owner()
        run_git(
            ["commit", "-q", "-m", f"{message} ({commit_actor})"], cwd=self.checkout
        )
        self.base_head = self.head()

    def write_issue(self, issue: Issue, previous_path: Path | None = None) -> Path:
        """Write the issue JSON to disk, removing the old path if it has moved."""
        path = self.issue_path(issue.issue_id, issue.state)
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
    ) -> Issue:
        """Create a new issue, write it to disk, append a created event, and commit."""
        timestamp = now_utc()
        issue = Issue(
            issue_id=self.next_issue_id(),
            title=title,
            body=body,
            deps=(),
            labels=tuple(labels),
            owner="",
            priority=validate_priority(priority),
            created_at=timestamp,
            updated_at=timestamp,
            state=state,
        )
        self.write_issue(issue)
        self.events.append(issue, "created", issue.title)
        self.commit_if_needed(f"Add issue {issue.issue_id}: {issue.title}")
        return issue

    def _issue_state(self, issue_id: str) -> str:
        """Return the state of an issue, using cache when available."""
        if issue_id in self._state_cache:
            return self._state_cache[issue_id]
        path = self.find_issue_path(issue_id)
        state = path.parent.name
        self._state_cache[issue_id] = state
        return state

    def _build_state_cache(self) -> None:
        """Populate the state cache from all issue files on disk."""
        for state in STATE_ORDER:
            for path in self.state_dir(state).glob("T-*.json"):
                if not path.name.endswith(EVENT_SUFFIX):
                    self._state_cache[path.stem] = state

    def dependency_closed(self, issue_id: str) -> bool:
        """Return True if the named dependency issue is in a terminal state."""
        return self._issue_state(issue_id) in TERMINAL_STATES

    def ready(self, issue: Issue) -> bool:
        """Return True if the issue is open and all its dependencies are closed."""
        return issue.state == "open" and all(
            self.dependency_closed(dep_id) for dep_id in issue.deps
        )

    def ensure_claimable(self, issue: Issue) -> None:
        """Raise SystemExit if *issue* cannot transition to the claimed state.

        Re-claiming an already-claimed issue (ownership transfer) is allowed;
        an open issue must be ready; any other state cannot be claimed. Shared
        by ``update_issue`` and ``claim`` so a batch claim can pre-validate
        every id before mutating any of them.
        """
        if issue.state == "claimed":
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
        stack = [dep_id]
        while stack:
            current = stack.pop()
            if current == issue_id:
                return True
            if current in seen:
                continue
            seen.add(current)
            try:
                current_issue, _ = self.load_issue(current)
            except SystemExit:
                # Referenced dep does not exist; treat as a leaf node.
                print(
                    col.warn(
                        f"warning: dependency {current} not found, "
                        "skipping during cycle check"
                    ),
                    file=sys.stderr,
                )
                continue
            stack.extend(current_issue.deps)
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
        message: str | None = None,
        event_kind: str = "updated",
        event_text: str = "",
        event_actor: str | None = None,
    ) -> Issue:
        """Apply one or more field changes to an issue and commit the result."""
        issue, path = self.load_issue(issue_id)
        target_state = issue.state if state is None else state
        if target_state == "claimed":
            self.ensure_claimable(issue)
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
            title=issue.title if title is None else title,
            body=issue.body if body is None else body,
            state=target_state,
            owner=resolved_owner,
            labels=issue.labels if labels is None else tuple(labels),
            priority=(
                issue.priority if priority is None else validate_priority(priority)
            ),
            updated_at=now_utc(),
        )
        self.events.move_file(updated.issue_id, updated.state, path)
        self.write_issue(updated, previous_path=path)
        self.events.append(updated, event_kind, event_text, actor=event_actor)
        self.commit_if_needed(
            message or f"Update issue {updated.issue_id}", actor=event_actor
        )
        return updated

    def reject_issue(self, issue_id: str, *, actor: str | None = None) -> Issue:
        """Move an issue to the rejected state (won't-do / abandoned)."""
        return self.update_issue(
            issue_id,
            state="rejected",
            message=f"Reject issue {issue_id}",
            event_kind="rejected",
            event_actor=actor,
        )

    def set_dependencies(self, issue_id: str, dep_ids: list[str]) -> Issue:
        """Add blocking dependencies to an issue, rejecting cycles."""
        issue, path = self.load_issue(issue_id)
        deps = set(issue.deps)
        for dep_id in dep_ids:
            dep = validate_issue_id(dep_id)
            self.find_issue_path(dep)
            if self._would_introduce_cycle(issue.issue_id, dep):
                raise SystemExit(
                    f"dependency would introduce a cycle: {issue.issue_id} -> {dep}"
                )
            deps.add(dep)
        updated = replace(
            issue, deps=tuple(sorted(deps, key=issue_number)), updated_at=now_utc()
        )
        self.write_issue(updated, previous_path=path)
        self.events.append(updated, "dependency", " ".join(dep_ids))
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
