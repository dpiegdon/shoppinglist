#!/usr/bin/env python3
"""Refuses a release whose version number contradicts the protocol version (T-243).

`PROTOCOL_VERSION` (server/src/shoppinglist_server/protocol.py) is what an installed client is
checked against: bumping it turns away every app in the field until it has been updated. That is a
MAJOR event by definition, so the two numbers have to agree, and the one moment where they can
still be made to agree cheaply is before the tag exists.

Three rules, all about the release being cut:

1. the protocol version may never exceed the release's MAJOR version;
2. a protocol version higher than the previous release's needs a MAJOR higher than the previous
   release's — a protocol bump ships in a major release or not at all; and
3. the protocol version may never go DOWN from the previous release's. Once a release has shipped
   requiring protocol N, nothing (an installed client's cached assumptions, another maintainer's
   half-finished edit to protocol.py) untells it that; a tree that would ship less than the
   previous release's PROTOCOL_VERSION is almost certainly a revert, not an intentional change.

A major release without a protocol change (a rebrand, a storage rewrite that nothing on the wire
notices) is allowed and leaves the protocol alone — the reverse of rule 2.

Called by release.sh with the four numbers it read out of the tree and the previous tag, and by
server/tests/test_release_guard.py with numbers of its own — which is why the rules live here,
in something testable, rather than inline in the shell script:

    ./release-guard.py <tree-protocol> <new-major> <prev-protocol> <prev-major>

An empty string for either of the previous numbers means "no previous release said" and counts as
0 — that is how a tag older than protocol.py, and the very first release, are spelled. Prints the
reason and exits 1 when the release must not be cut, exits 0 silently otherwise, and exits 2 on
arguments that are not numbers.
"""

import sys

USAGE = "usage: release-guard.py <tree-protocol> <new-major> <prev-protocol> <prev-major>"


def refusal(
    tree_protocol: int, new_major: int, previous_protocol: int, previous_major: int
) -> str | None:
    """The reason this release must not be cut, or None when it may be."""
    if tree_protocol > new_major:
        return (
            f"PROTOCOL_VERSION is {tree_protocol}, but this release's major version is "
            f"{new_major}. The protocol version may never exceed it: a client refused by "
            f"protocol {tree_protocol} would be told to install a {new_major}.x app that is "
            f"refused as well. Cut {tree_protocol}.0.0 or later, or lower PROTOCOL_VERSION."
        )
    if tree_protocol > previous_protocol and new_major <= previous_major:
        return (
            f"PROTOCOL_VERSION went {previous_protocol} -> {tree_protocol} since the previous "
            f"release, which turns away every client in the field until it is updated. That "
            f"ships as a MAJOR release only: this one's major version is {new_major}, and the "
            f"previous release's was {previous_major}. Cut {previous_major + 1}.0.0 or later."
        )
    if tree_protocol < previous_protocol:
        return (
            f"PROTOCOL_VERSION went {previous_protocol} -> {tree_protocol} since the previous "
            f"release. A protocol version cannot go down once shipped — raise it back to at "
            f"least {previous_protocol}, or this looks like an accidental revert of "
            f"protocol.py."
        )
    return None


def main(argv: list[str]) -> int:
    if len(argv) != 4:
        print(USAGE, file=sys.stderr)
        return 2
    numbers = []
    for arg in argv:
        # "" is a number here: the previous tag had no protocol.py, or there is no previous tag.
        text = arg.strip() or "0"
        if not text.isascii() or not text.isdigit():
            print(f"release-guard: not a number: '{arg}'\n{USAGE}", file=sys.stderr)
            return 2
        numbers.append(int(text))
    reason = refusal(*numbers)
    if reason is not None:
        print(reason)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
