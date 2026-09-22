"""The release guard that keeps the protocol version and the release number honest (T-243).

`release-guard.py` lives at the repo root next to `release.sh`, which is its only production
caller; these tests call it with numbers of their own. Both ways in are covered — the rules as a
function, and the script as release.sh runs it, because the exit status and the printed reason are
the part of it release.sh actually depends on.
"""

import importlib.util
import subprocess
import sys
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPO_ROOT / "release-guard.py"
PROTOCOL_FILE = REPO_ROOT / "server" / "src" / "shoppinglist_server" / "protocol.py"


def _load_guard():
    spec = importlib.util.spec_from_file_location("release_guard", SCRIPT)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


guard = _load_guard()


def _run(*args):
    return subprocess.run(
        [sys.executable, str(SCRIPT), *args], capture_output=True, text=True, cwd=REPO_ROOT
    )


# ---- releases that may be cut ------------------------------------------------


@pytest.mark.parametrize(
    "tree,new_major,prev_protocol,prev_major,why",
    [
        (3, 3, 0, 2, "3.0.0 after 2.2.0, whose tag predates protocol.py"),
        (3, 3, 3, 3, "3.1.0 after 3.0.0, protocol unchanged"),
        (3, 9, 3, 3, "a major release that changes nothing on the wire"),
        (4, 4, 3, 3, "4.0.0 carrying a protocol bump"),
        (0, 1, 0, 0, "the first release ever, no protocol yet"),
        (2, 3, 2, 3, "a protocol left behind by a major release"),
    ],
)
def test_allowed(tree, new_major, prev_protocol, prev_major, why):
    assert guard.refusal(tree, new_major, prev_protocol, prev_major) is None, why


# ---- releases that must be refused -------------------------------------------


@pytest.mark.parametrize(
    "tree,new_major,prev_protocol,prev_major",
    [
        (3, 2, 0, 2),  # protocol 3 in the tree, but 2.3.0 is being cut
        (4, 3, 3, 3),  # protocol bumped, 3.1.0 is being cut
        (4, 0, 3, 0),  # protocol ahead of a 0.x release
    ],
)
def test_refused_because_the_protocol_is_ahead_of_the_major(
    tree, new_major, prev_protocol, prev_major
):
    reason = guard.refusal(tree, new_major, prev_protocol, prev_major)

    assert reason is not None
    assert "may never exceed" in reason


@pytest.mark.parametrize(
    "tree,new_major,prev_protocol,prev_major",
    [
        (4, 4, 3, 4),  # 4.1.0 after 4.0.0, with a protocol bump
        (3, 3, 2, 3),  # 3.2.0 after 3.1.0, with a protocol bump
        (3, 3, 0, 3),  # protocol first introduced in a minor release
    ],
)
def test_refused_because_a_protocol_bump_needs_a_major_release(
    tree, new_major, prev_protocol, prev_major
):
    reason = guard.refusal(tree, new_major, prev_protocol, prev_major)

    assert reason is not None
    assert "MAJOR" in reason
    assert f"{prev_major + 1}.0.0" in reason


@pytest.mark.parametrize(
    "tree,new_major,prev_protocol,prev_major",
    [
        (2, 3, 3, 3),  # protocol dropped 3 -> 2 in a minor release
        (2, 4, 3, 3),  # protocol dropped 3 -> 2 even though the release is major
        (0, 1, 1, 1),  # protocol.py reverted to before it existed
    ],
)
def test_refused_because_the_protocol_decreased(tree, new_major, prev_protocol, prev_major):
    reason = guard.refusal(tree, new_major, prev_protocol, prev_major)

    assert reason is not None
    assert "cannot go down" in reason
    assert f"{prev_protocol} -> {tree}" in reason


# ---- the script as release.sh runs it ----------------------------------------


def test_the_script_is_silent_and_succeeds_on_a_good_release():
    result = _run("3", "3", "", "2")

    assert result.returncode == 0
    assert result.stdout == ""


def test_the_script_prints_the_reason_and_fails():
    result = _run("4", "3", "3", "3")

    assert result.returncode == 1
    assert "PROTOCOL_VERSION is 4" in result.stdout


def test_an_empty_previous_protocol_counts_as_zero():
    # What a tag older than protocol.py yields: `git show <tag>:...protocol.py` prints nothing.
    assert _run("3", "3", "", "2").returncode == 0
    # ...and it is a real 0, not a "skip the check": the same release as a minor one is refused.
    refused = _run("3", "3", "", "3")
    assert refused.returncode == 1
    assert "0 -> 3" in refused.stdout


def test_an_empty_previous_major_counts_as_zero():
    # No previous tag at all — the first release.
    assert _run("1", "1", "", "").returncode == 0


@pytest.mark.parametrize("args", [(), ("3",), ("3", "3", "0"), ("3", "3", "0", "2", "extra")])
def test_the_wrong_number_of_arguments_is_an_error(args):
    result = _run(*args)

    assert result.returncode == 2
    assert "usage" in result.stderr


@pytest.mark.parametrize("bad", ["three", "3.0", "-1", "٣", "1_0"])
def test_arguments_that_are_not_numbers_are_an_error(bad):
    result = _run(bad, "3", "0", "2")

    assert result.returncode == 2
    assert "not a number" in result.stderr


# ---- release.sh actually asks ------------------------------------------------


def test_release_sh_runs_the_guard_before_it_writes_anything():
    """Grepping a shell script is coarse, but the alternative is running release.sh, which
    rebuilds the world and tags the repo. What is asserted is the wiring the tests above cannot
    see: that the guard is called at all, with the four numbers, among the preconditions."""
    release_sh = (REPO_ROOT / "release.sh").read_text(encoding="utf-8")
    preconditions, _, rest = release_sh.partition("bumping versions")

    assert 'release-guard.py "$TREE_PROTOCOL" "$NEW_MAJOR" "$PREV_PROTOCOL" "$PREV_MAJOR"' in (
        preconditions
    )
    assert "GUARD_REFUSAL" in preconditions
    assert "release-guard.py" not in rest  # nothing to re-check after the tree has been written


def test_the_pattern_release_sh_reads_the_protocol_with_matches_this_tree():
    """release.sh pulls PROTOCOL_VERSION out of protocol.py with a sed expression; if that
    expression and the file ever drift apart, the guard is fed an empty number."""
    release_sh = (REPO_ROOT / "release.sh").read_text(encoding="utf-8")
    expression = next(
        line.split("sed -n ", 1)[1].split(" | head", 1)[0]
        for line in release_sh.splitlines()
        if "read_protocol()" in line
    )
    result = subprocess.run(
        f"sed -n {expression} < {PROTOCOL_FILE} | head -1",
        shell=True,
        capture_output=True,
        text=True,
    )

    from shoppinglist_server.protocol import PROTOCOL_VERSION

    assert result.stdout.strip() == str(PROTOCOL_VERSION)
