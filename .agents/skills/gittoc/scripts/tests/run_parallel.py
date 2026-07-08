#!/usr/bin/env python3
"""Run the gittoc test suite in parallel across processes (stdlib only).

The end-to-end tests are independent — each builds its own temporary git
repository under a fresh ``TemporaryDirectory`` and never touches global
state — so they can run concurrently. Each worker process loads the test
module once (via fork) and pulls test ids off a shared queue, so the only
overhead beyond the tests themselves is one interpreter per worker.

Usage:
    python3 scripts/tests/run_parallel.py            # auto: one worker per core
    python3 scripts/tests/run_parallel.py -j 4       # explicit worker count
    python3 scripts/tests/run_parallel.py -k pull    # only ids containing "pull"

Exits non-zero if any test fails or errors.
"""

from __future__ import annotations

import argparse
import io
import os
import sys
import time
import unittest
from concurrent.futures import ProcessPoolExecutor

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
MODULE = "scripts.tests.test_gittoc"


def _collect_ids(name: str) -> list[str]:
    """Return every test id reachable from a dotted module/class/method name."""
    suite = unittest.TestLoader().loadTestsFromName(name)
    ids: list[str] = []

    def walk(item: object) -> None:
        if isinstance(item, unittest.TestSuite):
            for child in item:
                walk(child)
        else:
            ids.append(item.id())  # type: ignore[attr-defined]

    walk(suite)
    return ids


def _run_one(test_id: str) -> tuple[str, bool, float, str]:
    """Run a single test id in this worker and return its result."""
    suite = unittest.TestLoader().loadTestsFromName(test_id)
    buffer = io.StringIO()
    start = time.perf_counter()
    result = unittest.TextTestRunner(stream=buffer, verbosity=0).run(suite)
    duration = time.perf_counter() - start
    return test_id, result.wasSuccessful(), duration, buffer.getvalue()


def _init_worker() -> None:
    if REPO_ROOT not in sys.path:
        sys.path.insert(0, REPO_ROOT)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "-j",
        "--jobs",
        type=int,
        default=os.cpu_count() or 1,
        help="number of worker processes (default: CPU count)",
    )
    parser.add_argument(
        "-k",
        "--filter",
        help="only run test ids containing this substring",
    )
    parser.add_argument(
        "target",
        nargs="?",
        default=MODULE,
        help=f"dotted module/class/method to run (default: {MODULE})",
    )
    args = parser.parse_args(argv)

    _init_worker()
    ids = _collect_ids(args.target)
    if args.filter:
        ids = [test_id for test_id in ids if args.filter in test_id]
    if not ids:
        print("no tests matched", file=sys.stderr)
        return 1

    start = time.perf_counter()
    failures: list[tuple[str, str]] = []
    completed = 0
    with ProcessPoolExecutor(max_workers=args.jobs, initializer=_init_worker) as pool:
        for test_id, ok, _duration, output in pool.map(_run_one, ids):
            completed += 1
            sys.stdout.write("." if ok else "F")
            sys.stdout.flush()
            if not ok:
                failures.append((test_id, output))
    elapsed = time.perf_counter() - start

    print()
    for test_id, output in failures:
        print(f"\n{'=' * 70}\nFAIL/ERROR: {test_id}\n{'-' * 70}\n{output}")
    print(
        f"\nRan {completed} tests in {elapsed:.1f}s "
        f"across {args.jobs} workers — "
        f"{'OK' if not failures else f'FAILED ({len(failures)})'}"
    )
    return 0 if not failures else 1


if __name__ == "__main__":
    raise SystemExit(main())
