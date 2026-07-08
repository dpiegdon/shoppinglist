"""ANSI terminal color helpers for gittoc output.

Color is applied only when the destination stream is a TTY (stdout for most
helpers, stderr for warn/error) and the NO_COLOR convention is not in effect.
All public functions return plain strings when color is disabled, so callers
need no conditional logic.
"""

from __future__ import annotations

import os
import sys

_RESET = "\033[0m"
_BOLD = "\033[1m"
_DIM = "\033[2m"
_DIM_INVERSE = "\033[30;100m"
_RED = "\033[31m"
_GREEN = "\033[32m"
_YELLOW = "\033[33m"
_CYAN = "\033[36m"
_MAGENTA = "\033[35m"
_BRIGHT_BLUE = "\033[94m"


def _color_enabled(stream) -> bool:
    """Return True if ANSI color should be emitted for *stream*.

    Honors the NO_COLOR convention (https://no-color.org/): any non-empty
    NO_COLOR disables color everywhere. Otherwise color is enabled only when
    the destination stream is a TTY.
    """
    if os.environ.get("NO_COLOR"):
        return False
    return stream.isatty()


def _c(text: str, *codes: str, stream=None) -> str:
    """Wrap text in ANSI codes when color is enabled for the target stream.

    Defaults to stdout; warn/error pass stderr so their color tracks the
    stream they are actually printed to, not stdout.
    """
    target = sys.stdout if stream is None else stream
    if not _color_enabled(target):
        return text
    return "".join(codes) + text + _RESET


def issue_id(text: str) -> str:
    return _c(text, _BOLD, _CYAN)


def priority(n: int) -> str:
    label = f"p{n}"
    if n == 1:
        return _c(label, _RED)
    if n == 2:
        return _c(label, _YELLOW)
    if n == 5:
        return _c(label, _DIM)
    return label


def state_marker(m: str) -> str:
    code = {">": _GREEN, "!": _YELLOW, "~": _RED, "x": _DIM}.get(m)
    return _c(m, code) if code else m


def state(s: str) -> str:
    label = f"[{s}]"
    code = {
        "claimed": _YELLOW,
        "blocked": _RED,
        "closed": _DIM,
        "rejected": _DIM_INVERSE,
    }.get(s)
    return _c(label, code) if code else label


def title(text: str) -> str:
    return _c(text, _BOLD)


def label(text: str) -> str:
    return _c(text, _CYAN)


def count(text: str) -> str:
    return _c(text, _YELLOW)


def timestamp(text: str) -> str:
    return _c(text, _DIM)


def event_label(text: str) -> str:
    """Color an event label: green for notes, magenta for other event kinds."""
    if text.startswith("note"):
        return _c(text, _GREEN)
    return _c(text, _MAGENTA)


def actor(text: str) -> str:
    return _c(text, _CYAN)


def deps(text: str) -> str:
    return _c(text, _RED)


def owner(text: str) -> str:
    return _c(text, _BRIGHT_BLUE)


def field_name(text: str) -> str:
    return _c(text, _DIM)


def ref(text: str) -> str:
    return _c(text, _DIM)


def ok(text: str) -> str:
    return _c(text, _GREEN)


def warn(text: str) -> str:
    return _c(text, _BOLD, _YELLOW, stream=sys.stderr)


def error(text: str) -> str:
    return _c(text, _BOLD, _RED, stream=sys.stderr)
