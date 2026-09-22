"""Structural tests for bootstrap.sh's Android SDK download (T-281).

bootstrap.sh --android-sdk downloads ~150MB from the network and unpacks it, so nothing here runs
it; these tests read its text, the same technique test_release_guard.py's
`test_release_sh_runs_the_guard_before_it_writes_anything` already uses.
"""

import re
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
BOOTSTRAP_SH = REPO_ROOT / "bootstrap.sh"

# The sha1 Google's own package XML (repository2-3.xml, cmdline-tools;12.0, linux archive) lists
# for commandlinetools-linux-11076708_latest.zip — confirmed independently against a real download
# of that file while writing this fix. If bootstrap.sh's URL ever moves to a newer build, this
# constant has to move with it, which is exactly what the second test below catches.
KNOWN_GOOD_SHA1 = "d313adb7aedccf6cf0cfca51ec180f0059f5f8f8"


def _text() -> str:
    return BOOTSTRAP_SH.read_text(encoding="utf-8")


def _sdk_block() -> str:
    src = _text()
    return src[src.index('step "android: SDK location"') : src.index('step "android: environment')]


def test_the_download_is_checksummed():
    block = _sdk_block()
    assert "sha1sum" in block
    assert "checksum mismatch" in block
    assert "exit 1" in block.split("checksum mismatch")[1][:200]


def test_the_pinned_checksum_is_the_one_google_publishes_for_this_exact_file():
    block = _sdk_block()
    match = re.search(r"CMDLINE_ZIP_SHA1=([0-9a-f]{40})", block)
    assert match, "no CMDLINE_ZIP_SHA1 constant found"
    assert match.group(1) == KNOWN_GOOD_SHA1


def test_curl_fails_the_script_on_an_http_error():
    # -f: curl otherwise writes the error page to the output file and exits 0.
    block = _sdk_block()
    curl_line = next(
        line for line in block.splitlines() if "curl" in line and "cmdline.zip" in line
    )
    assert " -f " in curl_line or curl_line.rstrip().endswith(" -f")


def test_unzip_failure_is_fatal_not_silently_continued():
    block = _sdk_block()
    # the old code was `unzip ... && rm -rf "$tmp"` with no else: on failure it fell straight
    # through to `mv`/`sdkmanager` against a directory that was never populated.
    assert re.search(r"unzip -q -o .* \|\| \{", block)
    assert not re.search(r"unzip -q -o [^\n]*&& rm -rf", block)
