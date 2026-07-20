"""Per-server-run boot id (T-107).

The registration override (server_settings) must reset on restart but stay
consistent across a multi-worker deployment, and a blueprint has no reliable
"server startup, once" hook (under a non-preload gunicorn, registration runs
once per worker; there is no distinguished first worker). So instead of trying
to detect startup, we tag the override with a boot id and compare it lazily at
read time — a mismatch means the override was set during a previous run and is
ignored (and cleared).

The boot id is the **master process's start-time**: every worker of one server
run shares the same master (its parent), so they all compute the same id, and it
changes on restart (a new master with a new start-time). No preload requirement,
no operator hook.
"""

import os
import uuid

# Used only when the master's start-time can't be read (non-Linux / no /proc, or
# a bare `flask run`): a per-process id, so the override still resets when this
# process restarts. Under a real multi-worker gunicorn on Linux the /proc path
# below is taken instead, which is the case that actually needs cross-worker
# agreement.
_FALLBACK_BOOT_ID = f"fallback:{uuid.uuid4().hex}"


def _start_time_ticks(pid: int) -> str:
    """Field 22 (starttime) of /proc/<pid>/stat — stable for the process's life.

    The comm field (2) is wrapped in parens and may itself contain spaces and
    parens, so split on the text after the final ')': field 3 (state) becomes
    index 0, making starttime index 19.
    """
    with open(f"/proc/{pid}/stat", "rb") as handle:
        data = handle.read()
    after_comm = data[data.rfind(b")") + 2 :]
    return after_comm.split()[19].decode("ascii")


def current_boot_id() -> str:
    ppid = os.getppid()
    try:
        return f"{ppid}:{_start_time_ticks(ppid)}"
    except (OSError, IndexError, ValueError):
        return _FALLBACK_BOOT_ID
