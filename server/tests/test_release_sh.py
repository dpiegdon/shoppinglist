"""Structural tests for release.sh's failure-handling (T-280).

release.sh commits and tags a real release, so nothing here runs it: these tests read its text (the
same technique test_release_guard.py's `test_release_sh_runs_the_guard_before_it_writes_anything`
already uses) and, for the one piece of logic that is a pure function with no side effects
(`version_gt`), extract just that function's body and execute it directly.
"""

import re
import subprocess
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parents[2]
RELEASE_SH = REPO_ROOT / "release.sh"
BUILD_WHEEL_SH = REPO_ROOT / "build-wheel.sh"
SMOKE_WHEEL_SH = REPO_ROOT / "smoke-wheel.sh"
BOOTSTRAP_SH = REPO_ROOT / "bootstrap.sh"
VERIFY_ALL_SH = REPO_ROOT / "verify-all.sh"


def _text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


# ---- version_gt: extracted and actually executed -----------------------------


def _extract_function(src: str, name: str) -> str:
    """Pulls a `name() { ... }` block out of a shell script by brace counting, so it can be
    sourced on its own without running anything else in the file."""
    start = src.index(f"{name}() {{")
    depth = 0
    for i in range(start, len(src)):
        if src[i] == "{":
            depth += 1
        elif src[i] == "}":
            depth -= 1
            if depth == 0:
                return src[start : i + 1]
    raise AssertionError(f"unbalanced braces extracting {name}() from the script")


def _version_gt(a: str, b: str) -> bool:
    fn = _extract_function(_text(RELEASE_SH), "version_gt")
    result = subprocess.run(
        ["bash", "-c", f"{fn}\nversion_gt {a!r} {b!r}"],
        capture_output=True,
        text=True,
    )
    return result.returncode == 0


@pytest.mark.parametrize(
    "new,current,expected",
    [
        ("3.1.0", "3.0.1", True),
        ("4.0.0", "3.9.9", True),
        ("3.0.5", "3.1.0", False),  # the exact regression named in T-280
        ("3.0.1", "3.0.1", False),  # equal is not greater
        ("3.0.10", "3.0.9", True),  # numeric, not lexicographic, comparison
        ("3.0.2", "3.0.10", False),
    ],
)
def test_version_gt(new, current, expected):
    assert _version_gt(new, current) is expected


def test_release_sh_refuses_a_version_that_goes_backwards():
    """3.1.0 -> 3.0.5 used to be accepted because only equality was checked."""
    assert 'version_gt "$VERSION" "$CURRENT_VERSION"' in _text(RELEASE_SH)


# ---- the half-released-tree trap ----------------------------------------------


def test_release_sh_installs_a_cleanup_trap_before_writing_anything():
    src = _text(RELEASE_SH)
    preconditions, _, _rest = src.partition('step "bumping versions"')
    assert "trap release_cleanup_on_failure EXIT" in preconditions
    assert "RELEASE_TOUCHED_PATHS" in preconditions
    # every path the script writes is covered, so a failure has something to restore
    for path in (
        "server/pyproject.toml",
        "android/app/build.gradle.kts",
        "web/package.json",
        "web/package-lock.json",
        "server/src/shoppinglist_server/web_dist",
        '"$EMBEDDED_APK"',
    ):
        assert path in preconditions, f"{path} is not in RELEASE_TOUCHED_PATHS"


def test_the_cleanup_trap_only_fires_on_failure_before_a_commit():
    src = _text(RELEASE_SH)
    trap_fn = _extract_function(src, "release_cleanup_on_failure")
    assert '"$status" -ne 0' in trap_fn
    assert "START_HEAD" in trap_fn  # never undoes a commit that already landed


def test_release_sh_stages_only_the_paths_it_touched():
    """`git add -A` used to sweep in anything else sitting in the tree (the exact incident
    reverted in 52a6223); the commit step must add only what this script itself wrote."""
    src = _text(RELEASE_SH)
    assert not re.search(r"^git add -A\b", src, re.MULTILINE)
    assert 'git add -- "${RELEASE_TOUCHED_PATHS[@]}"' in src


# ---- the signing-key guard fails closed, not open -----------------------------


def test_an_unreadable_previous_certificate_is_fatal():
    src = _text(RELEASE_SH)
    # the read of PREVIOUS_CERT must be guarded by a die, not merely by "if it happened to work"
    cert_block = src[src.index("EMBEDDED_APK=") : src.index('echo "  version ')]
    assert re.search(r'\[ -n "\$PREVIOUS_CERT" \] \|\|\s*\n\s*die', cert_block)
    assert "RELEASE_FIRST_RELEASE" in cert_block  # the explicit escape hatch


def test_reassurance_and_skip_message_are_mutually_exclusive_branches():
    src = _text(RELEASE_SH)
    branch = src[
        src.index('if [ "$CERT_WILL_BE_COMPARED" = 1 ]') : src.index(
            'cp "$BUILT_APK" "$EMBEDDED_APK"'
        )
    ]
    assert "signed, same key as the previous release" in branch
    assert "no previous release to compare the signing key against" in branch
    assert branch.index('if [ "$CERT_WILL_BE_COMPARED" = 1 ]') < branch.index(
        "signed, same key as the previous release"
    )


# ---- versionCode monotonicity against the APK that actually shipped -----------


def test_versioncode_is_checked_against_the_previously_shipped_apk():
    src = _text(RELEASE_SH)
    preconditions, _, _ = src.partition('step "bumping versions"')
    assert 'dump badging "$EMBEDDED_APK"' in preconditions
    assert "PREVIOUS_CODE" in preconditions
    assert '"$NEXT_CODE" -gt "$PREVIOUS_CODE"' in preconditions


# ---- smaller fixes -------------------------------------------------------------


def test_notes_flag_without_a_filename_is_rejected():
    src = _text(RELEASE_SH)
    assert "NOTES_GIVEN" in src
    assert '"--notes requires a filename"' in src


def test_assemble_release_retries_online_on_a_cold_cache():
    src = _text(RELEASE_SH)
    block = src[src.index('step "building the signed release APK"') : src.index("BUILT_APK=")]
    assert "--offline" in block
    assert "rerunning online" in block
    assert re.search(r"offline mode\|No cached version\|available for offline", block)


def test_web_bundle_is_built_with_npm_ci_first():
    src = _text(RELEASE_SH)
    block = src[
        src.index('step "rebuilding the web bundle"') : src.index('step "verifying everything"')
    ]
    assert re.search(r"npm ci\s*&&\s*npm run build", block)


@pytest.mark.parametrize(
    "path,cd_line",
    [
        (RELEASE_SH, 'cd "$(dirname "$0")" || exit 1'),
        (BUILD_WHEEL_SH, 'cd "$(dirname "$0")/server" || exit 1'),
        (SMOKE_WHEEL_SH, 'cd "$(dirname "$0")" || exit 1'),
        (BOOTSTRAP_SH, 'cd "$(dirname "$0")" || exit 1'),
        (VERIFY_ALL_SH, 'cd "$(dirname "$0")" || exit 1'),
    ],
)
def test_top_level_cd_is_guarded(path, cd_line):
    assert cd_line in _text(path), f"{path.name} does not guard its top-level cd"


@pytest.mark.parametrize(
    "subshell_cd", ["cd server || exit 1", "cd web || exit 1", "cd android || exit 1"]
)
def test_verify_all_subshell_cds_are_guarded(subshell_cd):
    src = _text(VERIFY_ALL_SH)
    assert subshell_cd in src, f"verify-all.sh has an unguarded '{subshell_cd.split(' ||')[0]}'"


def test_verify_all_has_no_remaining_bare_cd():
    """Every 'cd <dir>' at the start of a statement must short-circuit on failure, either with
    `|| exit` or as the left side of `&&` — a bare `cd` silently continues in the wrong
    directory (build-wheel.sh's next statement used to be `rm -rf build dist`)."""
    for path in (RELEASE_SH, BUILD_WHEEL_SH, SMOKE_WHEEL_SH, BOOTSTRAP_SH, VERIFY_ALL_SH):
        for lineno, line in enumerate(_text(path).splitlines(), 1):
            stripped = line.strip()
            if not re.match(r"^cd \S", stripped):
                continue
            assert (
                "||" in stripped or "&&" in stripped
            ), f"{path.name}:{lineno} has an unguarded bare cd: {stripped!r}"
