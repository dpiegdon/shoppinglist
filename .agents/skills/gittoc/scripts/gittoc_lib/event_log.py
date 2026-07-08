"""Per-issue JSONL event log: append, read, filter, and relocate."""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import TYPE_CHECKING

from . import colors as col
from .common import (
    EVENT_SUFFIX,
    STATE_ORDER,
    current_ref,
    default_owner,
    now_utc,
    validate_issue_id,
)
from .models import Issue

if TYPE_CHECKING:
    from .tracker import Tracker


class EventLog:
    """Owns the on-disk event log for each issue and caches parsed entries."""

    def __init__(self, tracker: "Tracker") -> None:
        """Bind this log to its tracker; share the tracker's worktree and repo paths."""
        self.tracker = tracker
        self._cache: dict[str, list[dict]] = {}

    def path(self, issue_id: str, state: str) -> Path:
        """Return the expected event log path for an issue in the given state."""
        return (
            self.tracker.state_dir(state)
            / f"{validate_issue_id(issue_id)}{EVENT_SUFFIX}"
        )

    def find(self, issue_id: str) -> Path | None:
        """Return the event log path for an issue, or None if no log exists yet."""
        issue_id = validate_issue_id(issue_id)
        for state in STATE_ORDER:
            path = self.path(issue_id, state)
            if path.exists():
                return path
        return None

    def move_file(
        self, issue_id: str, new_state: str, previous_path: Path | None
    ) -> None:
        """Relocate the event log alongside the issue when its state directory changes."""
        if not previous_path:
            return
        previous_event = previous_path.with_name(previous_path.stem + EVENT_SUFFIX)
        if not previous_event.exists():
            return
        target = self.path(issue_id, new_state)
        target.parent.mkdir(parents=True, exist_ok=True)
        if previous_event != target:
            previous_event.rename(target)

    def append(
        self, issue: Issue, kind: str, text: str = "", actor: str | None = None
    ) -> None:
        """Append a timestamped event entry to the issue's event log."""
        path = self.path(issue.issue_id, issue.state)
        path.parent.mkdir(parents=True, exist_ok=True)
        entry = {
            "actor": actor or default_owner(),
            "at": now_utc(),
            "kind": kind,
            "ref": current_ref(self.tracker.repo),
            "text": text,
        }
        with path.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(entry, sort_keys=True) + "\n")
        self._cache.pop(issue.issue_id, None)

    def entries(self, issue_id: str) -> list[dict]:
        """Return all event log entries for an issue, in chronological order.

        Note events are augmented with a computed ``note_id`` (1-based
        sequential index) so that individual notes are human-addressable.
        Results are cached for the lifetime of this EventLog instance.
        """
        if issue_id in self._cache:
            return self._cache[issue_id]
        path = self.find(issue_id)
        if not path or not path.exists():
            self._cache[issue_id] = []
            return []
        entries: list[dict] = []
        note_seq = 0
        with path.open("r", encoding="utf-8") as handle:
            for lineno, line in enumerate(handle, 1):
                line = line.strip()
                if not line:
                    continue
                try:
                    entry = json.loads(line)
                except json.JSONDecodeError:
                    print(
                        col.warn(
                            f"warning: skipping malformed event at {path}:{lineno}"
                        ),
                        file=sys.stderr,
                    )
                    continue
                if entry.get("kind") == "note":
                    note_seq += 1
                    entry["note_id"] = note_seq
                entries.append(entry)
        self._cache[issue_id] = entries
        return entries

    def filtered(
        self,
        issue_id: str,
        *,
        kinds: set[str] | None = None,
        limit: int | None = None,
    ) -> list[dict]:
        """Return event entries filtered by kind and/or capped at limit (most recent)."""
        entries = self.entries(issue_id)
        if kinds:
            entries = [entry for entry in entries if entry.get("kind") in kinds]
        if limit is not None:
            entries = entries[-limit:]
        return entries

    def note_count(self, issue_id: str) -> int:
        """Return the number of note events recorded for an issue."""
        return sum(1 for entry in self.entries(issue_id) if entry["kind"] == "note")
