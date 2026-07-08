"""Issue data model for gittoc."""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

from .common import (
    DEFAULT_PRIORITY,
    STATE_SET,
    issue_number,
    validate_issue_id,
    validate_priority,
)


@dataclass(frozen=True)
class Issue:
    """Immutable representation of a single gittoc ticket."""

    issue_id: str
    title: str
    body: str
    deps: tuple[str, ...]
    labels: tuple[str, ...]
    owner: str
    priority: int
    created_at: str
    updated_at: str
    state: str

    @classmethod
    def validate_path(cls, path: Path) -> tuple["Issue" | None, list[str]]:
        """Validate and load an issue file without aborting on the first error."""
        try:
            with path.open("r", encoding="utf-8") as handle:
                raw = json.load(handle)
        except json.JSONDecodeError as exc:
            return None, [f"malformed JSON: {exc}"]
        except OSError as exc:
            return None, [f"cannot read file: {exc}"]
        return cls.validate_record(raw, path)

    @classmethod
    def validate_record(
        cls, raw: object, path: Path
    ) -> tuple["Issue" | None, list[str]]:
        """Validate a decoded issue record and return an Issue when valid."""
        if not isinstance(raw, dict):
            return None, ["expected JSON object"]
        missing = [field for field in ("id", "title", "created_at") if field not in raw]
        if missing:
            return None, [f"missing required field(s) {', '.join(missing)}"]
        errors: list[str] = []
        state = path.parent.name if path.parent.name in STATE_SET else "open"

        try:
            issue_id = validate_issue_id(raw["id"])
        except (TypeError, SystemExit):
            errors.append(f"invalid issue id: {raw.get('id')!r}")
            issue_id = ""

        title = raw.get("title")
        if not isinstance(title, str):
            errors.append("field 'title' must be a string")
            title = ""

        body = raw.get("body", "")
        if not isinstance(body, str):
            errors.append("field 'body' must be a string")
            body = ""

        deps_raw = raw.get("deps", [])
        deps: list[str] = []
        if not isinstance(deps_raw, list):
            errors.append("field 'deps' must be a list")
        else:
            for dep in deps_raw:
                if not isinstance(dep, str):
                    errors.append("dependency entries must be strings")
                    continue
                try:
                    deps.append(validate_issue_id(dep))
                except SystemExit:
                    errors.append(f"invalid dependency id: {dep!r}")

        labels_raw = raw.get("labels", [])
        labels: list[str] = []
        if not isinstance(labels_raw, list):
            errors.append("field 'labels' must be a list")
        else:
            for label in labels_raw:
                if not isinstance(label, str) or not label:
                    errors.append("labels must be non-empty strings")
                    continue
                labels.append(label)

        owner = raw.get("owner", "")
        if not isinstance(owner, str):
            errors.append("field 'owner' must be a string")
            owner = ""

        created_at = raw.get("created_at")
        if not isinstance(created_at, str):
            errors.append("field 'created_at' must be a string")
            created_at = ""

        updated_at = raw.get("updated_at", created_at)
        if not isinstance(updated_at, str):
            errors.append("field 'updated_at' must be a string")
            updated_at = created_at

        priority_raw = raw.get("priority", DEFAULT_PRIORITY)
        if isinstance(priority_raw, bool) or not isinstance(priority_raw, int):
            errors.append(f"invalid priority: {priority_raw!r}")
            priority = DEFAULT_PRIORITY
        else:
            try:
                priority = validate_priority(priority_raw)
            except SystemExit:
                errors.append(f"invalid priority: {priority_raw!r}")
                priority = DEFAULT_PRIORITY

        if errors:
            return None, errors
        return (
            cls(
                issue_id=issue_id,
                title=title,
                body=body,
                deps=tuple(sorted(set(deps), key=issue_number)),
                labels=tuple(labels),
                owner=owner,
                priority=priority,
                created_at=created_at,
                updated_at=updated_at,
                state=state,
            ),
            [],
        )

    @classmethod
    def from_path(cls, path: Path) -> "Issue":
        """Load an Issue from its JSON file, deriving state from the parent directory."""
        issue, errors = cls.validate_path(path)
        if errors:
            raise SystemExit(f"{errors[0]} in {path}")
        assert issue is not None  # no errors means validate_path returned an Issue
        return issue

    def to_record(self) -> dict:
        """Serialize the issue to a dict suitable for writing as JSON.

        Empty optional fields (body, deps, labels, owner) are omitted
        to keep ticket files concise and readable.
        """
        record: dict = {
            "created_at": self.created_at,
            "id": self.issue_id,
            "priority": self.priority,
            "title": self.title,
            "updated_at": self.updated_at,
        }
        if self.body:
            record["body"] = self.body
        if self.deps:
            record["deps"] = list(self.deps)
        if self.labels:
            record["labels"] = list(self.labels)
        if self.owner:
            record["owner"] = self.owner
        return record

    def to_display(self, path: Path, notes_count: int) -> dict:
        """Return a display dict augmented with path, state, and note count."""
        data = self.to_record()
        data["notes_count"] = notes_count
        data["path"] = str(path)
        data["state"] = self.state
        return data
