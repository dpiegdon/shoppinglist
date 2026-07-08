"""Integrity report models, rendering, and fsck scanner."""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import TYPE_CHECKING

from .common import EVENT_SUFFIX, ISSUE_RE, STATE_ORDER, issue_number

if TYPE_CHECKING:
    from .tracker import Tracker


@dataclass(frozen=True)
class IntegrityFinding:
    """Single integrity finding produced by ``gittoc fsck``."""

    severity: str
    message: str
    path: str | None = None
    line: int | None = None
    issue_ids: tuple[str, ...] = ()

    def to_record(self) -> dict[str, object]:
        """Convert the finding to a JSON-serializable dict."""
        record: dict[str, object] = {
            "severity": self.severity,
            "message": self.message,
        }
        if self.path is not None:
            record["path"] = self.path
        if self.line is not None:
            record["line"] = self.line
        if self.issue_ids:
            record["issue_ids"] = list(self.issue_ids)
        return record


@dataclass(frozen=True)
class IntegrityReport:
    """Aggregate result of a tracker integrity scan."""

    findings: tuple[IntegrityFinding, ...]
    checked_paths: tuple[str, ...]
    scanned_issues: int
    scanned_event_logs: int

    @property
    def errors(self) -> tuple[IntegrityFinding, ...]:
        """Return the error-level findings."""
        return tuple(
            finding for finding in self.findings if finding.severity == "error"
        )

    @property
    def warnings(self) -> tuple[IntegrityFinding, ...]:
        """Return the warning-level findings."""
        return tuple(
            finding for finding in self.findings if finding.severity == "warning"
        )

    @property
    def ok(self) -> bool:
        """Return True when the scan found no errors or warnings."""
        return not self.findings

    def to_record(self) -> dict[str, object]:
        """Convert the report to a JSON-serializable dict."""
        return {
            "checked_paths": list(self.checked_paths),
            "errors": [finding.to_record() for finding in self.errors],
            "findings": [finding.to_record() for finding in self.findings],
            "ok": self.ok,
            "scanned_event_logs": self.scanned_event_logs,
            "scanned_issues": self.scanned_issues,
            "warnings": [finding.to_record() for finding in self.warnings],
        }


def issue_id_from_path(path: Path) -> str | None:
    """Return the ticket ID encoded in an issue/event path, or None."""
    name = path.name
    if name.endswith(EVENT_SUFFIX):
        candidate = name[: -len(EVENT_SUFFIX)]
    elif name.endswith(".json"):
        candidate = path.stem
    else:
        return None
    return candidate if ISSUE_RE.match(candidate) else None


def render_integrity_report(report: IntegrityReport) -> str:
    """Render an integrity report in a compact human-readable text form."""
    if report.ok:
        return (
            "fsck ok: "
            f"issues={report.scanned_issues} event_logs={report.scanned_event_logs}"
        )
    lines: list[str] = []
    for finding in report.findings:
        location = finding.path or "tracker"
        if finding.line is not None:
            location = f"{location}:{finding.line}"
        lines.append(f"{finding.severity}: {location}: {finding.message}")
    parts = []
    if report.errors:
        parts.append(f"{len(report.errors)} error(s)")
    if report.warnings:
        parts.append(f"{len(report.warnings)} warning(s)")
    lines.append(f"fsck failed: {', '.join(parts)}")
    return "\n".join(lines)


# ---------------------------------------------------------------------------
# fsck scanner
# ---------------------------------------------------------------------------


def _relpath(checkout: Path, path: Path) -> str:
    return str(path.relative_to(checkout))


def _make_finding(
    checkout: Path,
    message: str,
    *,
    path: Path | None = None,
    line: int | None = None,
    issue_ids: tuple[str, ...] = (),
    severity: str = "error",
) -> IntegrityFinding:
    return IntegrityFinding(
        severity=severity,
        message=message,
        path=_relpath(checkout, path) if path is not None else None,
        line=line,
        issue_ids=issue_ids,
    )


def _validate_event_file(checkout: Path, path: Path) -> list[IntegrityFinding]:
    findings: list[IntegrityFinding] = []
    issue_ids: tuple[str, ...] = ()
    event_id = issue_id_from_path(path)
    if event_id is not None:
        issue_ids = (event_id,)
    try:
        with path.open("r", encoding="utf-8") as handle:
            for lineno, line in enumerate(handle, 1):
                line = line.strip()
                if not line:
                    continue
                try:
                    entry = json.loads(line)
                except json.JSONDecodeError as exc:
                    findings.append(
                        _make_finding(
                            checkout,
                            f"malformed JSON: {exc}",
                            path=path,
                            line=lineno,
                            issue_ids=issue_ids,
                        )
                    )
                    continue
                if not isinstance(entry, dict):
                    findings.append(
                        _make_finding(
                            checkout,
                            "event entry must be a JSON object",
                            path=path,
                            line=lineno,
                            issue_ids=issue_ids,
                        )
                    )
                    continue
                for field in ("actor", "at", "kind", "text"):
                    if field not in entry:
                        findings.append(
                            _make_finding(
                                checkout,
                                f"missing event field '{field}'",
                                path=path,
                                line=lineno,
                                issue_ids=issue_ids,
                            )
                        )
                    elif not isinstance(entry[field], str):
                        findings.append(
                            _make_finding(
                                checkout,
                                f"event field '{field}' must be a string",
                                path=path,
                                line=lineno,
                                issue_ids=issue_ids,
                            )
                        )
    except OSError as exc:
        findings.append(_make_finding(checkout, f"cannot read file: {exc}", path=path))
    return findings


def _canonical_cycle(cycle: list[str]) -> tuple[str, ...]:
    start = min(range(len(cycle)), key=lambda index: issue_number(cycle[index]))
    return tuple(cycle[start:] + cycle[:start])


def fsck(tracker: "Tracker", paths: list[Path] | None = None) -> IntegrityReport:
    """Run a read-only integrity scan across tracker issues and event logs.

    When *paths* is ``None`` the full tracker is scanned.  When a list is
    given, only findings related to those paths (or the issue IDs they
    encode) are returned.  An empty list means nothing was changed, so the
    scan is skipped and an ok report is returned immediately.
    """
    from .models import Issue

    checkout = tracker.checkout
    if paths is not None and not paths:
        return IntegrityReport(
            findings=(), checked_paths=(), scanned_issues=0, scanned_event_logs=0
        )
    findings: list[IntegrityFinding] = []
    scope_paths = (
        {
            _relpath(checkout, path.resolve())
            for path in paths
            if path.exists() and path.is_relative_to(checkout)
        }
        if paths is not None
        else None
    )
    checked_paths = tuple(sorted(scope_paths or ()))
    scope_issue_ids = (
        {
            issue_id
            for rel in scope_paths or set()
            for issue_id in [issue_id_from_path(Path(rel))]
            if issue_id is not None
        }
        if scope_paths is not None
        else set()
    )

    issue_files: list[Path] = []
    event_files: list[Path] = []
    issue_paths_by_file_id: dict[str, list[Path]] = {}
    event_paths_by_file_id: dict[str, list[Path]] = {}

    for state in STATE_ORDER:
        state_dir = tracker.state_dir(state)
        if not state_dir.exists():
            continue
        for entry in sorted(state_dir.iterdir(), key=lambda value: value.name):
            if entry.is_dir():
                findings.append(
                    _make_finding(
                        checkout, "unexpected directory in tracker state", path=entry
                    )
                )
                continue
            if entry.name.endswith(EVENT_SUFFIX):
                event_id = issue_id_from_path(entry)
                if event_id is None:
                    findings.append(
                        _make_finding(checkout, "unexpected event filename", path=entry)
                    )
                    continue
                event_files.append(entry)
                event_paths_by_file_id.setdefault(event_id, []).append(entry)
                continue
            if entry.suffix == ".json":
                issue_id = issue_id_from_path(entry)
                if issue_id is None:
                    findings.append(
                        _make_finding(checkout, "unexpected issue filename", path=entry)
                    )
                    continue
                issue_files.append(entry)
                issue_paths_by_file_id.setdefault(issue_id, []).append(entry)
                continue
            findings.append(
                _make_finding(checkout, "unexpected file in tracker state", path=entry)
            )

    duplicate_file_ids = {
        issue_id
        for issue_id, paths_for_issue in issue_paths_by_file_id.items()
        if len(paths_for_issue) > 1
    }
    for issue_id, paths_for_issue in issue_paths_by_file_id.items():
        if len(paths_for_issue) <= 1:
            continue
        first = _relpath(checkout, paths_for_issue[0])
        for path in paths_for_issue[1:]:
            findings.append(
                _make_finding(
                    checkout,
                    f"duplicate issue file for {issue_id}; also present at {first}",
                    path=path,
                    issue_ids=(issue_id,),
                )
            )

    for issue_id, paths_for_issue in event_paths_by_file_id.items():
        if len(paths_for_issue) <= 1:
            continue
        first = _relpath(checkout, paths_for_issue[0])
        for path in paths_for_issue[1:]:
            findings.append(
                _make_finding(
                    checkout,
                    f"duplicate event log for {issue_id}; also present at {first}",
                    path=path,
                    issue_ids=(issue_id,),
                )
            )

    issues_by_file_id: dict[str, Issue] = {}
    issue_path_by_file_id: dict[str, Path] = {}
    issue_paths_by_logical_id: dict[str, list[Path]] = {}
    invalid_file_ids: set[str] = set()

    for path in issue_files:
        file_id = path.stem
        issue, errors = Issue.validate_path(path)
        if errors:
            invalid_file_ids.add(file_id)
            for error in errors:
                findings.append(
                    _make_finding(checkout, error, path=path, issue_ids=(file_id,))
                )
            continue
        assert issue is not None  # validate_path returns an Issue when errors is empty
        if file_id not in duplicate_file_ids:
            issues_by_file_id[file_id] = issue
            issue_path_by_file_id[file_id] = path
        if issue.issue_id != file_id:
            findings.append(
                _make_finding(
                    checkout,
                    f"filename/id mismatch: file name encodes {file_id}, record id is {issue.issue_id}",
                    path=path,
                    issue_ids=(file_id, issue.issue_id),
                )
            )
        issue_paths_by_logical_id.setdefault(issue.issue_id, []).append(path)

    for logical_id, paths_for_issue in issue_paths_by_logical_id.items():
        if len(paths_for_issue) <= 1:
            continue
        first = _relpath(checkout, paths_for_issue[0])
        for path in paths_for_issue[1:]:
            findings.append(
                _make_finding(
                    checkout,
                    f"duplicate issue record id {logical_id}; also present at {first}",
                    path=path,
                    issue_ids=(logical_id,),
                )
            )

    for path in event_files:
        event_id = issue_id_from_path(path)
        if event_id is None:
            continue
        if event_id not in issue_paths_by_file_id:
            findings.append(
                _make_finding(
                    checkout,
                    f"orphaned event log for missing issue {event_id}",
                    path=path,
                    issue_ids=(event_id,),
                )
            )
        elif event_id in duplicate_file_ids:
            findings.append(
                _make_finding(
                    checkout,
                    f"event log for {event_id} is ambiguous because the issue file exists in multiple states",
                    path=path,
                    issue_ids=(event_id,),
                )
            )
        else:
            issue_path = issue_paths_by_file_id[event_id][0]
            if path.parent != issue_path.parent:
                findings.append(
                    _make_finding(
                        checkout,
                        f"event log state mismatch for {event_id}; issue file is in {issue_path.parent.name}",
                        path=path,
                        issue_ids=(event_id,),
                    )
                )
        findings.extend(_validate_event_file(checkout, path))

    resolvable_ids = set(issues_by_file_id) - invalid_file_ids
    for issue_id, issue in issues_by_file_id.items():
        issue_path = issue_path_by_file_id[issue_id]
        for dep_id in issue.deps:
            if dep_id not in resolvable_ids:
                findings.append(
                    _make_finding(
                        checkout,
                        f"dangling dependency on {dep_id}",
                        path=issue_path,
                        issue_ids=(issue_id, dep_id),
                    )
                )

    seen_cycles: set[tuple[str, ...]] = set()
    visited: set[str] = set()
    active: set[str] = set()

    # Iterative DFS: each work-stack frame holds the node being explored
    # plus an iterator over its remaining deps. This avoids Python's
    # recursion limit on deep dependency chains.
    for start_id in sorted(resolvable_ids, key=issue_number):
        if start_id in visited:
            continue
        path_stack: list[str] = [start_id]
        active.add(start_id)
        work_stack = [(start_id, iter(issues_by_file_id[start_id].deps))]
        while work_stack:
            current_id, dep_iter = work_stack[-1]
            next_dep = next(dep_iter, None)
            if next_dep is None:
                work_stack.pop()
                path_stack.pop()
                active.discard(current_id)
                visited.add(current_id)
                continue
            if next_dep not in resolvable_ids:
                continue
            if next_dep in active:
                cycle = path_stack[path_stack.index(next_dep) :]
                key = _canonical_cycle(cycle)
                if key not in seen_cycles:
                    seen_cycles.add(key)
                    cycle_path = " -> ".join(list(key) + [key[0]])
                    findings.append(
                        _make_finding(
                            checkout,
                            f"dependency cycle detected: {cycle_path}",
                            path=issue_path_by_file_id[key[0]],
                            issue_ids=key,
                        )
                    )
                continue
            if next_dep in visited:
                continue
            active.add(next_dep)
            path_stack.append(next_dep)
            work_stack.append((next_dep, iter(issues_by_file_id[next_dep].deps)))

    if scope_paths is not None:
        findings = [
            finding
            for finding in findings
            if finding.path in scope_paths
            or scope_issue_ids.intersection(finding.issue_ids)
        ]

    findings.sort(
        key=lambda finding: (
            finding.severity,
            finding.path or "",
            finding.line or 0,
            finding.message,
        )
    )
    return IntegrityReport(
        findings=tuple(findings),
        checked_paths=checked_paths,
        scanned_issues=len(issue_files),
        scanned_event_logs=len(event_files),
    )
