"""Formatting functions for issue output."""

from __future__ import annotations

import json

from . import colors as col
from .common import ref_short_hash
from .models import Issue


def marker(issue: Issue, tracker) -> str:
    """Return the one-character state marker for an issue."""
    if issue.state in ("closed", "rejected"):
        return "x"
    if tracker.ready(issue):
        return ">"
    if issue.state == "claimed":
        return "!"
    if issue.state == "blocked":
        return "~"
    return "*"


def render_compact(issue: Issue, _tracker) -> str:
    """Render an issue as a single compact line without state marker."""
    return f"{issue.issue_id} p{issue.priority} {issue.state} {issue.title}"


def render_normal(issue: Issue, tracker) -> str:
    """Render an issue as a single annotated line with marker, deps, owner, labels, and note count."""
    owner = f" owner={col.owner(issue.owner)}" if issue.owner else ""
    label_str = f" labels={col.label(','.join(issue.labels))}" if issue.labels else ""
    notes = tracker.events.note_count(issue.issue_id)
    # inconsistent rendering for deps and notes_text, but this improves readability
    notes_text = col.count(f" notes={notes}" if notes else "")
    deps = col.deps(f" deps={str(len(issue.deps))}" if issue.deps else "")
    m = marker(issue, tracker)
    return (
        f"{col.state_marker(m)} {col.issue_id(issue.issue_id)} {col.priority(issue.priority)} "
        f"{col.state(issue.state)} {col.title(issue.title)} {owner}{label_str}{notes_text}{deps}"
    )


def render_verbose(issue: Issue, tracker) -> str:
    """Render an issue as a multi-line block with all fields."""
    deps = ", ".join(issue.deps) if issue.deps else "-"
    labels = ", ".join(issue.labels) if issue.labels else "-"
    owner = issue.owner or "-"
    body = issue.body or "-"
    notes = tracker.events.note_count(issue.issue_id)
    lines = [
        render_normal(issue, tracker),
        f"  deps: {deps}",
        f"  owner: {owner}",
        f"  labels: {labels}",
        f"  notes: {notes}",
        f"  created: {issue.created_at}",
        f"  updated: {issue.updated_at}",
        f"  body: {body}",
    ]
    return "\n".join(lines)


def render_event_line(entry: dict, indent: str, dead_refs: frozenset[str]) -> str:
    """Render a single event/note entry, surfacing its commit ref when present.

    The ref's short hash is shown after the event label, e.g.
    ``[..] closed (04bb189) owner: ...``. A hash that no longer resolves
    (in ``dead_refs``) is marked with a trailing ``?`` rather than printed as
    a dead pointer.
    """
    note_id = entry.get("note_id")
    kind = entry.get("kind", "?")
    ev_label = col.event_label(f"{kind}#{note_id}" if note_id else kind)
    ev_actor = col.actor(entry.get("actor", "?"))
    ev_at = col.timestamp(f"[{entry.get('at', '')}]")
    text = entry.get("text", "")
    short = ref_short_hash(entry.get("ref", ""))
    if short:
        token = f"({short}?)" if short in dead_refs else f"({short})"
        ref_part = f"{col.ref(token)} "
    else:
        ref_part = ""
    return f"{indent}{ev_at} {ev_label} {ref_part}{ev_actor}: {text}"


def render_show_text(data: dict, dead_refs: frozenset[str] = frozenset()) -> str:
    """Render a show-command data dict as human-readable text."""
    lines: list[str] = []
    _id = col.issue_id(str(data.get("id", "?")))
    _prio = col.priority(data.get("priority", 3))
    _state = col.state(str(data.get("state", "?")))
    _title = col.title(str(data.get("title", "")))
    lines.append(f"{_id} {_prio} {_state} {_title}")
    if data.get("body"):
        lines.append(f"  {col.field_name('body:')} {data['body']}")
    deps = data.get("deps", [])
    deps_str = col.deps(", ".join(deps)) if deps else "-"
    lines.append(f"  {col.field_name('deps:')} {deps_str}")
    label_list = data.get("labels", [])
    label_str = col.label(", ".join(label_list)) if label_list else "-"
    lines.append(f"  {col.field_name('labels:')} {label_str}")
    owner_val = data.get("owner")
    lines.append(
        f"  {col.field_name('owner:')} {col.owner(owner_val) if owner_val else '-'}"
    )
    lines.append(f"  {col.field_name('created:')} {data.get('created_at', '-')}")
    lines.append(f"  {col.field_name('updated:')} {data.get('updated_at', '-')}")
    notes_count = data.get("notes_count", data.get("recent_notes_total", 0))
    lines.append(f"  {col.field_name('notes:')} {notes_count}")
    recent_notes = data.get("recent_notes", [])
    if recent_notes:
        lines.append("")
        for note in recent_notes:
            lines.append(render_event_line(note, "  ", dead_refs))
    hint = data.get("recent_notes_hint")
    if hint:
        lines.append(col.warn(f"  ({hint})"))
    history = data.get("history")
    if history:
        lines.append("")
        lines.append(f"  {col.field_name('history:')}")
        for entry in history:
            lines.append(render_event_line(entry, "    ", dead_refs))
    return "\n".join(lines)


def print_issues(issues: list[Issue], tracker, fmt: str) -> None:
    """Print a list of issues in the requested format."""
    if fmt == "json":
        payload = []
        for issue in issues:
            path = tracker.issue_path(issue.issue_id, issue.state)
            payload.append(
                issue.to_display(
                    path.relative_to(tracker.checkout),
                    tracker.events.note_count(issue.issue_id),
                )
            )
        print(json.dumps(payload, indent=2, sort_keys=True))
        return

    renderer = {
        "compact": render_compact,
        "normal": render_normal,
        "verbose": render_verbose,
    }[fmt]
    for index, issue in enumerate(issues):
        if index and fmt == "verbose":
            print()
        print(renderer(issue, tracker))
