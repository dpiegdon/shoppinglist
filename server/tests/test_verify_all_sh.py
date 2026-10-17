"""Tests for verify-all.sh, the gate (T-281, T-360).

The real stages are slow (a Gradle build, a fresh venv install), so two techniques are used. What
each stage runs is checked by reading the script's text, the same technique test_release_guard.py's
`test_release_sh_runs_the_guard_before_it_writes_anything` uses. How the stages are scheduled and
reported (serial or parallel, the summary, stopping on a failure) is checked by running a copy of
the script over stub tools, further down.
"""

import re
import subprocess
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parents[2]
VERIFY_ALL_SH = REPO_ROOT / "verify-all.sh"


def _text() -> str:
    return VERIFY_ALL_SH.read_text(encoding="utf-8")


def test_the_release_variant_unit_tests_run():
    """android/README.md notes that testReleaseUnitTest carries the proof the debug-only TLS
    bypass is absent from release; nothing ran it before this fix."""
    src = _text()
    android_check = src[
        src.index("android_check() (") : src.index("# `./verify-all.sh --preflight`")
    ]
    assert ":app:testReleaseUnitTest" in android_check
    # both the offline attempt and the online retry need it, not just one
    assert android_check.count(":app:testReleaseUnitTest") >= 2


def test_the_android_screenshots_are_compared():
    """The screenshot tests (T-325) only compare with their reference images when Roborazzi's
    verify task is in the build; a plain testDebugUnitTest runs them without comparing."""
    src = _text()
    android_check = src[
        src.index("android_check() (") : src.index("# `./verify-all.sh --preflight`")
    ]
    runs = [line for line in android_check.splitlines() if "./gradlew " in line]
    # both the offline attempt and the online retry
    assert len(runs) >= 2
    assert all(":shared:verifyRoborazziDebug" in line for line in runs)


def test_the_wheel_is_built_and_smoke_tested_in_the_gate():
    src = _text()
    assert "wheel_check" in src
    assert "./build-wheel.sh" in src
    assert "./smoke-wheel.sh" in src
    assert re.search(r'\["[^"]*wheel[^"]*"\]=wheel_check', src)


def test_web_stage_does_not_use_npx():
    """npx fetches from the registry when a binary is missing locally, which can silently run a
    version other than the one package-lock.json pins."""
    src = _text()
    web_check = src[src.index("web_check() (") : src.index("android_preflight() (")]
    assert not re.search(r"^\s*npx ", web_check, re.MULTILINE)
    assert "node_modules/.bin/vitest" in web_check
    assert "node_modules/.bin/tsc" in web_check


# ---- the runner itself, over stub tools (T-360) ------------------------------------------------
#
# A copy of verify-all.sh runs in a tree of stub tools: every program a stage calls (the venv's
# python and linters, node, npm, vitest, tsc, gradlew, the JDK, the wheel scripts) is a shell
# script that records its arguments and passes, or fails a second later when its name is in
# FAIL_STUBS, or first sleeps 30 s when it is in SLOW_STUBS. That runs the real scheduling, logging and summary code in about
# a second, without any of the real suites.

STAGE_NAMES = [
    "server (isort + black + ruff + ty)",
    "server (pytest)",
    "web (vitest + tsc + lint)",
    "server (wheel build + smoke test)",
    "android (preflight)",
    "android (test + lint)",
]

_STUB = """#!/bin/sh
name=$(basename "$0")
echo "$name $*" >> "$STUB_RECORD"
case "$name $1" in "python -c") exit 0 ;; esac
case " $SLOW_STUBS " in *" $name "*) sleep 30 ;; esac
case " $FAIL_STUBS " in *" $name "*) sleep 1; echo "stub $name failed on purpose"; exit 1 ;; esac
[ "$name" = java ] && echo "    sun.jnu.encoding = UTF-8"
exit 0
"""


@pytest.fixture
def stub_tree(tmp_path):
    root = tmp_path / "repo"
    root.mkdir()
    script = root / "verify-all.sh"
    script.write_text(_text(), encoding="utf-8")
    script.chmod(0o755)
    stubs = {
        "server/.venv/bin": ["python", "isort", "black", "ruff", "ty"],
        "web/node_modules/.bin": ["vitest", "tsc"],
        "bin": ["node", "npm"],
        "jdk/bin": ["java", "javac"],
        "android": ["gradlew"],
        ".": ["build-wheel.sh", "smoke-wheel.sh"],
    }
    for directory, names in stubs.items():
        (root / directory).mkdir(parents=True, exist_ok=True)
        for name in names:
            path = root / directory / name
            path.write_text(_STUB, encoding="utf-8")
            path.chmod(0o755)
    (root / "android" / "local.properties").write_text("sdk.dir=/nowhere\n", encoding="utf-8")
    return root


def _run(root, *args, fail="", slow="", mode=None):
    env = {
        "PATH": f"{root / 'bin'}:/usr/bin:/bin",
        "HOME": str(root),
        "JAVA_HOME": str(root / "jdk"),
        "TMPDIR": str(root),
        "STUB_RECORD": str(root / "record"),
        "FAIL_STUBS": fail,
        "SLOW_STUBS": slow,
    }
    if mode:
        env["VERIFY_ALL_MODE"] = mode
    result = subprocess.run(
        [str(root / "verify-all.sh"), *args],
        capture_output=True,
        text=True,
        env=env,
        timeout=60,
    )
    record = (root / "record").read_text() if (root / "record").exists() else ""
    return result, record


def _summary(stdout):
    """({stage: status}, [stages in the order printed]) from the summary at the end."""
    lines = stdout.split("verify-all summary")[-1].splitlines()
    rows = [m for m in (re.match(r"^  (PASS|FAIL|SKIP)  (.+)$", line) for line in lines) if m]
    return {m[2]: m[1] for m in rows}, [m[2] for m in rows]


@pytest.mark.parametrize("mode", ["serial", "parallel"])
def test_every_stage_passes_and_the_summary_lists_them_in_order(stub_tree, mode):
    result, _ = _run(stub_tree, f"--{mode}")
    assert result.returncode == 0, result.stdout + result.stderr
    statuses, order = _summary(result.stdout)
    assert order == STAGE_NAMES
    assert set(statuses.values()) == {"PASS"}
    assert result.stdout.rstrip().endswith("All checks passed.")
    assert f"verify-all: {mode}" in result.stdout


def test_parallel_stops_the_other_stages_and_prints_the_failed_log(stub_tree):
    """vitest fails while Gradle is still busy: the run ends without waiting for Gradle, reports
    the web stage FAIL with its log, and Android's test stage as not finished."""
    result, record = _run(stub_tree, "--parallel", fail="vitest", slow="gradlew")
    assert result.returncode == 1
    statuses, order = _summary(result.stdout)
    assert order == STAGE_NAMES
    assert statuses["web (vitest + tsc + lint)"] == "FAIL"
    assert statuses["android (preflight)"] == "PASS"
    assert statuses["android (test + lint)"] == "SKIP"
    assert [s for s in statuses.values() if s == "FAIL"] == ["FAIL"]
    assert "=== web (vitest + tsc + lint): FAILED" in result.stdout
    assert "stub vitest failed on purpose" in result.stdout
    assert "gradlew " in record  # Gradle had started; the 60 s timeout proves it was stopped


def test_serial_stops_at_the_first_failure(stub_tree):
    result, record = _run(stub_tree, "--serial", fail="python")
    assert result.returncode == 1
    statuses, _ = _summary(result.stdout)
    assert statuses == {
        "server (isort + black + ruff + ty)": "PASS",
        "server (pytest)": "FAIL",
        "web (vitest + tsc + lint)": "SKIP",
        "server (wheel build + smoke test)": "SKIP",
        "android (preflight)": "SKIP",
        "android (test + lint)": "SKIP",
    }
    assert "vitest" not in record and "gradlew" not in record


def test_the_mode_can_be_chosen_by_environment(stub_tree):
    result, record = _run(stub_tree, mode="serial")
    assert result.returncode == 0
    assert "verify-all: serial (chosen)" in result.stdout
    # serial: vitest keeps vite.config.ts's one file at a time; pytest takes every core
    assert re.search(r"^vitest run\s*$", record, re.MULTILINE)
    assert "-m pytest -q -n auto " in record

    (stub_tree / "record").unlink()
    result, record = _run(stub_tree, mode="parallel")
    assert result.returncode == 0
    assert "verify-all: parallel (chosen)" in result.stdout
    assert re.search(r"^vitest run --fileParallelism --maxWorkers=\d+$", record, re.MULTILINE)
    assert re.search(r"-m pytest -q -n \d+ ", record)


def test_an_unknown_argument_is_refused(stub_tree):
    result, record = _run(stub_tree, "--fast")
    assert result.returncode == 2
    assert "usage:" in result.stderr
    assert record == ""
