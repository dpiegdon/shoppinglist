"""Command-line interface: argument parsing and alias resolution."""

from __future__ import annotations

import argparse
import subprocess
import sys

from . import __version__
from . import colors as col
from .commands import (
    cmd_claim,
    cmd_claimed,
    cmd_close,
    cmd_dep,
    cmd_fsck,
    cmd_grep,
    cmd_init,
    cmd_labels,
    cmd_list,
    cmd_log,
    cmd_new,
    cmd_note,
    cmd_pull,
    cmd_push,
    cmd_reject,
    cmd_remote,
    cmd_resume,
    cmd_show,
    cmd_summary,
    cmd_unblocked,
    cmd_update,
)
from .common import DEFAULT_PRIORITY, STATE_ORDER
from .tracker import StaleTrackerError


def positive_int(value: str) -> int:
    """argparse type for a positive integer (>= 1), e.g. --limit."""
    try:
        number = int(value)
    except ValueError:
        raise argparse.ArgumentTypeError(f"expected an integer, got {value!r}")
    if number < 1:
        raise argparse.ArgumentTypeError(f"must be >= 1, got {number}")
    return number


def add_format_argument(
    parser: argparse.ArgumentParser, default: str = "normal"
) -> None:
    """Add -f/--format with compact/normal/verbose/json choices to a subcommand parser."""
    parser.add_argument(
        "-f",
        "--format",
        choices=("compact", "normal", "verbose", "json"),
        default=default,
        help="output format (default: %(default)s)",
    )


def add_text_format_argument(parser: argparse.ArgumentParser) -> None:
    """Add -f/--format with text/json choices to a subcommand parser."""
    parser.add_argument(
        "-f",
        "--format",
        choices=("text", "json"),
        default="text",
        help="output format (default: text)",
    )


def build_parser() -> argparse.ArgumentParser:
    """Build and return the top-level argument parser with all subcommands registered."""
    parser = argparse.ArgumentParser(prog="gittoc")
    parser.add_argument(
        "-V", "--version", action="version", version=f"%(prog)s {__version__}"
    )
    sub = parser.add_subparsers(dest="command", required=True)

    claim_parser = sub.add_parser(
        "claim",
        help="claim one or more issues",
        description="Assign one or more tickets to yourself (or --owner). "
        "Only open, unblocked tickets can be claimed.",
    )
    claim_parser.add_argument(
        "issue_ids",
        nargs="+",
        metavar="issue_id",
        help="ticket(s) to claim, e.g. T-42,T-43 or T-42 T-43",
    )
    claim_parser.add_argument(
        "--owner",
        help="owner name (default: $GITTOC_OWNER or $USER)",
    )
    add_format_argument(claim_parser)
    claim_parser.set_defaults(func=cmd_claim)

    claimed_parser = sub.add_parser(
        "claimed",
        aliases=["c"],
        help="list all currently claimed issues",
        description="List all tickets currently in the claimed state, across all owners.",
    )
    add_format_argument(claimed_parser)
    claimed_parser.set_defaults(func=cmd_claimed)

    close_parser = sub.add_parser(
        "close",
        help="mark an issue as done",
        description="Mark a ticket as closed (work complete). "
        "Use reject instead for tickets that won't be done.",
    )
    close_parser.add_argument("issue_id", help="ticket to close, e.g. T-42")
    close_parser.add_argument(
        "--actor",
        help="override actor name (default: $GITTOC_OWNER or $USER)",
    )
    add_format_argument(close_parser)
    close_parser.set_defaults(func=cmd_close)

    dep_parser = sub.add_parser(
        "depends",
        aliases=["dep"],
        help="add or remove blocking dependencies",
        description="Make ISSUE_ID depend on one or more DEP_IDs — the dependencies "
        "must be closed before ISSUE_ID can be claimed. Use -r to remove dependencies.",
    )
    dep_parser.add_argument(
        "issue_id", help="ticket that depends on the others, e.g. T-4"
    )
    dep_parser.add_argument(
        "dep_ids",
        nargs="+",
        metavar="dep_id",
        help="one or more blocking tickets, e.g. T-1,T-2 or T-1 T-2",
    )
    dep_parser.add_argument(
        "-r",
        "--remove",
        action="store_true",
        help="remove the listed dependencies instead of adding them",
    )
    dep_parser.set_defaults(func=cmd_dep)

    fsck_parser = sub.add_parser(
        "fsck",
        help="validate tracker files, dependencies, cycles, and event logs",
        description="Scan all tracker files for integrity issues: malformed JSON, "
        "missing or dangling dependencies, dependency cycles, and orphaned event logs. "
        "Exits non-zero if any problems are found.",
    )
    add_text_format_argument(fsck_parser)
    fsck_parser.set_defaults(func=cmd_fsck)

    grep_parser = sub.add_parser(
        "grep",
        aliases=["g"],
        help="search ticket files for a pattern",
        description="Search ticket JSON and event files with grep. "
        "Use -s to select states, -a for all. "
        "Pattern and grep flags follow: gittoc grep [-s STATE] [-a] PATTERN [-i] [-n] ...",
    )
    grep_parser.add_argument(
        "-s",
        "--state",
        action="append",
        metavar="STATE",
        help="search this state (repeatable, comma-separated; default: open)",
    )
    grep_parser.add_argument(
        "-a",
        "--all",
        action="store_true",
        help="search all states",
    )
    grep_parser.add_argument(
        "grep_args",
        nargs=argparse.REMAINDER,
        help="pattern and optional grep flags (e.g. feature -i -n)",
    )
    grep_parser.set_defaults(func=cmd_grep)

    init_parser = sub.add_parser(
        "init",
        help="initialize tracker worktree",
        description="Set up the gittoc tracker in this repository. "
        "Creates the gittoc branch and a hidden worktree at .git/gittoc/. "
        "Run once per repository clone. Also auto-configures the remote if one can be inferred.",
    )
    init_parser.set_defaults(func=cmd_init)

    labels_parser = sub.add_parser(
        "labels",
        help="list all labels in use across tickets with counts",
        description="List every label currently applied to tickets, with how many "
        "tickets carry each one. Useful for backlog grooming and finding label typos.",
    )
    labels_parser.add_argument(
        "-a",
        "--all",
        action="store_true",
        help="include closed tickets (default: open only)",
    )
    add_text_format_argument(labels_parser)
    labels_parser.set_defaults(func=cmd_labels)

    list_parser = sub.add_parser(
        "list",
        aliases=["l"],
        help="list issues ordered by priority",
        description="List tickets sorted by priority (1=highest). "
        "Defaults to open tickets only; use -s or -a to include other states.",
    )
    list_parser.add_argument(
        "-s",
        "--state",
        action="append",
        metavar="STATE",
        help="include this state (repeatable, comma-separated; default: open)",
    )
    list_parser.add_argument(
        "-l",
        "--label",
        action="append",
        metavar="LABEL",
        help="filter to tickets carrying this label (repeatable, comma-separated, AND)",
    )
    list_parser.add_argument(
        "-a",
        "--all",
        action="store_true",
        help="show all states",
    )
    list_parser.add_argument(
        "--sort",
        choices=["priority", "id"],
        default="priority",
        help="sort order: priority (default) or id (chronological)",
    )
    add_format_argument(list_parser)
    list_parser.set_defaults(func=cmd_list)

    log_parser = sub.add_parser(
        "log", help="show git history for an issue, or all recent tracker changes"
    )
    log_parser.add_argument(
        "issue_id",
        nargs="?",
        help="ticket to inspect; omit to show full tracker branch log",
    )
    log_parser.add_argument(
        "--reverse",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="show oldest-first (default: --reverse); pass --no-reverse for newest-first",
    )
    log_parser.add_argument(
        "--limit",
        type=positive_int,
        metavar="N",
        help="show at most N commits",
    )
    log_parser.set_defaults(func=cmd_log)

    new_parser = sub.add_parser("new", help="create an issue")
    new_parser.add_argument("title", help="one-line summary of the issue")
    new_parser.add_argument("-b", "--body", help="longer description or context")
    new_parser.add_argument(
        "-F",
        "--file",
        metavar="FILE",
        help="read body from FILE, or '-' for stdin (alternative to -b; "
        "avoids shell evaluation of backticks/$())",
    )
    new_parser.add_argument(
        "-l",
        "--label",
        action="append",
        metavar="LABEL",
        help="tag for this issue (repeatable, comma-separated)",
    )
    new_parser.add_argument(
        "-p",
        "--priority",
        type=int,
        default=DEFAULT_PRIORITY,
        help=f"1 (highest) to 5 (lowest), default {DEFAULT_PRIORITY}",
    )
    new_parser.add_argument(
        "-d",
        "--dep",
        action="append",
        metavar="ISSUE_ID",
        help="add a blocking dependency (repeatable, comma-separated, e.g. -d T-1,T-2)",
    )
    new_parser.set_defaults(func=cmd_new)

    note_parser = sub.add_parser(
        "note", aliases=["n"], help="append a note to an issue"
    )
    note_parser.add_argument("issue_id", help="ticket to annotate, e.g. T-42")
    note_parser.add_argument("text", nargs="?", help="note text (omit to use -F)")
    note_parser.add_argument(
        "-F",
        "--file",
        metavar="FILE",
        help="read note text from FILE, or '-' for stdin; avoids the caller's "
        "shell evaluating backticks/$() — quote your heredoc delimiter: <<'EOF'",
    )
    note_parser.add_argument(
        "--actor",
        help="override actor name (default: $GITTOC_OWNER or $USER)",
    )
    note_parser.set_defaults(func=cmd_note)

    pull_parser = sub.add_parser(
        "pull",
        aliases=["pl", "pul"],
        help="fetch and merge the tracker branch from a remote",
    )
    pull_parser.add_argument(
        "remote", nargs="?", help="remote name (default: configured gittoc remote)"
    )
    add_text_format_argument(pull_parser)
    pull_parser.set_defaults(func=cmd_pull)

    push_parser = sub.add_parser(
        "push", aliases=["ps", "pus"], help="push the tracker branch to a remote"
    )
    push_parser.add_argument(
        "remote", nargs="?", help="remote name (default: configured gittoc remote)"
    )
    add_text_format_argument(push_parser)
    push_parser.set_defaults(func=cmd_push)

    unblocked_parser = sub.add_parser(
        "unblocked",
        aliases=["ubl"],
        help="list open issues with no blocking dependencies",
        description="List open tickets that have no unresolved dependencies — "
        "these are safe to claim and start working on right now.",
    )
    add_format_argument(unblocked_parser)
    unblocked_parser.set_defaults(func=cmd_unblocked)

    reject_parser = sub.add_parser(
        "reject",
        help="mark an issue as won't-do / abandoned",
        description="Mark a ticket as rejected (won't fix, out of scope, abandoned). "
        "Use close instead when work is actually complete.",
    )
    reject_parser.add_argument("issue_id", help="ticket to reject, e.g. T-42")
    reject_parser.add_argument(
        "--actor",
        help="override actor name (default: $GITTOC_OWNER or $USER)",
    )
    add_format_argument(reject_parser)
    reject_parser.set_defaults(func=cmd_reject)

    remote_parser = sub.add_parser(
        "remote",
        help="show or configure tracker remote wiring",
        description="Show which remote the tracker branch is configured to sync with, "
        "or configure it. Use --set to pin a specific remote, --auto to infer it. "
        "Once configured, push/pull work without specifying a remote each time.",
    )
    remote_parser.add_argument(
        "--set",
        metavar="REMOTE",
        help="configure tracker to use this remote",
    )
    remote_parser.add_argument(
        "--auto",
        action="store_true",
        help="infer and configure remote automatically",
    )
    add_text_format_argument(remote_parser)
    remote_parser.set_defaults(func=cmd_remote)

    resume_parser = sub.add_parser(
        "resume",
        aliases=["r"],
        help="show context for your next ticket",
        description="Show work context for a ticket to help you pick up where you left off. "
        "Without an ID, auto-selects the best next ticket: your claimed work first, "
        "then highest-priority ready ticket, then highest-priority open ticket.",
    )
    resume_parser.add_argument(
        "issue_id",
        nargs="?",
        help="ticket to resume; omit to auto-select by priority and ownership",
    )
    resume_parser.add_argument(
        "--owner",
        help="owner for auto-selection (default: $GITTOC_OWNER or $USER)",
    )
    add_text_format_argument(resume_parser)
    resume_parser.set_defaults(func=cmd_resume)

    show_parser = sub.add_parser("show", aliases=["s"], help="show one issue in detail")
    show_parser.add_argument("issue_id", help="ticket to show, e.g. T-42")
    show_parser.add_argument(
        "-a",
        "--all",
        action="store_true",
        help="show everything: all notes and full event history",
    )
    show_parser.add_argument(
        "-n",
        "--notes",
        action="store_true",
        help="show all notes (no limit)",
    )
    show_parser.add_argument(
        "--limit",
        type=positive_int,
        help="maximum number of notes/events to show",
    )
    add_text_format_argument(show_parser)
    show_parser.set_defaults(func=cmd_show)

    summary_parser = sub.add_parser(
        "summary",
        aliases=["sum"],
        help="print ticket counts by state",
        description="Print a one-line summary of how many tickets are in each state, "
        "plus how many open tickets are unblocked and ready to claim.",
    )
    add_text_format_argument(summary_parser)
    summary_parser.set_defaults(func=cmd_summary)

    update_parser = sub.add_parser("update", aliases=["up"], help="update issue fields")
    update_parser.add_argument("issue_id", help="ticket to update, e.g. T-42")
    update_parser.add_argument("-t", "--title", help="new title")
    update_parser.add_argument("-b", "--body", help="new body text")
    update_parser.add_argument(
        "-F",
        "--file",
        metavar="FILE",
        help="read new body from FILE, or '-' for stdin (alternative to -b; "
        "avoids shell evaluation of backticks/$())",
    )
    update_parser.add_argument("--state", choices=STATE_ORDER, help="new state")
    update_parser.add_argument(
        "--owner",
        help="assign to this owner",
    )
    update_parser.add_argument(
        "-l",
        "--label",
        action="append",
        metavar="LABEL",
        help="add label(s); repeatable and comma-separated",
    )
    update_parser.add_argument(
        "-L",
        "--replace-label",
        action="append",
        metavar="LABEL",
        help="replace all labels with this set; repeatable and comma-separated",
    )
    update_parser.add_argument(
        "-x",
        "--remove-label",
        action="append",
        metavar="LABEL",
        help="remove label(s); repeatable and comma-separated",
    )
    update_parser.add_argument(
        "-p",
        "--priority",
        type=int,
        help="1 (highest) to 5 (lowest)",
    )
    update_parser.set_defaults(func=cmd_update)

    return parser


def main(argv: list[str] | None = None) -> int:
    """Entry point: parse argv, resolve aliases, dispatch to the appropriate command."""
    if argv is None:
        argv = sys.argv[1:]
    else:
        argv = list(argv)
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        return args.func(args)
    except StaleTrackerError as exc:
        print(str(exc), file=sys.stderr)
        return 2
    except subprocess.CalledProcessError as exc:
        msg = exc.stderr.strip() or exc.stdout.strip() or str(exc)
        print(col.error(f"git error: {msg}"), file=sys.stderr)
        return 1
    except OSError as exc:
        print(col.error(f"error: {exc}"), file=sys.stderr)
        return 1
