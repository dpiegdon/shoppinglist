"""Process-level lock that serializes tracker mutations.

Every writer shares one checkout of the tracker branch. Optimistic locking
(``Tracker.ensure_not_stale``) detects *committed* changes, but two processes
writing and committing at the same moment still collide inside git: index
and ref locks fail the loser with raw errors, and one writer's commit can
sweep up lines another writer appended to the same event log. A lock file in
the common git directory, held from before the auto-pull to after the commit,
removes that window. Readers never take it.
"""

from __future__ import annotations

import atexit
import os
import socket
import sys
import time
from pathlib import Path

from . import colors as col

LOCK_FILENAME = "gittoc.lock"
LOCK_POLL_SECONDS = 0.05
DEFAULT_TIMEOUT_SECONDS = 30.0
# A holder whose liveness cannot be checked (other host, or a platform
# without a safe probe) is presumed dead after this long.
STALE_AGE_SECONDS = 120.0


def lock_timeout() -> float:
    """Seconds to wait for the lock; ``GITTOC_LOCK_TIMEOUT`` overrides the default."""
    raw = os.environ.get("GITTOC_LOCK_TIMEOUT", "").strip()
    if not raw:
        return DEFAULT_TIMEOUT_SECONDS
    try:
        return max(0.0, float(raw))
    except ValueError:
        return DEFAULT_TIMEOUT_SECONDS


class MutationLock:
    """Exclusive lock file created with O_EXCL; released at exit."""

    def __init__(self, path: Path) -> None:
        self.path = path
        self.held = False

    def acquire(self) -> None:
        """Block until the lock is ours, breaking stale locks; exit on timeout."""
        deadline = time.monotonic() + lock_timeout()
        while True:
            try:
                fd = os.open(self.path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o644)
            except FileExistsError:
                if self._break_if_stale():
                    continue
                if time.monotonic() >= deadline:
                    raise SystemExit(
                        f"tracker is locked by {self._describe_holder()}; "
                        f"gave up after {lock_timeout():.0f}s. If that process is "
                        f"gone, remove {self.path} and retry"
                    )
                time.sleep(LOCK_POLL_SECONDS)
                continue
            with os.fdopen(fd, "w", encoding="utf-8") as handle:
                handle.write(
                    f"{os.getpid()} {socket.gethostname()} {int(time.time())}\n"
                )
            self.held = True
            atexit.register(self.release)
            return

    def release(self) -> None:
        """Remove the lock file if this process holds it (idempotent)."""
        if not self.held:
            return
        self.held = False
        try:
            self.path.unlink()
        except FileNotFoundError:
            pass

    def _holder(self) -> tuple[int | None, str]:
        """Return (pid, hostname) recorded in the lock file, best effort."""
        try:
            parts = self.path.read_text(encoding="utf-8").split()
        except OSError:
            return None, ""
        pid = int(parts[0]) if parts and parts[0].isdigit() else None
        host = parts[1] if len(parts) > 1 else ""
        return pid, host

    def _describe_holder(self) -> str:
        pid, host = self._holder()
        if pid is None:
            return "another process"
        return f"pid {pid} on {host or 'unknown host'}"

    def _break_if_stale(self) -> bool:
        """Remove the lock if its holder is provably dead or the file is ancient."""
        pid, host = self._holder()
        stale = False
        # os.kill(pid, 0) is a liveness probe only on POSIX; on Windows it
        # would terminate the process, so there we rely on the file's age.
        if os.name == "posix" and pid is not None and host == socket.gethostname():
            try:
                os.kill(pid, 0)
            except ProcessLookupError:
                stale = True
            except OSError:
                pass  # alive but not ours to signal
        if not stale:
            try:
                stale = time.time() - self.path.stat().st_mtime > STALE_AGE_SECONDS
            except FileNotFoundError:
                return True  # released between our attempts
        if not stale:
            return False
        print(
            col.warn(f"warning: removing stale tracker lock {self.path}"),
            file=sys.stderr,
        )
        try:
            self.path.unlink()
        except FileNotFoundError:
            pass
        return True
