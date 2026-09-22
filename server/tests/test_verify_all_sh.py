"""Structural tests for what verify-all.sh's gate actually runs (T-281).

The Android and wheel stages are slow (a Gradle build, a fresh venv install), so nothing here runs
verify-all.sh itself — these tests read its text, the same technique test_release_guard.py's
`test_release_sh_runs_the_guard_before_it_writes_anything` already uses. The stages this change
adds were run directly (`./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest :app:lintDebug
--offline`, and `./build-wheel.sh && ./smoke-wheel.sh`) while writing the fix, to confirm they pass
before wiring them into the gate.
"""

import re
from pathlib import Path

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


def test_the_wheel_is_built_and_smoke_tested_in_the_gate():
    src = _text()
    assert "wheel_check" in src
    assert "./build-wheel.sh" in src
    assert "./smoke-wheel.sh" in src
    assert re.search(r'run_stage "[^"]*wheel[^"]*"\s+wheel_check', src)


def test_web_stage_does_not_use_npx():
    """npx fetches from the registry when a binary is missing locally, which can silently run a
    version other than the one package-lock.json pins."""
    src = _text()
    web_check = src[src.index("web_check() (") : src.index("android_preflight() (")]
    assert not re.search(r"^\s*npx ", web_check, re.MULTILINE)
    assert "node_modules/.bin/vitest" in web_check
    assert "node_modules/.bin/tsc" in web_check
