"""Command handler implementations for gittoc CLI."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path
from typing import cast

from . import colors as col
from .common import (
    STATE_ORDER,
    STATE_SET,
    TRACKER_BRANCH,
    default_owner,
    issue_number,
    missing_objects,
    parse_state,
    ref_short_hash,
    run_git,
    validate_issue_id,
)
from .remote_sync import RemotePushPullError
from .render import print_issues, render_show_text
from .tracker import Tracker

SHOW_NOTES_LIMIT = 3


def _auto_pull(tracker: Tracker) -> None:
    """Pull before a mutation if autopush is enabled."""
    if tracker.remote.autopush_enabled():
        tracker.remote.auto_pull()


def _auto_push(tracker: Tracker) -> None:
    """Push after a mutation if autopush is enabled."""
    if tracker.remote.autopush_enabled():
        tracker.remote.auto_push()


def parse_labels(values: list[str] | None) -> list[str]:
    """Parse repeatable/comma-separated label arguments into a deduplicated list."""
    if not values:
        return []
    labels: list[str] = []
    seen: set[str] = set()
    for value in values:
        for label in value.split(","):
            label = label.strip()
            if not label or label in seen:
                continue
            seen.add(label)
            labels.append(label)
    return labels


def parse_states(values: list[str] | None) -> tuple[str, ...]:
    """Parse repeatable/comma-separated state arguments into a validated tuple."""
    if not values:
        return ()
    states: list[str] = []
    seen: set[str] = set()
    for value in values:
        for state in value.split(","):
            state = state.strip()
            if not state or state in seen:
                continue
            if state not in STATE_SET:
                raise SystemExit(
                    f"invalid state: {state} (valid: {', '.join(STATE_ORDER)})"
                )
            seen.add(state)
            states.append(state)
    return tuple(states)


def parse_issue_ids(values: list[str] | None) -> list[str]:
    """Parse repeatable/comma-separated issue ID arguments into a validated list."""
    if not values:
        return []
    ids: list[str] = []
    seen: set[str] = set()
    for value in values:
        for issue_id in value.split(","):
            issue_id = issue_id.strip()
            if not issue_id or issue_id in seen:
                continue
            validate_issue_id(issue_id)
            seen.add(issue_id)
            ids.append(issue_id)
    return ids


def resolve_text_input(
    inline: str | None,
    file_arg: str | None,
    *,
    what: str,
    allow_empty: bool,
) -> str | None:
    """Resolve text from an inline argument or a -F file/stdin source.

    The caller's shell evaluates inline arguments (backticks, ``$()``, ``!``),
    so the ``-F`` path lets callers pass backtick-heavy prose via a file or a
    quoted heredoc on stdin without fighting quoting. A single trailing newline
    is stripped so heredocs and editor-saved files do not leave a dangling blank
    line. Returns ``None`` only when neither source is provided.
    """
    if inline is not None and file_arg is not None:
        raise SystemExit(f"provide {what} inline or with -F, not both")
    if file_arg is None:
        return inline
    if file_arg == "-":
        text = sys.stdin.read()
    else:
        try:
            text = Path(file_arg).read_text(encoding="utf-8")
        except OSError as exc:
            raise SystemExit(f"cannot read {what} from {file_arg}: {exc}") from exc
    if text.endswith("\n"):
        text = text[:-1]
    if not allow_empty and not text:
        raise SystemExit(f"{what} is empty")
    return text


def cmd_init(_args: argparse.Namespace) -> int:
    """Initialize the tracker worktree and auto-configure the remote if possible."""
    tracker = Tracker.open()
    if not tracker.remote.configured():
        inferred = tracker.remote.effective()
        if inferred:
            tracker.remote.configure(inferred)
    print(f"initialized tracker branch {TRACKER_BRANCH} at {tracker.checkout}")
    return 0


def cmd_remote(args: argparse.Namespace) -> int:
    """Show or configure the remote wiring for the tracker branch."""
    tracker = Tracker.open()
    if args.set:
        status = tracker.remote.configure(args.set)
    elif args.auto:
        remote = tracker.remote.effective()
        if not remote:
            raise SystemExit(
                "no remote could be inferred (use: gittoc remote --set <name>)"
            )
        status = tracker.remote.configure(remote)
    else:
        status = tracker.remote.status()
    if args.format == "json":
        print(json.dumps(status, indent=2, sort_keys=True))
    else:
        remotes = (
            ", ".join(cast("list[str]", status["remotes"]))
            if status["remotes"]
            else "-"
        )
        print(
            f"remotes={remotes} inferred={status['inferred_remote'] or '-'} "
            f"configured={status['configured_remote'] or '-'} "
            f"effective={status['effective_remote'] or '-'} "
            f"branch_remote={status['branch_config_remote'] or '-'} "
            f"branch_merge={status['branch_config_merge'] or '-'} "
            f"remote_branch_exists={'yes' if status['remote_branch_exists'] else 'no'}"
        )
    return 0


def cmd_pull(args: argparse.Namespace) -> int:
    """Fetch and merge the tracker branch from a remote."""
    tracker = Tracker.open()
    remote = args.remote or tracker.remote.effective()
    if not remote:
        print(
            col.error(
                "error: no remote specified and none configured (run: gittoc remote --set <name>)"
            ),
            file=sys.stderr,
        )
        return 1
    try:
        status = tracker.remote.pull(remote)
    except RemotePushPullError as exc:
        print(col.error(f"error: {exc}"), file=sys.stderr)
        return 1
    from .integrity import IntegrityReport, render_integrity_report

    report = status.get("fsck")
    payload = dict(status)
    if isinstance(report, IntegrityReport):
        payload["fsck"] = report.to_record()
    if args.format == "json":
        print(json.dumps(payload, indent=2, sort_keys=True))
    elif status.get("merge_kind") == "unchanged":
        print(
            col.ok(
                f"{TRACKER_BRANCH} from {status['remote']} already up to date "
                f"at {status['head']}"
            )
        )
    else:
        print(f"pulled {TRACKER_BRANCH} from {status['remote']} to {status['head']}")
        if isinstance(report, IntegrityReport) and not report.ok:
            # Findings go to stderr so the pull status line stays parseable on
            # stdout; cmd_fsck uses stdout because the report IS the output.
            print(render_integrity_report(report), file=sys.stderr)
    if isinstance(report, IntegrityReport) and not report.ok:
        return 1
    return 0


def cmd_fsck(args: argparse.Namespace) -> int:
    """Run a read-only integrity scan over tracker issues and event logs."""
    from .integrity import fsck, render_integrity_report

    tracker = Tracker.open()
    report = fsck(tracker)
    if args.format == "json":
        print(json.dumps(report.to_record(), indent=2, sort_keys=True))
    else:
        print(render_integrity_report(report))
    return 0 if report.ok else 1


def cmd_push(args: argparse.Namespace) -> int:
    """Push the tracker branch to a remote."""
    tracker = Tracker.open()
    remote = args.remote or tracker.remote.effective()
    if not remote:
        print(
            col.error(
                "error: no remote specified and none configured (run: gittoc remote --set <name>)"
            ),
            file=sys.stderr,
        )
        return 1
    try:
        status = tracker.remote.push(remote)
    except RemotePushPullError as exc:
        print(col.error(f"error: {exc}"), file=sys.stderr)
        return 1
    if args.format == "json":
        print(json.dumps(status, indent=2, sort_keys=True))
    else:
        print(f"pushed {TRACKER_BRANCH} to {status['remote']} at {status['head']}")
    return 0


def cmd_new(args: argparse.Namespace) -> int:
    """Create a new issue and optionally add dependencies."""
    body = resolve_text_input(args.body, args.file, what="body", allow_empty=True)
    tracker = Tracker.open()
    _auto_pull(tracker)
    issue = tracker.create_issue(
        args.title, body or "", parse_labels(args.label), args.priority
    )
    deps = parse_issue_ids(args.dep)
    if deps:
        tracker.set_dependencies(issue.issue_id, deps)
    print(issue.issue_id)
    _auto_push(tracker)
    return 0


def cmd_list(args: argparse.Namespace) -> int:
    """List issues filtered by state, label, and/or readiness."""
    tracker = Tracker.open()
    states: tuple[str, ...]
    if args.all:
        states = STATE_ORDER
    else:
        states = parse_states(args.state) or ("open",)
    issues = tracker.list_issues(states)
    if args.label:
        required = set(parse_labels(args.label))
        issues = [issue for issue in issues if required.issubset(issue.labels)]
    if args.sort == "id":
        issues.sort(key=lambda i: issue_number(i.issue_id))
    print_issues(issues, tracker, args.format)
    return 0


def cmd_claimed(args: argparse.Namespace) -> int:
    """List all currently claimed issues."""
    tracker = Tracker.open()
    issues = tracker.list_issues(("claimed",))
    print_issues(issues, tracker, args.format)
    return 0


def cmd_unblocked(args: argparse.Namespace) -> int:
    """List all open issues that have no unresolved blocking dependencies."""
    tracker = Tracker.open()
    issues = tracker.ready_issues()
    print_issues(issues, tracker, args.format)
    return 0


def cmd_claim(args: argparse.Namespace) -> int:
    """Claim one or more issues, assigning them to the given or inferred owner."""
    tracker = Tracker.open()
    _auto_pull(tracker)
    owner = args.owner or default_owner()
    issue_ids = parse_issue_ids(args.issue_ids)
    # Validate every id up front so a batch claim is all-or-nothing: if any
    # ticket is missing or unclaimable, abort before mutating (committing) any.
    for issue_id in issue_ids:
        issue, _ = tracker.load_issue(issue_id)
        tracker.ensure_claimable(issue)
    issues = []
    for issue_id in issue_ids:
        issues.append(
            tracker.update_issue(
                issue_id,
                state="claimed",
                owner=owner,
                message=f"Claim issue {issue_id} for {owner}",
                event_kind="claimed",
                event_text=owner,
                event_actor=owner,
            )
        )
    print_issues(issues, tracker, args.format)
    _auto_push(tracker)
    return 0


def cmd_labels(args: argparse.Namespace) -> int:
    """List all labels in use, with counts, across open (or all) tickets."""
    tracker = Tracker.open()
    states = STATE_ORDER if args.all else ("open",)
    counts: dict[str, int] = {}
    for issue in tracker.list_issues(states):
        for label in issue.labels:
            counts[label] = counts.get(label, 0) + 1
    defined = tracker.load_defined_labels()
    all_labels = sorted(set(counts) | set(defined))
    if args.format == "json":
        rows = [
            {
                "label": label,
                "count": counts.get(label, 0),
                "description": defined.get(label, ""),
            }
            for label in all_labels
        ]
        print(json.dumps(rows, indent=2))
    else:
        for label in all_labels:
            count = counts.get(label, 0)
            desc = defined.get(label, "")
            if desc:
                print(f"{label:<20} {count:>4}  {desc}")
            else:
                print(f"{label:<20} {count:>4}")
    return 0


def cmd_reject(args: argparse.Namespace) -> int:
    """Reject an issue (mark as won't-do / abandoned) and print confirmation."""
    tracker = Tracker.open()
    _auto_pull(tracker)
    actor = args.actor or default_owner()
    issue = tracker.reject_issue(args.issue_id, actor=actor)
    print_issues([issue], tracker, args.format)
    _auto_push(tracker)
    return 0


def cmd_summary(args: argparse.Namespace) -> int:
    """Print a one-line summary of issue counts by state."""
    tracker = Tracker.open()
    counts = tracker.summary()
    if args.format == "json":
        print(json.dumps(counts, indent=2, sort_keys=True))
    else:
        parts = [
            f"open={counts['open']}",
            f"claimed={counts['claimed']}",
            f"blocked={counts['blocked']}",
            f"closed={counts['closed']}",
            f"rejected={counts['rejected']}",
            f"ready={counts['ready']}",
        ]
        print(" ".join(parts))
    return 0


def _dead_refs(tracker: Tracker, data: dict) -> frozenset[str]:
    """Return the short hashes of event refs in *data* that no longer resolve.

    Computed only for text output (JSON keeps the raw ref). Scans the notes and
    history that will be rendered and batch-checks their commit hashes in one
    git call so a surfaced ref orphaned by a later rebase/gc can be marked.
    """
    entries = (data.get("recent_notes") or []) + (data.get("history") or [])
    hashes = {ref_short_hash(entry.get("ref", "")) for entry in entries}
    return frozenset(missing_objects(tracker.repo, hashes))


def _build_show_data(
    tracker: Tracker,
    issue_id: str,
    *,
    all_events: bool = False,
    notes_only: bool = False,
    limit: int | None = None,
) -> dict:
    """Build the data dict for show/resume output."""
    issue, path = tracker.load_issue(issue_id)
    note_count = tracker.events.note_count(issue.issue_id)
    data = issue.to_display(path.relative_to(tracker.checkout), note_count)
    if all_events:
        data["history"] = tracker.events.filtered(issue.issue_id, limit=limit)
    elif notes_only:
        notes = tracker.events.filtered(issue.issue_id, kinds={"note"}, limit=limit)
        data["recent_notes"] = notes
        data["recent_notes_shown"] = len(notes)
        data["recent_notes_total"] = note_count
    else:
        notes_limit = limit if limit is not None else SHOW_NOTES_LIMIT
        recent_notes = tracker.events.filtered(
            issue.issue_id, kinds={"note"}, limit=notes_limit
        )
        data["recent_notes"] = recent_notes
        data["recent_notes_shown"] = len(recent_notes)
        data["recent_notes_total"] = note_count
        if note_count > len(recent_notes):
            data["recent_notes_hint"] = (
                f"showing {len(recent_notes)} of {note_count} notes; "
                f"use `show {issue_id} -n` for all"
            )
    return data


def cmd_show(args: argparse.Namespace) -> int:
    """Print a single issue with optional notes/history detail."""
    tracker = Tracker.open()
    data = _build_show_data(
        tracker,
        args.issue_id,
        all_events=args.all,
        notes_only=args.notes,
        limit=args.limit,
    )
    if args.format == "json":
        print(json.dumps(data, indent=2, sort_keys=True))
    else:
        print(render_show_text(data, _dead_refs(tracker, data)))
    return 0


def cmd_update(args: argparse.Namespace) -> int:
    """Update one or more fields of an existing issue."""
    body = resolve_text_input(args.body, args.file, what="body", allow_empty=True)
    tracker = Tracker.open()
    _auto_pull(tracker)
    state = parse_state(args.state)
    add_labels = parse_labels(args.label)
    replace_labels = parse_labels(args.replace_label)
    remove_labels = set(parse_labels(args.remove_label))
    if replace_labels and (add_labels or remove_labels):
        raise SystemExit(
            "cannot combine --replace-label with --label or --remove-label"
        )
    labels = None
    if replace_labels:
        labels = replace_labels
    elif add_labels or remove_labels:
        issue, _ = tracker.load_issue(args.issue_id)
        labels = list(issue.labels)
        for label in add_labels:
            if label not in labels:
                labels.append(label)
        if remove_labels:
            labels = [label for label in labels if label not in remove_labels]
    has_changes = any(
        [
            args.title is not None,
            body is not None,
            state is not None,
            args.owner is not None,
            labels is not None,
            args.priority is not None,
        ]
    )
    if not has_changes:
        print("no fields to update", file=sys.stderr)
        return 1
    issue = tracker.update_issue(
        args.issue_id,
        title=args.title,
        body=body,
        state=state,
        owner=args.owner,
        labels=labels,
        priority=args.priority,
        event_text="fields updated",
    )
    print(issue.issue_id)
    _auto_push(tracker)
    return 0


def cmd_dep(args: argparse.Namespace) -> int:
    """Add or remove blocking dependencies for an issue."""
    tracker = Tracker.open()
    _auto_pull(tracker)
    dep_ids = parse_issue_ids(args.dep_ids)
    if args.remove:
        issue = tracker.remove_dependencies(args.issue_id, dep_ids)
    else:
        issue = tracker.set_dependencies(args.issue_id, dep_ids)
    print(issue.issue_id)
    _auto_push(tracker)
    return 0


def cmd_grep(args: argparse.Namespace) -> int:
    """Search ticket files for a pattern using grep."""
    grep_args = [a for a in (args.grep_args or []) if a != "--"]
    if not grep_args:
        print(col.error("error: grep requires a pattern"), file=sys.stderr)
        return 1
    pattern = grep_args[0]
    extra_flags = grep_args[1:]
    tracker = Tracker.open()
    states: tuple[str, ...]
    if args.all:
        states = STATE_ORDER
    else:
        states = parse_states(args.state) or ("open",)
    files: list[str] = []
    for state in states:
        state_path = tracker.state_dir(state)
        if state_path.is_dir():
            files.extend(
                str(p.relative_to(tracker.checkout))
                for p in sorted(state_path.iterdir())
                if p.is_file()
            )
    if not files:
        return 1
    cmd = ["grep", "-H"] + extra_flags + ["--", pattern] + files
    result = subprocess.run(cmd, cwd=tracker.checkout)
    return result.returncode


def cmd_close(args: argparse.Namespace) -> int:
    """Mark an issue as closed (done) and print confirmation."""
    tracker = Tracker.open()
    _auto_pull(tracker)
    actor = args.actor or default_owner()
    issue = tracker.update_issue(
        args.issue_id,
        state="closed",
        message=f"Close issue {args.issue_id}",
        event_kind="closed",
        event_actor=actor,
    )
    print_issues([issue], tracker, args.format)
    _auto_push(tracker)
    return 0


def cmd_log(args: argparse.Namespace) -> int:
    """Print git log for a specific issue file, or the full tracker branch."""
    tracker = Tracker.open()
    reverse = ["--reverse"] if args.reverse else []
    if args.issue_id:
        _, path = tracker.load_issue(args.issue_id)
        rel = path.relative_to(tracker.checkout)
        git_args = ["log", *reverse, "--follow", "--oneline", "--", str(rel)]
    else:
        git_args = ["log", *reverse, "--oneline"]
    out = run_git(git_args, cwd=tracker.checkout)
    lines = [line for line in out.stdout.splitlines() if line]
    # Slice client-side: git log --follow --reverse --max-count=N drops all
    # output in some git versions, so apply the limit uniformly after the fact.
    # Mirrors `git log -n N` semantics: N newest commits, then --reverse flips.
    if args.limit is not None:
        lines = lines[-args.limit :] if args.reverse else lines[: args.limit]
    if lines:
        print("\n".join(lines))
    return 0


def cmd_note(args: argparse.Namespace) -> int:
    """Append a note to an issue and print its ID."""
    text = resolve_text_input(args.text, args.file, what="note text", allow_empty=False)
    if text is None:
        raise SystemExit("note requires text (positional argument or -F)")
    tracker = Tracker.open()
    _auto_pull(tracker)
    issue = tracker.add_note(args.issue_id, text, actor=args.actor)
    print(issue.issue_id)
    _auto_push(tracker)
    return 0


def cmd_resume(args: argparse.Namespace) -> int:
    """Select the next issue and display it in show format."""
    tracker = Tracker.open()
    if args.issue_id:
        issue_id = args.issue_id
    else:
        owner = args.owner or default_owner()
        issue, _ = tracker.resume_issue(owner)
        if issue is None:
            if args.format == "json":
                print("null")
            else:
                print("no resumable issues")
            return 0
        issue_id = issue.issue_id
    data = _build_show_data(tracker, issue_id)
    if args.format == "json":
        print(json.dumps(data, indent=2, sort_keys=True))
    else:
        print(render_show_text(data, _dead_refs(tracker, data)))
    return 0
