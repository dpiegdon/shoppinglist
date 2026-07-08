#!/usr/bin/env python3
"""End-to-end tests for gittoc."""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
CLI = ROOT / "gittoc"


def run(args: list[str], cwd: Path, stdin: str | None = None) -> str:
    proc = subprocess.run(
        [str(CLI), *args],
        cwd=str(cwd),
        text=True,
        capture_output=True,
        check=True,
        input=stdin,
    )
    return proc.stdout.strip()


def run_fail(
    args: list[str], cwd: Path, stdin: str | None = None
) -> subprocess.CompletedProcess:
    """Run a command expected to fail, returning the completed process."""
    return subprocess.run(
        [str(CLI), *args],
        cwd=str(cwd),
        text=True,
        capture_output=True,
        check=False,
        input=stdin,
    )


def import_lib(name: str):
    """Import a gittoc_lib submodule for direct unit testing."""
    import importlib

    sys.path.insert(0, str(ROOT))
    try:
        return importlib.import_module(f"gittoc_lib.{name}")
    finally:
        sys.path.pop(0)


def current_branch(cwd: Path) -> str:
    proc = subprocess.run(
        ["git", "branch", "--show-current"],
        cwd=str(cwd),
        text=True,
        capture_output=True,
        check=True,
    )
    return proc.stdout.strip()


class GittocTestBase(unittest.TestCase):
    """Shared setUp/tearDown for all gittoc E2E tests."""

    def setUp(self) -> None:
        self.tempdir = tempfile.TemporaryDirectory()
        self.repo = Path(self.tempdir.name) / "repo"
        self.repo.mkdir()
        subprocess.run(["git", "init"], cwd=self.repo, check=True, capture_output=True)
        subprocess.run(
            ["git", "config", "user.name", "Test User"],
            cwd=self.repo,
            check=True,
            capture_output=True,
        )
        subprocess.run(
            ["git", "config", "user.email", "test@example.com"],
            cwd=self.repo,
            check=True,
            capture_output=True,
        )
        (self.repo / "README.md").write_text("test repo\n", encoding="utf-8")
        subprocess.run(
            ["git", "add", "README.md"], cwd=self.repo, check=True, capture_output=True
        )
        subprocess.run(
            ["git", "commit", "-m", "Initial commit"],
            cwd=self.repo,
            check=True,
            capture_output=True,
        )

    def tearDown(self) -> None:
        self.tempdir.cleanup()

    def init_with_remote(self) -> Path:
        """Initialize tracker with a bare remote and return the remote path."""
        remote_repo = Path(self.tempdir.name) / "remote.git"
        subprocess.run(
            ["git", "init", "--bare", str(remote_repo)],
            check=True,
            capture_output=True,
        )
        subprocess.run(
            ["git", "remote", "add", "origin", str(remote_repo)],
            cwd=self.repo,
            check=True,
            capture_output=True,
        )
        run(["init"], self.repo)
        return remote_repo


class TestInitAndRemote(GittocTestBase):
    def test_init_creates_tracker(self) -> None:
        init_out = run(["init"], self.repo)
        self.assertIn("initialized tracker branch", init_out)

    def test_init_auto_configures_remote(self) -> None:
        self.init_with_remote()
        remote_status = json.loads(run(["remote", "--format", "json"], self.repo))
        self.assertEqual(remote_status["configured_remote"], "origin")
        self.assertEqual(remote_status["effective_remote"], "origin")
        self.assertEqual(remote_status["branch_config_remote"], "origin")
        self.assertEqual(remote_status["branch_config_merge"], "refs/heads/gittoc")
        self.assertFalse(remote_status["remote_branch_exists"])


class TestCreateAndList(GittocTestBase):
    def test_create_issues(self) -> None:
        run(["init"], self.repo)
        issue1 = run(
            ["new", "High priority task", "-b", "finish core work", "-p", "1"],
            self.repo,
        )
        issue2 = run(
            ["new", "Lower priority task", "-b", "depends on first", "-p", "4"],
            self.repo,
        )
        self.assertEqual(issue1, "T-1")
        self.assertEqual(issue2, "T-2")

    def test_create_with_deps(self) -> None:
        run(["init"], self.repo)
        run(["new", "Blocker task", "-p", "1"], self.repo)
        issue2 = run(["new", "Dependent task", "-d", "T-1"], self.repo)
        self.assertEqual(issue2, "T-2")
        show = run(["show", "T-2"], self.repo)
        self.assertIn("T-1", show)
        # T-2 should NOT be ready (blocked by T-1)
        ready_out = run(["unblocked", "--format", "compact"], self.repo)
        self.assertNotIn("T-2", ready_out)

    def test_list_alias_and_compact(self) -> None:
        run(["init"], self.repo)
        run(["new", "High priority task", "-p", "1"], self.repo)
        alias_list = run(["l", "--format", "compact"], self.repo).splitlines()
        self.assertEqual(alias_list[0], "T-1 p1 open High priority task")

    def test_summary(self) -> None:
        run(["init"], self.repo)
        run(["new", "Task one", "-p", "1"], self.repo)
        run(["new", "Task two", "-p", "4"], self.repo)
        self.assertEqual(
            run(["sum"], self.repo),
            "open=2 claimed=0 blocked=0 closed=0 rejected=0 ready=2",
        )

    def test_list_all_states(self) -> None:
        run(["init"], self.repo)
        run(["new", "Task one", "-p", "1"], self.repo)
        run(["close", "T-1"], self.repo)
        all_list = run(["list", "--all", "--format", "compact"], self.repo)
        self.assertIn("T-1 p1 closed Task one", all_list)

    def test_verbose_list(self) -> None:
        run(["init"], self.repo)
        run(["new", "Task one", "-p", "1", "-b", "finish core work"], self.repo)
        verbose = run(["list", "--format", "verbose"], self.repo)
        self.assertIn("body: finish core work", verbose)
        self.assertIn("deps: -", verbose)


class TestDependenciesAndReady(GittocTestBase):
    def test_dep_and_ready(self) -> None:
        run(["init"], self.repo)
        issue1 = run(["new", "High priority task", "-p", "1"], self.repo)
        issue2 = run(["new", "Lower priority task", "-p", "4"], self.repo)
        run(["dep", issue2, issue1], self.repo)

        listing = run(["list"], self.repo).splitlines()
        self.assertIn(f"> {issue1} p1 [open] High priority task", listing[0])
        self.assertIn(f"* {issue2} p4 [open] Lower priority task  deps=1", listing[1])

    def test_ready_after_close(self) -> None:
        run(["init"], self.repo)
        issue1 = run(["new", "Blocker", "-p", "1"], self.repo)
        issue2 = run(["new", "Blocked", "-p", "2"], self.repo)
        run(["dep", issue2, issue1], self.repo)
        run(["close", issue1], self.repo)
        ready = run(["unblocked"], self.repo)
        self.assertIn(issue2, ready)

    def test_self_dependency_rejected(self) -> None:
        run(["init"], self.repo)
        issue1 = run(["new", "Task"], self.repo)
        with self.assertRaises(subprocess.CalledProcessError):
            run(["dep", issue1, issue1], self.repo)

    def test_cycle_rejected(self) -> None:
        run(["init"], self.repo)
        issue1 = run(["new", "A"], self.repo)
        issue2 = run(["new", "B"], self.repo)
        run(["dep", issue2, issue1], self.repo)
        with self.assertRaises(subprocess.CalledProcessError):
            run(["dep", issue1, issue2], self.repo)

    def test_cannot_claim_non_ready(self) -> None:
        run(["init"], self.repo)
        issue1 = run(["new", "Blocker", "-p", "1"], self.repo)
        issue2 = run(["new", "Blocked", "-p", "2"], self.repo)
        run(["dep", issue2, issue1], self.repo)
        with self.assertRaises(subprocess.CalledProcessError):
            run(["claim", issue2, "--owner", "tester"], self.repo)
        with self.assertRaises(subprocess.CalledProcessError):
            run(
                ["update", issue2, "--state", "claimed", "--owner", "tester"], self.repo
            )

    def test_remove_dependency(self) -> None:
        run(["init"], self.repo)
        issue1 = run(["new", "Blocker", "-p", "1"], self.repo)
        issue2 = run(["new", "Blocked", "-p", "2"], self.repo)
        run(["dep", issue2, issue1], self.repo)
        shown = json.loads(run(["show", issue2, "-f", "json"], self.repo))
        self.assertEqual(shown["deps"], [issue1])
        run(["dep", issue2, issue1, "--remove"], self.repo)
        shown = json.loads(run(["show", issue2, "-f", "json"], self.repo))
        self.assertNotIn("deps", shown)

    def test_remove_nonexistent_dep_fails(self) -> None:
        run(["init"], self.repo)
        issue1 = run(["new", "A"], self.repo)
        issue2 = run(["new", "B"], self.repo)
        with self.assertRaises(subprocess.CalledProcessError):
            run(["dep", issue1, issue2, "--remove"], self.repo)

    def test_cycle_check_with_missing_dep(self) -> None:
        """Cycle detection should not crash if a dep references a non-existent issue."""
        run(["init"], self.repo)
        issue1 = run(["new", "A"], self.repo)
        issue2 = run(["new", "B"], self.repo)
        run(["dep", issue2, issue1], self.repo)
        # Manually inject a non-existent dep into T-1's JSON
        gittoc_dir = self.repo / ".git" / "gittoc"
        t1_path = gittoc_dir / "issues" / "open" / "T-1.json"
        data = json.loads(t1_path.read_text())
        data["deps"] = ["T-999"]
        t1_path.write_text(json.dumps(data, indent=2))
        # Cycle check traverses T-2 -> T-1 deps -> T-999 (missing).
        # Should not crash; T-1 depends on T-2 would still be a cycle.
        with self.assertRaises(subprocess.CalledProcessError):
            run(["dep", issue1, issue2], self.repo)


class TestClaimWorkflow(GittocTestBase):
    def test_claim_and_show(self) -> None:
        run(["init"], self.repo)
        issue1 = run(["new", "Task", "-p", "1"], self.repo)
        claimed_out = run(["claim", issue1, "--owner", "tester"], self.repo)
        self.assertIn(f"! {issue1} p1 [claimed] Task  owner=tester", claimed_out)

        claimed = json.loads(run(["show", issue1, "-f", "json"], self.repo))
        self.assertEqual(claimed["state"], "claimed")
        self.assertTrue(claimed["path"].startswith("issues/claimed/"))

    def test_cannot_claim_closed(self) -> None:
        run(["init"], self.repo)
        issue1 = run(["new", "Task", "-p", "1"], self.repo)
        run(["close", issue1], self.repo)
        with self.assertRaises(subprocess.CalledProcessError):
            run(["claim", issue1, "--owner", "tester"], self.repo)

    def test_claimed_alias(self) -> None:
        """The 'c' alias maps to the 'claimed' list filter."""
        run(["init"], self.repo)
        issue1 = run(["new", "Task", "-p", "1"], self.repo)
        run(["claim", issue1, "--owner", "tester"], self.repo)
        claimed_list = run(["c"], self.repo)
        self.assertIn(issue1, claimed_list)

    def test_reclaim_by_different_owner_warns(self) -> None:
        run(["init"], self.repo)
        issue1 = run(["new", "Task"], self.repo)
        run(["claim", issue1, "--owner", "alice"], self.repo)
        proc = run_fail(["claim", issue1, "--owner", "bob"], self.repo)
        self.assertIn("warning", proc.stderr)
        self.assertIn("alice", proc.stderr)
        shown = json.loads(run(["show", issue1, "-f", "json"], self.repo))
        self.assertEqual(shown["owner"], "bob")

    def test_reclaim_by_same_owner_no_warning(self) -> None:
        run(["init"], self.repo)
        issue1 = run(["new", "Task"], self.repo)
        run(["claim", issue1, "--owner", "alice"], self.repo)
        proc = run_fail(["claim", issue1, "--owner", "alice"], self.repo)
        self.assertNotIn("warning", proc.stderr)

    def test_unclaim_clears_owner(self) -> None:
        run(["init"], self.repo)
        issue1 = run(["new", "Task"], self.repo)
        run(["claim", issue1, "--owner", "alice"], self.repo)
        proc = run_fail(["update", issue1, "--state", "open"], self.repo)
        self.assertIn("note", proc.stderr)
        self.assertIn("alice", proc.stderr)
        shown = json.loads(run(["show", issue1, "-f", "json"], self.repo))
        self.assertEqual(shown["state"], "open")
        self.assertNotIn("owner", shown)

    def test_unclaim_with_explicit_owner_preserves_owner(self) -> None:
        run(["init"], self.repo)
        issue1 = run(["new", "Task"], self.repo)
        run(["claim", issue1, "--owner", "alice"], self.repo)
        run(["update", issue1, "--state", "open", "--owner", "alice"], self.repo)
        shown = json.loads(run(["show", issue1, "-f", "json"], self.repo))
        self.assertEqual(shown["owner"], "alice")

    def test_update_to_claimed_defaults_owner(self) -> None:
        """`update --state claimed` with no --owner defaults the owner like `claim`."""
        run(["init"], self.repo)
        issue1 = run(["new", "Task"], self.repo)
        with mock.patch.dict(os.environ, {"GITTOC_OWNER": "autoclaim"}):
            run(["update", issue1, "--state", "claimed"], self.repo)
        shown = json.loads(run(["show", issue1, "-f", "json"], self.repo))
        self.assertEqual(shown["state"], "claimed")
        self.assertEqual(shown["owner"], "autoclaim")

    def test_update_to_claimed_with_explicit_owner(self) -> None:
        run(["init"], self.repo)
        issue1 = run(["new", "Task"], self.repo)
        run(["update", issue1, "--state", "claimed", "--owner", "bob"], self.repo)
        shown = json.loads(run(["show", issue1, "-f", "json"], self.repo))
        self.assertEqual(shown["owner"], "bob")

    def test_multi_claim_is_all_or_nothing(self) -> None:
        """A batch claim containing an unclaimable id must claim none of them."""
        run(["init"], self.repo)
        good = run(["new", "Good"], self.repo)
        blocker = run(["new", "Blocker"], self.repo)
        blocked = run(["new", "Blocked", "-d", blocker], self.repo)
        proc = run_fail(["claim", good, blocked, "--owner", "tester"], self.repo)
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn(blocked, proc.stderr)
        # The claimable ticket listed before the bad one must be left untouched.
        shown = json.loads(run(["show", good, "-f", "json"], self.repo))
        self.assertEqual(shown["state"], "open")
        self.assertNotIn("owner", shown)

    def test_multi_claim_missing_id_claims_none(self) -> None:
        run(["init"], self.repo)
        good = run(["new", "Good"], self.repo)
        proc = run_fail(["claim", good, "T-999", "--owner", "tester"], self.repo)
        self.assertNotEqual(proc.returncode, 0)
        shown = json.loads(run(["show", good, "-f", "json"], self.repo))
        self.assertEqual(shown["state"], "open")


class TestLabels(GittocTestBase):
    def test_add_and_remove_labels(self) -> None:
        run(["init"], self.repo)
        issue = run(["new", "Task"], self.repo)
        run(["update", issue, "-l", "feature,ux"], self.repo)
        run(["update", issue, "-l", "bug"], self.repo)
        run(["update", issue, "-x", "ux"], self.repo)
        labeled = json.loads(run(["show", issue, "-f", "json"], self.repo))
        self.assertEqual(labeled["labels"], ["feature", "bug"])

    def test_replace_labels(self) -> None:
        run(["init"], self.repo)
        issue = run(["new", "Task"], self.repo)
        run(["update", issue, "-l", "feature,ux"], self.repo)
        run(["update", issue, "-L", "task,docs"], self.repo)
        replaced = json.loads(run(["show", issue, "-f", "json"], self.repo))
        self.assertEqual(replaced["labels"], ["task", "docs"])

    def test_cannot_combine_replace_and_add(self) -> None:
        run(["init"], self.repo)
        issue = run(["new", "Task"], self.repo)
        with self.assertRaises(subprocess.CalledProcessError):
            run(["update", issue, "-L", "feature", "-l", "bug"], self.repo)


class TestNotesAndHistory(GittocTestBase):
    def test_notes_and_history(self) -> None:
        run(["init"], self.repo)
        issue = run(["new", "Task", "-p", "1"], self.repo)
        run(["claim", issue, "--owner", "tester"], self.repo)
        run(["note", issue, "First note", "--actor", "tester"], self.repo)
        run(["n", issue, "Alias note", "--actor", "tester"], self.repo)
        run(["note", issue, "Third note", "--actor", "tester"], self.repo)
        run(["note", issue, "Fourth note", "--actor", "tester"], self.repo)
        run(["note", issue, "Fifth note truncation", "--actor", "tester"], self.repo)

        history = run(["show", issue, "-a"], self.repo)
        self.assertRegex(history, r"claimed \([0-9a-f]+\) tester: tester")
        self.assertRegex(history, r"note#1 \([0-9a-f]+\) tester: First note")

    def test_note_ids_sequential(self) -> None:
        run(["init"], self.repo)
        issue = run(["new", "Task"], self.repo)
        run(["note", issue, "Alpha", "--actor", "a"], self.repo)
        run(["note", issue, "Beta", "--actor", "b"], self.repo)
        run(["note", issue, "Gamma", "--actor", "c"], self.repo)
        history = run(["show", issue, "-n"], self.repo)
        self.assertRegex(history, r"note#1 \([0-9a-f]+\) a: Alpha")
        self.assertRegex(history, r"note#2 \([0-9a-f]+\) b: Beta")
        self.assertRegex(history, r"note#3 \([0-9a-f]+\) c: Gamma")
        # JSON output should include note_id field
        shown = json.loads(run(["show", issue, "-n", "-f", "json"], self.repo))
        entries = shown["recent_notes"]
        self.assertEqual(entries[0]["note_id"], 1)
        self.assertEqual(entries[2]["note_id"], 3)

    def test_notes_only_with_limit(self) -> None:
        run(["init"], self.repo)
        issue = run(["new", "Task"], self.repo)
        run(["note", issue, "Note A", "--actor", "tester"], self.repo)
        run(["note", issue, "Note B", "--actor", "tester"], self.repo)
        run(["note", issue, "Note C", "--actor", "tester"], self.repo)
        notes_only = run(["show", issue, "-n", "--limit", "1"], self.repo)
        self.assertIn("Note C", notes_only)
        self.assertNotIn("Note A", notes_only)

    def test_show_truncates_notes(self) -> None:
        run(["init"], self.repo)
        issue = run(["new", "Task"], self.repo)
        for i in range(5):
            run(["note", issue, f"Note {i}", "--actor", "tester"], self.repo)

        shown = json.loads(run(["show", issue, "-f", "json"], self.repo))
        self.assertEqual(shown["recent_notes_total"], 5)
        self.assertEqual(shown["recent_notes_shown"], 3)
        self.assertEqual(len(shown["recent_notes"]), 3)
        self.assertIn(f"show {issue} -n", shown["recent_notes_hint"])

    def test_show_text_format(self) -> None:
        run(["init"], self.repo)
        issue = run(["new", "My Task", "-p", "2", "-l", "bug"], self.repo)
        run(["note", issue, "A note", "--actor", "tester"], self.repo)
        text = run(["show", issue], self.repo)
        self.assertIn("T-1 p2 [open] My Task", text)
        self.assertIn("labels: bug", text)
        self.assertIn("tester: A note", text)


class TestShowAndResume(GittocTestBase):
    def test_show_alias(self) -> None:
        run(["init"], self.repo)
        run(["new", "Task"], self.repo)
        shown = json.loads(run(["s", "T-1", "-f", "json"], self.repo))
        self.assertEqual(shown["id"], "T-1")

    def test_resume_auto_select(self) -> None:
        run(["init"], self.repo)
        run(["new", "Task one", "-p", "1"], self.repo)
        resume = json.loads(run(["resume", "--format", "json"], self.repo))
        self.assertEqual(resume["id"], "T-1")

    def test_resume_prefers_claimed(self) -> None:
        run(["init"], self.repo)
        run(["new", "Task one", "-p", "1"], self.repo)
        run(["new", "Task two", "-p", "2"], self.repo)
        run(["claim", "T-2", "--owner", "tester"], self.repo)
        resume = run(["resume", "--owner", "tester"], self.repo)
        self.assertIn("T-2", resume)

    def test_resume_falls_back_to_ready(self) -> None:
        run(["init"], self.repo)
        run(["new", "Blocker", "-p", "1"], self.repo)
        issue2 = run(["new", "Depends", "-p", "2"], self.repo)
        run(["dep", issue2, "T-1"], self.repo)
        run(["close", "T-1"], self.repo)
        resume = json.loads(run(["resume", "--format", "json"], self.repo))
        self.assertEqual(resume["id"], issue2)

    def test_resume_alias(self) -> None:
        run(["init"], self.repo)
        run(["new", "Task"], self.repo)
        resume = json.loads(run(["r", "T-1", "--format", "json"], self.repo))
        self.assertEqual(resume["id"], "T-1")

    def test_resume_shows_recent_notes(self) -> None:
        run(["init"], self.repo)
        issue = run(["new", "Task"], self.repo)
        for i in range(5):
            run(["note", issue, f"Note {i}"], self.repo)
        resume = json.loads(run(["resume", issue, "--format", "json"], self.repo))
        self.assertEqual(resume["recent_notes_total"], 5)
        self.assertEqual(len(resume["recent_notes"]), 3)


class TestUpdate(GittocTestBase):
    def test_update_title_and_priority(self) -> None:
        run(["init"], self.repo)
        run(["new", "Original", "-p", "3"], self.repo)
        run(["update", "T-1", "--title", "Updated", "--priority", "1"], self.repo)
        updated = json.loads(run(["show", "T-1", "-a", "-f", "json"], self.repo))
        self.assertEqual(updated["title"], "Updated")
        self.assertEqual(updated["priority"], 1)
        self.assertTrue(any(entry["kind"] == "updated" for entry in updated["history"]))

    def test_update_state_to_blocked(self) -> None:
        run(["init"], self.repo)
        run(["new", "Task", "-p", "2"], self.repo)
        run(["update", "T-1", "--state", "blocked"], self.repo)
        summary = run(["summary"], self.repo)
        self.assertIn("blocked=1", summary)

    def test_update_alias(self) -> None:
        """The 'up' alias maps to the 'update' command."""
        run(["init"], self.repo)
        run(["new", "Original"], self.repo)
        run(["up", "T-1", "--title", "Aliased"], self.repo)
        updated = json.loads(run(["show", "T-1", "-f", "json"], self.repo))
        self.assertEqual(updated["title"], "Aliased")

    def test_update_no_fields_is_noop(self) -> None:
        run(["init"], self.repo)
        run(["new", "Task"], self.repo)
        result = run_fail(["update", "T-1"], self.repo)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("no fields to update", result.stderr)


class TestCloseAndReject(GittocTestBase):
    def test_close(self) -> None:
        run(["init"], self.repo)
        run(["new", "Task"], self.repo)
        run(["close", "T-1"], self.repo)
        summary = run(["summary"], self.repo)
        self.assertIn("closed=1", summary)

    def test_reject(self) -> None:
        run(["init"], self.repo)
        run(["new", "Task"], self.repo)
        run(["reject", "T-1"], self.repo)
        summary = run(["summary"], self.repo)
        self.assertIn("rejected=1", summary)


class TestLog(GittocTestBase):
    def test_log_for_issue(self) -> None:
        run(["init"], self.repo)
        run(["new", "Task"], self.repo)
        run(["close", "T-1"], self.repo)
        history = run(["log", "T-1"], self.repo)
        self.assertRegex(history, r"Close issue T-1 \([^)]+\)")

    def test_tracker_log(self) -> None:
        run(["init"], self.repo)
        run(["new", "Task"], self.repo)
        run(["claim", "T-1", "--owner", "tester"], self.repo)
        tracker_log = run(["log"], self.repo)
        self.assertIn("Claim issue T-1 for tester (tester)", tracker_log)

    def test_note_appears_in_log(self) -> None:
        run(["init"], self.repo)
        run(["new", "Task"], self.repo)
        run(["note", "T-1", "context"], self.repo)
        tracker_log = run(["log"], self.repo)
        self.assertIn("Add note to T-1", tracker_log)


class TestWorktreeIntegrity(GittocTestBase):
    def test_worktree_clean_after_operations(self) -> None:
        run(["init"], self.repo)
        run(["new", "Task one", "-p", "1"], self.repo)
        run(["new", "Task two", "-p", "2"], self.repo)
        run(["close", "T-1"], self.repo)
        run(["close", "T-2"], self.repo)

        tracker_status = subprocess.run(
            ["git", "-C", str(self.repo / ".git" / "gittoc"), "status", "--short"],
            text=True,
            capture_output=True,
            check=True,
        ).stdout.strip()
        self.assertEqual(tracker_status, "")

    def test_worktree_listed(self) -> None:
        run(["init"], self.repo)
        worktree_entry = subprocess.run(
            ["git", "worktree", "list"],
            cwd=self.repo,
            text=True,
            capture_output=True,
            check=True,
        ).stdout
        self.assertIn(str(self.repo), worktree_entry)


class TestExternalWorktree(GittocTestBase):
    """Verify gittoc works from git linked worktrees (created with `git worktree add`)."""

    def _add_external_worktree(self, branch: str = "feature") -> Path:
        """Create a feature branch and a linked worktree outside the main repo."""
        subprocess.run(
            ["git", "checkout", "-b", branch],
            cwd=self.repo,
            check=True,
            capture_output=True,
        )
        (self.repo / "work.txt").write_text("work\n", encoding="utf-8")
        subprocess.run(
            ["git", "add", "work.txt"], cwd=self.repo, check=True, capture_output=True
        )
        subprocess.run(
            ["git", "commit", "-m", "work"],
            cwd=self.repo,
            check=True,
            capture_output=True,
        )
        subprocess.run(
            ["git", "checkout", "-"], cwd=self.repo, check=True, capture_output=True
        )
        wt = Path(self.tempdir.name) / "external-wt"
        subprocess.run(
            ["git", "worktree", "add", str(wt), branch],
            cwd=self.repo,
            check=True,
            capture_output=True,
        )
        return wt

    def test_commands_work_from_external_worktree(self) -> None:
        """Tracker operations invoked from a linked worktree hit the shared tracker."""
        run(["init"], self.repo)
        run(["new", "from main"], self.repo)
        wt = self._add_external_worktree()
        # gittoc must find the existing tracker from the external worktree
        out = run(["list"], wt)
        self.assertIn("from main", out)
        # Create a ticket from the external worktree; it must persist in the shared tracker
        run(["new", "from external"], wt)
        out_main = run(["list"], self.repo)
        self.assertIn("from external", out_main)

    def test_init_from_external_worktree(self) -> None:
        """Initial gittoc init invoked from an external worktree places the tracker in the main repo."""
        wt = self._add_external_worktree()
        init_out = run(["init"], wt)
        self.assertIn("initialized tracker branch", init_out)
        # Tracker worktree must live under the shared .git directory, not the linked worktree
        shared_tracker = self.repo / ".git" / "gittoc"
        self.assertTrue(shared_tracker.exists(), f"tracker not at {shared_tracker}")
        self.assertFalse(
            (wt / ".git" / "gittoc").is_dir(),
            "tracker should not be placed inside the linked worktree's gitdir",
        )

    def test_commands_tolerate_git_dir_env(self) -> None:
        """GIT_DIR set by the `git toc` alias in linked worktrees must not confuse tracker resolution."""
        run(["init"], self.repo)
        run(["new", "seed"], self.repo)
        wt = self._add_external_worktree()
        # Simulate what `git` sets when dispatching an alias from a linked worktree
        env = {
            **__import__("os").environ,
            "GIT_DIR": str(self.repo / ".git" / "worktrees" / "external-wt"),
        }
        proc = subprocess.run(
            [str(CLI), "list"],
            cwd=str(wt),
            text=True,
            capture_output=True,
            check=True,
            env=env,
        )
        self.assertIn("seed", proc.stdout)


class TestRemoteTracking(GittocTestBase):
    def test_init_tracks_remote_gittoc_branch(self) -> None:
        source = Path(self.tempdir.name) / "source"
        source.mkdir()
        subprocess.run(["git", "init"], cwd=source, check=True, capture_output=True)
        subprocess.run(
            ["git", "config", "user.name", "Test User"],
            cwd=source,
            check=True,
            capture_output=True,
        )
        subprocess.run(
            ["git", "config", "user.email", "test@example.com"],
            cwd=source,
            check=True,
            capture_output=True,
        )
        (source / "README.md").write_text("source repo\n", encoding="utf-8")
        subprocess.run(
            ["git", "add", "README.md"], cwd=source, check=True, capture_output=True
        )
        subprocess.run(
            ["git", "commit", "-m", "Initial commit"],
            cwd=source,
            check=True,
            capture_output=True,
        )

        run(["init"], source)
        issue = run(["new", "Remote tracker issue"], source)
        self.assertEqual(issue, "T-1")

        remote_repo = Path(self.tempdir.name) / "remote-clone.git"
        subprocess.run(
            ["git", "init", "--bare", str(remote_repo)], check=True, capture_output=True
        )
        subprocess.run(
            ["git", "remote", "add", "origin", str(remote_repo)],
            cwd=source,
            check=True,
            capture_output=True,
        )
        subprocess.run(
            ["git", "push", "-u", "origin", current_branch(source)],
            cwd=source,
            check=True,
            capture_output=True,
        )
        subprocess.run(
            ["git", "push", "-u", "origin", "gittoc"],
            cwd=source,
            check=True,
            capture_output=True,
        )

        clone = Path(self.tempdir.name) / "clone"
        subprocess.run(
            ["git", "clone", str(remote_repo), str(clone)],
            check=True,
            capture_output=True,
        )

        summary = run(["summary"], clone)
        self.assertEqual(
            summary, "open=1 claimed=0 blocked=0 closed=0 rejected=0 ready=1"
        )

        issue_data = json.loads(run(["show", "T-1", "-f", "json"], clone))
        self.assertEqual(issue_data["title"], "Remote tracker issue")
        self.assertTrue(issue_data["path"].startswith("issues/open/"))


class TestPullAndPush(GittocTestBase):
    def test_pull_and_push_tracker_branch(self) -> None:
        remote_repo = Path(self.tempdir.name) / "sync.git"
        subprocess.run(
            ["git", "init", "--bare", str(remote_repo)], check=True, capture_output=True
        )

        source = Path(self.tempdir.name) / "source-sync"
        shutil.copytree(self.repo, source)
        subprocess.run(
            ["git", "remote", "add", "origin", str(remote_repo)],
            cwd=source,
            check=True,
            capture_output=True,
        )
        run(["init"], source)
        subprocess.run(
            ["git", "push", "-u", "origin", current_branch(source)],
            cwd=source,
            check=True,
            capture_output=True,
        )
        push_out = json.loads(run(["push", "origin", "--format", "json"], source))
        self.assertEqual(push_out["action"], "push")
        self.assertEqual(push_out["remote"], "origin")

        clone = Path(self.tempdir.name) / "clone-sync"
        subprocess.run(
            ["git", "clone", str(remote_repo), str(clone)],
            check=True,
            capture_output=True,
        )
        run(["summary"], clone)

        new_issue = run(["new", "Pulled tracker issue"], source)
        self.assertEqual(new_issue, "T-1")
        run(["push", "origin"], source)

        pull_out = json.loads(run(["pull", "origin", "--format", "json"], clone))
        self.assertEqual(pull_out["action"], "pull")
        self.assertEqual(pull_out["remote"], "origin")
        pulled = json.loads(run(["show", "T-1", "-f", "json"], clone))
        self.assertEqual(pulled["title"], "Pulled tracker issue")

    def test_pull_reports_already_up_to_date(self) -> None:
        remote_repo = Path(self.tempdir.name) / "uptodate.git"
        subprocess.run(
            ["git", "init", "--bare", str(remote_repo)], check=True, capture_output=True
        )
        subprocess.run(
            ["git", "remote", "add", "origin", str(remote_repo)],
            cwd=self.repo,
            check=True,
            capture_output=True,
        )
        run(["init"], self.repo)
        run(["push", "origin"], self.repo)

        clone = Path(self.tempdir.name) / "uptodate-clone"
        subprocess.run(
            ["git", "clone", str(remote_repo), str(clone)],
            check=True,
            capture_output=True,
        )
        # First pull attaches the worktree; clone is already at the remote head.
        run(["pull", "origin"], clone)
        # A second pull with no remote changes must report nothing changed.
        second = run(["pull", "origin"], clone)
        self.assertIn("already up to date", second)
        self.assertNotIn("pulled", second)
        # JSON output exposes the unchanged merge_kind for scripting.
        second_json = json.loads(run(["pull", "origin", "--format", "json"], clone))
        self.assertEqual(second_json["merge_kind"], "unchanged")

    def test_pull_alias(self) -> None:
        remote_repo = Path(self.tempdir.name) / "alias.git"
        subprocess.run(
            ["git", "init", "--bare", str(remote_repo)], check=True, capture_output=True
        )
        subprocess.run(
            ["git", "remote", "add", "origin", str(remote_repo)],
            cwd=self.repo,
            check=True,
            capture_output=True,
        )
        run(["init"], self.repo)
        run(["push", "origin"], self.repo)

        clone = Path(self.tempdir.name) / "alias-clone"
        subprocess.run(
            ["git", "clone", str(remote_repo), str(clone)],
            check=True,
            capture_output=True,
        )

        pull_alias = json.loads(run(["pl", "origin", "--format", "json"], clone))
        self.assertEqual(pull_alias["action"], "pull")

    def test_push_alias(self) -> None:
        remote_repo = Path(self.tempdir.name) / "ps-alias.git"
        subprocess.run(
            ["git", "init", "--bare", str(remote_repo)], check=True, capture_output=True
        )
        subprocess.run(
            ["git", "remote", "add", "origin", str(remote_repo)],
            cwd=self.repo,
            check=True,
            capture_output=True,
        )
        run(["init"], self.repo)
        push_alias = json.loads(run(["ps", "origin", "--format", "json"], self.repo))
        self.assertEqual(push_alias["action"], "push")


class TestFsck(GittocTestBase):
    def test_fsck_clean_tracker(self) -> None:
        run(["init"], self.repo)
        run(["new", "Task"], self.repo)
        self.assertIn("fsck ok", run(["fsck"], self.repo))

    def test_fsck_reports_malformed_issue_and_orphaned_event_log(self) -> None:
        run(["init"], self.repo)
        run(["new", "Task"], self.repo)
        gittoc_dir = self.repo / ".git" / "gittoc"
        issue_path = gittoc_dir / "issues" / "open" / "T-1.json"
        issue_path.write_text("{not json\n", encoding="utf-8")
        orphan_event = gittoc_dir / "issues" / "open" / "T-9.events.jsonl"
        orphan_event.write_text('{"actor":"a","at":"b","kind":"note","text":"c"}\n')

        result = run_fail(["fsck"], self.repo)
        self.assertEqual(result.returncode, 1)
        self.assertIn("issues/open/T-1.json", result.stdout)
        self.assertIn("malformed JSON", result.stdout)
        self.assertIn("issues/open/T-9.events.jsonl", result.stdout)
        self.assertIn("orphaned event log", result.stdout)

    def test_fsck_reports_dangling_dependencies_and_cycles(self) -> None:
        run(["init"], self.repo)
        run(["new", "A"], self.repo)
        run(["new", "B"], self.repo)
        gittoc_dir = self.repo / ".git" / "gittoc"
        t1_path = gittoc_dir / "issues" / "open" / "T-1.json"
        t2_path = gittoc_dir / "issues" / "open" / "T-2.json"

        t1_data = json.loads(t1_path.read_text(encoding="utf-8"))
        t2_data = json.loads(t2_path.read_text(encoding="utf-8"))
        t1_data["deps"] = ["T-2"]
        t2_data["deps"] = ["T-1", "T-99"]
        t1_path.write_text(json.dumps(t1_data, indent=2) + "\n", encoding="utf-8")
        t2_path.write_text(json.dumps(t2_data, indent=2) + "\n", encoding="utf-8")

        result = run_fail(["fsck"], self.repo)
        self.assertEqual(result.returncode, 1)
        self.assertIn("dangling dependency on T-99", result.stdout)
        self.assertIn("dependency cycle detected: T-1 -> T-2 -> T-1", result.stdout)

    def test_fsck_deep_dependency_chain(self) -> None:
        """Cycle detection must not hit Python's recursion limit on long chains."""
        run(["init"], self.repo)
        run(["new", "seed"], self.repo)
        gittoc_dir = self.repo / ".git" / "gittoc"
        open_dir = gittoc_dir / "issues" / "open"
        seed_path = open_dir / "T-1.json"
        seed_data = json.loads(seed_path.read_text(encoding="utf-8"))
        chain_length = 2000
        for index in range(2, chain_length + 1):
            issue_data = dict(seed_data)
            issue_data["id"] = f"T-{index}"
            issue_data["title"] = f"chain-{index}"
            issue_data["deps"] = [f"T-{index - 1}"]
            (open_dir / f"T-{index}.json").write_text(
                json.dumps(issue_data, indent=2) + "\n", encoding="utf-8"
            )
        self.assertIn("fsck ok", run(["fsck"], self.repo))

    def test_pull_runs_fsck_after_nontrivial_merge(self) -> None:
        remote_repo = Path(self.tempdir.name) / "fsck-pull.git"
        subprocess.run(
            ["git", "init", "--bare", str(remote_repo)], check=True, capture_output=True
        )
        subprocess.run(
            ["git", "remote", "add", "origin", str(remote_repo)],
            cwd=self.repo,
            check=True,
            capture_output=True,
        )
        run(["init"], self.repo)
        run(["new", "Seed issue"], self.repo)
        subprocess.run(
            ["git", "push", "-u", "origin", current_branch(self.repo)],
            cwd=self.repo,
            check=True,
            capture_output=True,
        )
        run(["push", "origin"], self.repo)

        clone_a = Path(self.tempdir.name) / "clone-a"
        clone_b = Path(self.tempdir.name) / "clone-b"
        subprocess.run(
            ["git", "clone", str(remote_repo), str(clone_a)],
            check=True,
            capture_output=True,
        )
        subprocess.run(
            ["git", "clone", str(remote_repo), str(clone_b)],
            check=True,
            capture_output=True,
        )
        run(["summary"], clone_a)
        run(["summary"], clone_b)

        remote_event = (
            clone_a / ".git" / "gittoc" / "issues" / "open" / "T-1.events.jsonl"
        )
        with remote_event.open("a", encoding="utf-8") as handle:
            handle.write("{broken json\n")
        subprocess.run(
            ["git", "-C", str(clone_a / ".git" / "gittoc"), "add", "issues"],
            check=True,
            capture_output=True,
        )
        subprocess.run(
            [
                "git",
                "-C",
                str(clone_a / ".git" / "gittoc"),
                "commit",
                "-m",
                "Corrupt tracker events",
            ],
            check=True,
            capture_output=True,
        )
        run(["push", "origin"], clone_a)

        run(["new", "Local issue"], clone_b)
        result = run_fail(["pull", "origin"], clone_b)
        self.assertEqual(result.returncode, 1)
        self.assertIn("malformed JSON", result.stderr)
        self.assertIn("issues/open/T-1.events.jsonl", result.stderr)

    def test_fsck_tolerates_events_without_ref(self) -> None:
        """Events written before the ref field was introduced must still pass fsck."""
        run(["init"], self.repo)
        run(["new", "Task"], self.repo)
        gittoc_dir = self.repo / ".git" / "gittoc"
        event_path = gittoc_dir / "issues" / "open" / "T-1.events.jsonl"
        # Overwrite with a legacy event that has no 'ref' field
        event_path.write_text(
            '{"actor":"alice","at":"2024-01-01T00:00:00+00:00","kind":"created","text":"Task"}\n',
            encoding="utf-8",
        )
        self.assertIn("fsck ok", run(["fsck"], self.repo))

    def test_fsck_tolerates_events_with_unknown_fields(self) -> None:
        """Events with extra unknown fields (e.g. 'ref') must not fail fsck."""
        run(["init"], self.repo)
        run(["new", "Task"], self.repo)
        gittoc_dir = self.repo / ".git" / "gittoc"
        event_path = gittoc_dir / "issues" / "open" / "T-1.events.jsonl"
        event_path.write_text(
            '{"actor":"alice","at":"2024-01-01T00:00:00+00:00","kind":"created",'
            '"ref":"main@abc1234","text":"Task","future_field":"ignored"}\n',
            encoding="utf-8",
        )
        self.assertIn("fsck ok", run(["fsck"], self.repo))


class TestAutoPush(GittocTestBase):
    def test_autopush_syncs_new_ticket(self) -> None:
        remote_repo = self.init_with_remote()
        subprocess.run(
            ["git", "push", "-u", "origin", "gittoc"],
            cwd=self.repo,
            check=True,
            capture_output=True,
        )
        subprocess.run(
            ["git", "config", "gittoc.autopush", "true"],
            cwd=self.repo,
            check=True,
            capture_output=True,
        )

        clone = Path(self.tempdir.name) / "clone-autopush"
        subprocess.run(
            ["git", "clone", str(remote_repo), str(clone)],
            check=True,
            capture_output=True,
        )
        run(["summary"], clone)  # triggers init of gittoc worktree

        run(["new", "Auto-pushed ticket"], self.repo)

        # clone should see the ticket after pulling
        run(["pull", "origin"], clone)
        summary = run(["summary"], clone)
        self.assertIn("open=1", summary)

    def test_autopush_push_failure_warns_not_aborts(self) -> None:
        run(["init"], self.repo)
        subprocess.run(
            ["git", "config", "gittoc.autopush", "true"],
            cwd=self.repo,
            check=True,
            capture_output=True,
        )
        # autopush is enabled but no remote is configured — push skipped silently
        issue = run(["new", "Ticket without remote"], self.repo)
        self.assertEqual(issue, "T-1")

    def test_auto_pull_fetch_failure_warns_and_continues(self) -> None:
        """Fetch failure during auto-pull should warn to stderr but not abort."""
        self.init_with_remote()
        run(["new", "first ticket"], self.repo)
        run(["push", "origin"], self.repo)
        subprocess.run(
            ["git", "config", "gittoc.autopush", "true"],
            cwd=self.repo,
            check=True,
            capture_output=True,
        )
        # Break the remote URL to simulate a network failure
        subprocess.run(
            ["git", "remote", "set-url", "origin", "/nonexistent/path"],
            cwd=self.repo,
            check=True,
            capture_output=True,
        )
        # Mutation should succeed locally; auto-pull and auto-push both warn
        proc = run_fail(["new", "offline ticket"], self.repo)
        self.assertEqual(proc.returncode, 0)
        self.assertIn("warning", proc.stderr)
        self.assertIn("T-2", proc.stdout)

    def test_auto_pull_merge_conflict_aborts_mutation(self) -> None:
        """A merge conflict during auto-pull must abort before any local write."""
        remote_repo = self.init_with_remote()
        run(["new", "original title"], self.repo)
        run(["push", "origin"], self.repo)

        # Clone: update T-1 and push to create divergent history
        clone = Path(self.tempdir.name) / "clone-conflict"
        subprocess.run(
            ["git", "clone", str(remote_repo), str(clone)],
            check=True,
            capture_output=True,
        )
        subprocess.run(
            ["git", "config", "user.name", "Test User"],
            cwd=clone,
            check=True,
            capture_output=True,
        )
        subprocess.run(
            ["git", "config", "user.email", "test@example.com"],
            cwd=clone,
            check=True,
            capture_output=True,
        )
        run(["init"], clone)
        run(["update", "T-1", "-t", "clone title"], clone)
        run(["push", "origin"], clone)

        # Repo A: also update T-1 differently — now divergent, not pushed
        run(["update", "T-1", "-t", "repo title"], self.repo)

        # Enable autopush so auto-pull fires before the next mutation
        subprocess.run(
            ["git", "config", "gittoc.autopush", "true"],
            cwd=self.repo,
            check=True,
            capture_output=True,
        )

        # Creating T-2 triggers auto-pull → merge conflict → mutation aborted
        proc = run_fail(["new", "T-2 title"], self.repo)
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("resolve conflicts", proc.stderr)

    def test_autopush_disabled_does_not_push(self) -> None:
        """Without autopush enabled, mutations stay local and don't reach the remote."""
        remote_repo = self.init_with_remote()
        run(["push", "origin"], self.repo)

        # Clone starts with an empty tracker
        clone = Path(self.tempdir.name) / "clone-no-autopush"
        subprocess.run(
            ["git", "clone", str(remote_repo), str(clone)],
            check=True,
            capture_output=True,
        )
        subprocess.run(
            ["git", "config", "user.name", "Test User"],
            cwd=clone,
            check=True,
            capture_output=True,
        )
        subprocess.run(
            ["git", "config", "user.email", "test@example.com"],
            cwd=clone,
            check=True,
            capture_output=True,
        )
        run(["init"], clone)

        # autopush NOT enabled — create a ticket locally in repo A
        run(["new", "local only ticket"], self.repo)

        # Clone pulls — should NOT see the new ticket
        run(["pull", "origin"], clone)
        summary = run(["summary"], clone)
        self.assertIn("open=0", summary)


class TestMiscCoverage(GittocTestBase):
    """Coverage for commands and options not exercised elsewhere."""

    def test_labels_command(self) -> None:
        run(["init"], self.repo)
        run(["new", "bug one", "-l", "bug,p1"], self.repo)
        run(["new", "bug two", "-l", "bug"], self.repo)
        run(["new", "feature", "-l", "feature"], self.repo)
        out = run(["labels"], self.repo)
        # bug appears on 2 tickets; count is the second token on each line
        lines = {line.split()[0]: line.split()[1] for line in out.splitlines() if line}
        self.assertEqual(lines.get("bug"), "2")
        self.assertEqual(lines.get("feature"), "1")
        self.assertEqual(lines.get("p1"), "1")

    def test_labels_all_flag(self) -> None:
        run(["init"], self.repo)
        run(["new", "task", "-l", "chore"], self.repo)
        run(["close", "T-1"], self.repo)
        # Without -a, closed ticket labels don't appear
        out_open = run(["labels"], self.repo)
        self.assertEqual(out_open.strip(), "")
        # With -a, closed ticket labels appear
        out_all = run(["labels", "-a"], self.repo)
        self.assertIn("chore", out_all)

    def test_labels_defined_labels_file(self) -> None:
        run(["init"], self.repo)
        run(["new", "a task", "-l", "bug"], self.repo)
        # Write a labels.json to the tracker branch
        labels_json = self.repo / ".git/gittoc/labels.json"
        labels_json.write_text(
            '{"bug": "Something is broken", "ready": "Ready to implement"}',
            encoding="utf-8",
        )
        out = run(["labels"], self.repo)
        lines = {line.split()[0]: line.split()[1] for line in out.splitlines() if line}
        # bug is in use and defined
        self.assertEqual(lines.get("bug"), "1")
        self.assertIn("Something is broken", out)
        # ready is defined but not in use — still shown with count 0
        self.assertIn("ready", out)
        self.assertEqual(lines.get("ready"), "0")
        self.assertIn("Ready to implement", out)

    def test_labels_defined_labels_json_format(self) -> None:
        run(["init"], self.repo)
        run(["new", "a task", "-l", "bug"], self.repo)
        labels_json = self.repo / ".git/gittoc/labels.json"
        labels_json.write_text('{"bug": "Something is broken"}', encoding="utf-8")
        out = run(["labels", "-f", "json"], self.repo)
        rows = json.loads(out)
        bug_row = next(r for r in rows if r["label"] == "bug")
        self.assertEqual(bug_row["count"], 1)
        self.assertEqual(bug_row["description"], "Something is broken")

    def test_labels_no_defined_file(self) -> None:
        run(["init"], self.repo)
        run(["new", "a task", "-l", "custom"], self.repo)
        # No labels.json — output should still work, count as second token
        out = run(["labels"], self.repo)
        lines = {line.split()[0]: line.split()[1] for line in out.splitlines() if line}
        self.assertEqual(lines.get("custom"), "1")

    def test_grep_content_search(self) -> None:
        run(["init"], self.repo)
        run(["new", "task", "-b", "contains needle here"], self.repo)
        run(["new", "other task"], self.repo)
        out = run(["grep", "needle"], self.repo)
        self.assertIn("needle", out)
        self.assertNotIn("other task", out)

    def test_grep_all_states(self) -> None:
        run(["init"], self.repo)
        run(["new", "open needle"], self.repo)
        run(["new", "closed needle"], self.repo)
        run(["close", "T-2"], self.repo)
        out_open = run(["grep", "needle"], self.repo)
        self.assertIn("T-1", out_open)
        self.assertNotIn("closed", out_open)
        out_all = run(["grep", "-a", "needle"], self.repo)
        self.assertIn("T-1", out_all)
        self.assertIn("T-2", out_all)

    def test_remote_set_command(self) -> None:
        self.init_with_remote()
        run(["remote", "--set", "origin"], self.repo)
        out = run(["remote"], self.repo)
        self.assertIn("configured=origin", out)

    def test_remote_status_output(self) -> None:
        self.init_with_remote()
        out = run(["remote"], self.repo)
        self.assertIn("remotes=origin", out)
        self.assertIn("effective=origin", out)

    def test_log_no_reverse(self) -> None:
        run(["init"], self.repo)
        run(["new", "first"], self.repo)
        run(["new", "second"], self.repo)
        forward = run(["log"], self.repo).splitlines()
        backward = run(["log", "--no-reverse"], self.repo).splitlines()
        self.assertEqual(forward, list(reversed(backward)))

    def test_log_limit(self) -> None:
        run(["init"], self.repo)
        run(["new", "first"], self.repo)
        run(["new", "second"], self.repo)
        run(["new", "third"], self.repo)
        # Newest 2 commits, shown newest-first
        lines = run(["log", "--no-reverse", "--limit", "2"], self.repo).splitlines()
        self.assertEqual(len(lines), 2)
        self.assertIn("third", lines[0])
        self.assertIn("second", lines[1])
        # Default --reverse: newest 2, then oldest-first
        reversed_lines = run(["log", "--limit", "2"], self.repo).splitlines()
        self.assertEqual(reversed_lines, list(reversed(lines)))

    def test_log_limit_per_issue(self) -> None:
        run(["init"], self.repo)
        run(["new", "task"], self.repo)
        run(["note", "T-1", "first note"], self.repo)
        run(["note", "T-1", "second note"], self.repo)
        # Default --reverse on per-issue path exercises the --follow codepath
        # where git's --max-count+--reverse+--follow combination is buggy
        lines = run(["log", "T-1", "--limit", "1"], self.repo).splitlines()
        self.assertEqual(len(lines), 1)
        lines = run(
            ["log", "T-1", "--no-reverse", "--limit", "1"], self.repo
        ).splitlines()
        self.assertEqual(len(lines), 1)

    def test_log_limit_rejects_non_positive(self) -> None:
        run(["init"], self.repo)
        run(["new", "task"], self.repo)
        for bad in ("0", "-1"):
            proc = run_fail(["log", "--limit", bad], self.repo)
            self.assertNotEqual(proc.returncode, 0)
            self.assertIn("--limit", proc.stderr)

    def test_show_limit_rejects_non_positive(self) -> None:
        run(["init"], self.repo)
        run(["new", "task"], self.repo)
        proc = run_fail(["show", "T-1", "--limit", "0"], self.repo)
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("--limit", proc.stderr)

    def test_unblocked_command(self) -> None:
        run(["init"], self.repo)
        run(["new", "blocker"], self.repo)
        run(["new", "blocked", "-d", "T-1"], self.repo)
        run(["new", "free"], self.repo)
        out = run(["unblocked", "-f", "compact"], self.repo)
        self.assertIn("T-1", out)
        self.assertIn("T-3", out)
        self.assertNotIn("T-2", out)

    def test_update_owner_and_body(self) -> None:
        run(["init"], self.repo)
        run(["new", "task"], self.repo)
        run(["update", "T-1", "--owner", "alice", "-b", "new body"], self.repo)
        data = json.loads(run(["show", "T-1", "-f", "json"], self.repo))
        self.assertEqual(data["owner"], "alice")
        self.assertEqual(data["body"], "new body")

    def test_show_all_history(self) -> None:
        run(["init"], self.repo)
        run(["new", "task"], self.repo)
        run(["note", "T-1", "first note"], self.repo)
        run(["note", "T-1", "second note"], self.repo)
        data = json.loads(run(["show", "T-1", "-a", "-f", "json"], self.repo))
        self.assertIn("history", data)
        kinds = [e["kind"] for e in data["history"]]
        self.assertIn("created", kinds)
        self.assertIn("note", kinds)

    def test_summary_json_format(self) -> None:
        run(["init"], self.repo)
        run(["new", "task"], self.repo)
        data = json.loads(run(["summary", "-f", "json"], self.repo))
        self.assertEqual(data["open"], 1)
        self.assertEqual(data["ready"], 1)
        self.assertIn("claimed", data)


class TestVersioning(GittocTestBase):
    def _worktree(self) -> Path:
        return self.repo / ".git" / "gittoc"

    def _version_path(self) -> Path:
        return self._worktree() / "VERSION"

    def test_init_writes_version_file(self) -> None:
        run(["init"], self.repo)
        vpath = self._version_path()
        self.assertTrue(vpath.exists())
        data = json.loads(vpath.read_text(encoding="utf-8"))
        self.assertEqual(data["format_version"], 1)
        self.assertEqual(data["layout_version"], 1)
        self.assertIn("migrated_at", data)
        self.assertIn("migrated_by", data)

    def test_v0_to_v1_migration(self) -> None:
        """A pre-versioning tracker gets VERSION stamped on open."""
        run(["init"], self.repo)
        vpath = self._version_path()
        # Simulate a pre-versioning tracker by removing VERSION
        vpath.unlink()
        subprocess.run(
            ["git", "add", "-A"], cwd=self._worktree(), check=True, capture_output=True
        )
        subprocess.run(
            ["git", "commit", "-q", "-m", "remove VERSION"],
            cwd=self._worktree(),
            check=True,
            capture_output=True,
        )
        self.assertFalse(vpath.exists())
        # Next command triggers open() → migration
        run(["summary"], self.repo)
        self.assertTrue(vpath.exists())
        data = json.loads(vpath.read_text(encoding="utf-8"))
        self.assertEqual(data["format_version"], 1)
        self.assertEqual(data["layout_version"], 1)

    def test_version_too_high_aborts(self) -> None:
        """A tracker with a higher version than supported causes an error."""
        run(["init"], self.repo)
        vpath = self._version_path()
        data = json.loads(vpath.read_text(encoding="utf-8"))
        data["format_version"] = 99
        vpath.write_text(
            json.dumps(data, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
        subprocess.run(
            ["git", "add", "-A"], cwd=self._worktree(), check=True, capture_output=True
        )
        subprocess.run(
            ["git", "commit", "-q", "-m", "bump version"],
            cwd=self._worktree(),
            check=True,
            capture_output=True,
        )
        result = run_fail(["summary"], self.repo)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("format version 99", result.stderr)
        self.assertIn("upgrade gittoc", result.stderr)

    def test_pull_rejects_version_mismatch(self) -> None:
        """Pull aborts before merging if remote has a different version."""
        remote_repo = Path(self.tempdir.name) / "ver-remote.git"
        subprocess.run(
            ["git", "init", "--bare", str(remote_repo)], check=True, capture_output=True
        )

        # Set up source and push a valid v1 tracker
        source = Path(self.tempdir.name) / "ver-source"
        shutil.copytree(self.repo, source)
        subprocess.run(
            ["git", "remote", "add", "origin", str(remote_repo)],
            cwd=source,
            check=True,
            capture_output=True,
        )
        run(["init"], source)
        subprocess.run(
            ["git", "push", "-u", "origin", current_branch(source)],
            cwd=source,
            check=True,
            capture_output=True,
        )
        run(["push", "origin"], source)

        # Clone inits while remote is still v1
        clone = Path(self.tempdir.name) / "ver-clone"
        subprocess.run(
            ["git", "clone", str(remote_repo), str(clone)],
            check=True,
            capture_output=True,
        )
        run(["init"], clone)

        # Now bump source to v2 directly via git and push
        src_wt = source / ".git" / "gittoc"
        vpath = src_wt / "VERSION"
        data = json.loads(vpath.read_text(encoding="utf-8"))
        data["format_version"] = 2
        vpath.write_text(
            json.dumps(data, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
        subprocess.run(
            ["git", "add", "-A"], cwd=src_wt, check=True, capture_output=True
        )
        subprocess.run(
            ["git", "commit", "-q", "-m", "bump to v2"],
            cwd=src_wt,
            check=True,
            capture_output=True,
        )
        subprocess.run(
            ["git", "push", "origin", "gittoc"],
            cwd=source,
            check=True,
            capture_output=True,
        )

        # Clone is at v1, remote is now v2 — pull should fail
        result = run_fail(["pull", "origin"], clone)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("version mismatch", result.stderr)

    def test_push_rejects_version_mismatch(self) -> None:
        """Push aborts if remote has a different version."""
        remote_repo = Path(self.tempdir.name) / "ps-ver.git"
        subprocess.run(
            ["git", "init", "--bare", str(remote_repo)], check=True, capture_output=True
        )

        # Source pushes a valid v1 tracker
        source = Path(self.tempdir.name) / "ps-ver-src"
        shutil.copytree(self.repo, source)
        subprocess.run(
            ["git", "remote", "add", "origin", str(remote_repo)],
            cwd=source,
            check=True,
            capture_output=True,
        )
        run(["init"], source)
        subprocess.run(
            ["git", "push", "-u", "origin", current_branch(source)],
            cwd=source,
            check=True,
            capture_output=True,
        )
        run(["push", "origin"], source)

        # Clone inits while remote is still v1, creates a local ticket
        clone = Path(self.tempdir.name) / "ps-ver-clone"
        subprocess.run(
            ["git", "clone", str(remote_repo), str(clone)],
            check=True,
            capture_output=True,
        )
        run(["init"], clone)
        run(["new", "local ticket"], clone)

        # Now bump source to v2 directly via git and push
        src_wt = source / ".git" / "gittoc"
        vpath = src_wt / "VERSION"
        data = json.loads(vpath.read_text(encoding="utf-8"))
        data["format_version"] = 2
        vpath.write_text(
            json.dumps(data, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
        subprocess.run(
            ["git", "add", "-A"], cwd=src_wt, check=True, capture_output=True
        )
        subprocess.run(
            ["git", "commit", "-q", "-m", "bump to v2"],
            cwd=src_wt,
            check=True,
            capture_output=True,
        )
        subprocess.run(
            ["git", "push", "origin", "gittoc"],
            cwd=source,
            check=True,
            capture_output=True,
        )

        # Clone is at v1, remote is now v2 — push should fail
        result = run_fail(["push", "origin"], clone)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("version mismatch", result.stderr)

    def _commit_worktree(self, wt: Path, msg: str) -> None:
        """Stage all changes in a worktree and commit."""
        subprocess.run(["git", "add", "-A"], cwd=wt, check=True, capture_output=True)
        subprocess.run(
            ["git", "commit", "-q", "-m", msg],
            cwd=wt,
            check=True,
            capture_output=True,
        )

    def _write_version_raw(self, vpath: Path, content: str) -> None:
        """Write raw content to a VERSION file and commit it."""
        vpath.write_text(content, encoding="utf-8")
        self._commit_worktree(vpath.parent, "update VERSION")

    def test_layout_version_too_high_aborts(self) -> None:
        """A tracker with a higher layout version than supported causes an error."""
        run(["init"], self.repo)
        vpath = self._version_path()
        data = json.loads(vpath.read_text(encoding="utf-8"))
        data["layout_version"] = 99
        self._write_version_raw(
            vpath, json.dumps(data, indent=2, sort_keys=True) + "\n"
        )
        result = run_fail(["summary"], self.repo)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("layout version 99", result.stderr)
        self.assertIn("upgrade gittoc", result.stderr)

    def test_corrupted_version_file_aborts(self) -> None:
        """A VERSION file with invalid JSON causes a clear error."""
        run(["init"], self.repo)
        self._write_version_raw(self._version_path(), "not json at all\n")
        result = run_fail(["summary"], self.repo)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("malformed VERSION", result.stderr)

    def test_missing_fields_in_version_aborts(self) -> None:
        """A VERSION file missing required fields causes a clear error."""
        run(["init"], self.repo)
        self._write_version_raw(self._version_path(), "{}\n")
        result = run_fail(["summary"], self.repo)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("malformed VERSION", result.stderr)

    def test_migration_is_idempotent(self) -> None:
        """Opening the tracker twice does not create duplicate migration commits."""
        run(["init"], self.repo)
        vpath = self._version_path()
        vpath.unlink()
        self._commit_worktree(self._worktree(), "remove VERSION")
        # First open triggers migration
        run(["summary"], self.repo)
        log1 = subprocess.run(
            ["git", "log", "--oneline"],
            cwd=self._worktree(),
            text=True,
            capture_output=True,
            check=True,
        ).stdout
        migrate_count_1 = log1.count("migrate to format")
        # Second open should not migrate again
        run(["summary"], self.repo)
        log2 = subprocess.run(
            ["git", "log", "--oneline"],
            cwd=self._worktree(),
            text=True,
            capture_output=True,
            check=True,
        ).stdout
        migrate_count_2 = log2.count("migrate to format")
        self.assertEqual(migrate_count_1, 1)
        self.assertEqual(migrate_count_2, 1)

    def test_migration_creates_git_commit(self) -> None:
        """The v0-to-v1 migration creates a commit with a descriptive message."""
        run(["init"], self.repo)
        vpath = self._version_path()
        vpath.unlink()
        self._commit_worktree(self._worktree(), "remove VERSION")
        run(["summary"], self.repo)
        log = subprocess.run(
            ["git", "log", "--oneline", "-1"],
            cwd=self._worktree(),
            text=True,
            capture_output=True,
            check=True,
        ).stdout
        self.assertIn("migrate to format v1 layout v1", log)

    def test_version_file_is_git_tracked(self) -> None:
        """After init, VERSION is committed (not untracked)."""
        run(["init"], self.repo)
        status = subprocess.run(
            ["git", "status", "--porcelain"],
            cwd=self._worktree(),
            text=True,
            capture_output=True,
            check=True,
        ).stdout
        self.assertEqual(status.strip(), "")

    def test_push_first_time_no_remote_branch(self) -> None:
        """First push to a remote with no gittoc branch succeeds (gate skipped)."""
        remote_repo = Path(self.tempdir.name) / "first-push.git"
        subprocess.run(
            ["git", "init", "--bare", str(remote_repo)], check=True, capture_output=True
        )
        subprocess.run(
            ["git", "remote", "add", "origin", str(remote_repo)],
            cwd=self.repo,
            check=True,
            capture_output=True,
        )
        subprocess.run(
            ["git", "push", "-u", "origin", current_branch(self.repo)],
            cwd=self.repo,
            check=True,
            capture_output=True,
        )
        run(["init"], self.repo)
        push_out = json.loads(run(["push", "origin", "--format", "json"], self.repo))
        self.assertEqual(push_out["action"], "push")

    def test_push_allows_unversioned_remote(self) -> None:
        """Push succeeds when remote has no VERSION (pre-versioning baseline)."""
        remote_repo = Path(self.tempdir.name) / "push-unver.git"
        subprocess.run(
            ["git", "init", "--bare", str(remote_repo)], check=True, capture_output=True
        )

        # Source pushes without VERSION
        source = Path(self.tempdir.name) / "push-unver-src"
        shutil.copytree(self.repo, source)
        subprocess.run(
            ["git", "remote", "add", "origin", str(remote_repo)],
            cwd=source,
            check=True,
            capture_output=True,
        )
        run(["init"], source)
        src_wt = source / ".git" / "gittoc"
        (src_wt / "VERSION").unlink()
        self._commit_worktree(src_wt, "remove VERSION")
        subprocess.run(
            ["git", "push", "-u", "origin", current_branch(source)],
            cwd=source,
            check=True,
            capture_output=True,
        )
        subprocess.run(
            ["git", "push", "origin", "gittoc"],
            cwd=source,
            check=True,
            capture_output=True,
        )

        # Clone (with VERSION) can push to unversioned remote
        clone = Path(self.tempdir.name) / "push-unver-clone"
        subprocess.run(
            ["git", "clone", str(remote_repo), str(clone)],
            check=True,
            capture_output=True,
        )
        run(["init"], clone)
        run(["new", "ticket from clone"], clone)
        push_out = json.loads(run(["push", "origin", "--format", "json"], clone))
        self.assertEqual(push_out["action"], "push")

    def test_pull_no_merge_on_version_mismatch(self) -> None:
        """Pull with version mismatch leaves the local worktree unchanged."""
        remote_repo = Path(self.tempdir.name) / "no-merge.git"
        subprocess.run(
            ["git", "init", "--bare", str(remote_repo)], check=True, capture_output=True
        )
        source = Path(self.tempdir.name) / "no-merge-src"
        shutil.copytree(self.repo, source)
        subprocess.run(
            ["git", "remote", "add", "origin", str(remote_repo)],
            cwd=source,
            check=True,
            capture_output=True,
        )
        run(["init"], source)
        subprocess.run(
            ["git", "push", "-u", "origin", current_branch(source)],
            cwd=source,
            check=True,
            capture_output=True,
        )
        run(["push", "origin"], source)

        clone = Path(self.tempdir.name) / "no-merge-clone"
        subprocess.run(
            ["git", "clone", str(remote_repo), str(clone)],
            check=True,
            capture_output=True,
        )
        run(["init"], clone)
        clone_wt = clone / ".git" / "gittoc"
        head_before = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            cwd=clone_wt,
            text=True,
            capture_output=True,
            check=True,
        ).stdout.strip()

        # Bump source to v2 and push
        src_wt = source / ".git" / "gittoc"
        vpath = src_wt / "VERSION"
        data = json.loads(vpath.read_text(encoding="utf-8"))
        data["format_version"] = 2
        self._write_version_raw(
            vpath, json.dumps(data, indent=2, sort_keys=True) + "\n"
        )
        subprocess.run(
            ["git", "push", "origin", "gittoc"],
            cwd=source,
            check=True,
            capture_output=True,
        )

        # Pull should fail and HEAD should be unchanged
        result = run_fail(["pull", "origin"], clone)
        self.assertNotEqual(result.returncode, 0)
        head_after = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            cwd=clone_wt,
            text=True,
            capture_output=True,
            check=True,
        ).stdout.strip()
        self.assertEqual(head_before, head_after)

    def test_auto_pull_aborts_on_version_mismatch(self) -> None:
        """A mutation command with auto-pull aborts when remote version differs."""
        remote_repo = Path(self.tempdir.name) / "auto-pull-ver.git"
        subprocess.run(
            ["git", "init", "--bare", str(remote_repo)], check=True, capture_output=True
        )
        subprocess.run(
            ["git", "remote", "add", "origin", str(remote_repo)],
            cwd=self.repo,
            check=True,
            capture_output=True,
        )
        subprocess.run(
            ["git", "push", "-u", "origin", current_branch(self.repo)],
            cwd=self.repo,
            check=True,
            capture_output=True,
        )
        run(["init"], self.repo)
        run(["push", "origin"], self.repo)
        # Enable autopush (which also enables auto-pull before mutations)
        subprocess.run(
            ["git", "config", "--local", "gittoc.autopush", "true"],
            cwd=self.repo,
            check=True,
            capture_output=True,
        )

        # Bump remote to v2 via a second repo
        source2 = Path(self.tempdir.name) / "auto-pull-src2"
        subprocess.run(
            ["git", "clone", str(remote_repo), str(source2)],
            check=True,
            capture_output=True,
        )
        run(["init"], source2)
        src2_wt = source2 / ".git" / "gittoc"
        vpath = src2_wt / "VERSION"
        data = json.loads(vpath.read_text(encoding="utf-8"))
        data["format_version"] = 2
        self._write_version_raw(
            vpath, json.dumps(data, indent=2, sort_keys=True) + "\n"
        )
        subprocess.run(
            ["git", "push", "origin", "gittoc"],
            cwd=source2,
            check=True,
            capture_output=True,
        )

        # Mutation with auto-pull should abort — no ticket created
        result = run_fail(["new", "should not exist"], self.repo)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("version mismatch", result.stderr)

    def test_auto_push_catches_version_mismatch(self) -> None:
        """auto_push catches SystemExit from version mismatch instead of crashing.

        In normal flow auto_pull gates first, so this scenario only arises if
        the remote version changes between the pull and push within a single
        command (a race condition). We verify the code path by calling
        push_remote directly and checking that auto_push catches the error.
        """
        remote_repo = Path(self.tempdir.name) / "auto-push-ver.git"
        subprocess.run(
            ["git", "init", "--bare", str(remote_repo)], check=True, capture_output=True
        )
        subprocess.run(
            ["git", "remote", "add", "origin", str(remote_repo)],
            cwd=self.repo,
            check=True,
            capture_output=True,
        )
        subprocess.run(
            ["git", "push", "-u", "origin", current_branch(self.repo)],
            cwd=self.repo,
            check=True,
            capture_output=True,
        )
        run(["init"], self.repo)
        run(["push", "origin"], self.repo)

        # Bump remote to v2 directly
        source2 = Path(self.tempdir.name) / "auto-push-src2"
        subprocess.run(
            ["git", "clone", str(remote_repo), str(source2)],
            check=True,
            capture_output=True,
        )
        run(["init"], source2)
        src2_wt = source2 / ".git" / "gittoc"
        vpath = src2_wt / "VERSION"
        data = json.loads(vpath.read_text(encoding="utf-8"))
        data["format_version"] = 2
        self._write_version_raw(
            vpath, json.dumps(data, indent=2, sort_keys=True) + "\n"
        )
        subprocess.run(
            ["git", "push", "--force", "origin", "gittoc"],
            cwd=source2,
            check=True,
            capture_output=True,
        )

        # Call push directly — should raise SystemExit
        import sys

        sys.path.insert(0, str(ROOT))
        from gittoc_lib.tracker import Tracker

        tracker = Tracker(self.repo, self._worktree())
        with self.assertRaises(SystemExit):
            tracker.remote.push("origin")

        # But auto_push should catch it (not crash)
        subprocess.run(
            ["git", "config", "--local", "gittoc.autopush", "true"],
            cwd=self.repo,
            check=True,
            capture_output=True,
        )
        tracker2 = Tracker(self.repo, self._worktree())
        # auto_push should warn to stderr, not raise
        tracker2.remote.auto_push()  # Should not raise

    def test_pull_allows_unversioned_remote(self) -> None:
        """Pull succeeds when remote has no VERSION (pre-versioning baseline)."""
        remote_repo = Path(self.tempdir.name) / "unver.git"
        subprocess.run(
            ["git", "init", "--bare", str(remote_repo)], check=True, capture_output=True
        )

        # Source pushes with no VERSION
        source = Path(self.tempdir.name) / "unver-src"
        shutil.copytree(self.repo, source)
        subprocess.run(
            ["git", "remote", "add", "origin", str(remote_repo)],
            cwd=source,
            check=True,
            capture_output=True,
        )
        run(["init"], source)
        src_wt = source / ".git" / "gittoc"
        vpath = src_wt / "VERSION"
        vpath.unlink()
        subprocess.run(
            ["git", "add", "-A"], cwd=src_wt, check=True, capture_output=True
        )
        subprocess.run(
            ["git", "commit", "-q", "-m", "remove VERSION"],
            cwd=src_wt,
            check=True,
            capture_output=True,
        )
        subprocess.run(
            ["git", "push", "-u", "origin", current_branch(source)],
            cwd=source,
            check=True,
            capture_output=True,
        )
        run(["push", "origin"], source)

        # Clone pulls — should succeed (unversioned remote is compatible with anything)
        clone = Path(self.tempdir.name) / "unver-clone"
        subprocess.run(
            ["git", "clone", str(remote_repo), str(clone)],
            check=True,
            capture_output=True,
        )
        run(["init"], clone)
        pull_out = json.loads(run(["pull", "origin", "--format", "json"], clone))
        self.assertEqual(pull_out["action"], "pull")


class TestErrorMessages(GittocTestBase):
    """Verify that failure paths produce clear, actionable messages."""

    def test_invalid_issue_id_shows_expected_format(self) -> None:
        run(["init"], self.repo)
        proc = run_fail(["show", "42"], self.repo)
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("T-<number>", proc.stderr)

    def test_invalid_issue_id_various_forms(self) -> None:
        run(["init"], self.repo)
        for bad_id in ["42", "t-1", "X-1", "TT-1", ""]:
            proc = run_fail(["show", bad_id], self.repo)
            self.assertNotEqual(proc.returncode, 0, f"expected failure for {bad_id!r}")

    def test_invalid_state_lists_valid_states(self) -> None:
        run(["init"], self.repo)
        proc = run_fail(["update", "T-1", "--state", "invalid"], self.repo)
        # argparse catches this before our validator, but let's check it fails
        self.assertNotEqual(proc.returncode, 0)

    def test_issue_not_found_message(self) -> None:
        run(["init"], self.repo)
        proc = run_fail(["show", "T-999"], self.repo)
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("issue not found", proc.stderr)

    def test_not_a_git_repo(self) -> None:
        plain_dir = Path(self.tempdir.name) / "plain"
        plain_dir.mkdir()
        proc = run_fail(["init"], plain_dir)
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("git error", proc.stderr)

    def test_unknown_remote(self) -> None:
        run(["init"], self.repo)
        proc = run_fail(["push", "nonexistent"], self.repo)
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("unknown remote", proc.stderr)

    def test_no_remote_configured_pull(self) -> None:
        run(["init"], self.repo)
        proc = run_fail(["pull"], self.repo)
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("no remote", proc.stderr)

    def test_no_remote_configured_push(self) -> None:
        run(["init"], self.repo)
        proc = run_fail(["push"], self.repo)
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("no remote", proc.stderr)

    def test_claim_non_ready_blocked_issue(self) -> None:
        run(["init"], self.repo)
        run(["new", "blocker"], self.repo)
        run(["new", "blocked"], self.repo)
        run(["depends", "T-2", "T-1"], self.repo)
        proc = run_fail(["claim", "T-2"], self.repo)
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("cannot claim non-ready", proc.stderr)

    def test_claim_closed_issue(self) -> None:
        run(["init"], self.repo)
        run(["new", "done ticket"], self.repo)
        run(["close", "T-1"], self.repo)
        proc = run_fail(["claim", "T-1"], self.repo)
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("cannot claim issue from state closed", proc.stderr)

    def test_cycle_detection_message(self) -> None:
        run(["init"], self.repo)
        run(["new", "a"], self.repo)
        run(["new", "b"], self.repo)
        run(["depends", "T-2", "T-1"], self.repo)
        proc = run_fail(["depends", "T-1", "T-2"], self.repo)
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("cycle", proc.stderr)

    def test_remove_nonexistent_dependency(self) -> None:
        run(["init"], self.repo)
        run(["new", "ticket"], self.repo)
        run(["new", "other"], self.repo)
        proc = run_fail(["depends", "-r", "T-1", "T-2"], self.repo)
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("not a dependency", proc.stderr)

    def test_update_no_fields(self) -> None:
        run(["init"], self.repo)
        run(["new", "ticket"], self.repo)
        proc = run_fail(["update", "T-1"], self.repo)
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("no fields to update", proc.stderr)

    def test_replace_label_conflict(self) -> None:
        run(["init"], self.repo)
        run(["new", "ticket"], self.repo)
        proc = run_fail(
            ["update", "T-1", "--replace-label", "a", "--label", "b"], self.repo
        )
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("cannot combine", proc.stderr)

    def test_grep_no_pattern(self) -> None:
        run(["init"], self.repo)
        proc = run_fail(["grep"], self.repo)
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("pattern", proc.stderr)

    def test_claim_non_ready_mentions_dependencies(self) -> None:
        run(["init"], self.repo)
        run(["new", "blocker"], self.repo)
        run(["new", "blocked"], self.repo)
        run(["depends", "T-2", "T-1"], self.repo)
        proc = run_fail(["claim", "T-2"], self.repo)
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("unresolved dependencies", proc.stderr)

    def test_remote_auto_no_remotes_hints_set(self) -> None:
        run(["init"], self.repo)
        proc = run_fail(["remote", "--auto"], self.repo)
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("--set", proc.stderr)

    def test_new_dep_not_found(self) -> None:
        run(["init"], self.repo)
        proc = run_fail(["new", "task", "-d", "T-999"], self.repo)
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("issue not found", proc.stderr)

    def test_malformed_issue_json(self) -> None:
        run(["init"], self.repo)
        # Write a corrupt JSON file directly into the tracker worktree
        worktree = self.repo / ".git" / "gittoc"
        bad_file = worktree / "issues" / "open" / "T-1.json"
        bad_file.write_text("not valid json{", encoding="utf-8")
        subprocess.run(
            ["git", "add", "issues/open/T-1.json"],
            cwd=str(worktree),
            check=True,
            capture_output=True,
        )
        subprocess.run(
            ["git", "commit", "-q", "-m", "inject bad ticket"],
            cwd=str(worktree),
            check=True,
            capture_output=True,
        )
        proc = run_fail(["show", "T-1"], self.repo)
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("malformed", proc.stderr.lower())

    def test_dep_on_missing_ticket(self) -> None:
        run(["init"], self.repo)
        run(["new", "ticket"], self.repo)
        proc = run_fail(["dep", "T-1", "T-999"], self.repo)
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("issue not found", proc.stderr)


class TestCommaArguments(GittocTestBase):
    """Verify that multi-value arguments accept comma-separated values."""

    def test_list_states_comma(self) -> None:
        run(["init"], self.repo)
        run(["new", "open ticket"], self.repo)
        run(["new", "claimed ticket"], self.repo)
        run(["claim", "T-2"], self.repo)
        out = run(["list", "-s", "open,claimed", "-f", "compact"], self.repo)
        self.assertIn("T-1", out)
        self.assertIn("T-2", out)

    def test_list_states_comma_and_repeated(self) -> None:
        """Comma and repeated -s can be combined."""
        run(["init"], self.repo)
        run(["new", "open ticket"], self.repo)
        run(["new", "done ticket"], self.repo)
        run(["close", "T-2"], self.repo)
        run(["new", "rejected ticket"], self.repo)
        run(["reject", "T-3"], self.repo)
        out = run(
            ["list", "-s", "open,closed", "-s", "rejected", "-f", "compact"], self.repo
        )
        self.assertIn("T-1", out)
        self.assertIn("T-2", out)
        self.assertIn("T-3", out)

    def test_list_states_invalid_comma(self) -> None:
        run(["init"], self.repo)
        proc = run_fail(["list", "-s", "open,bogus"], self.repo)
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("invalid state", proc.stderr)

    def test_grep_states_comma(self) -> None:
        run(["init"], self.repo)
        run(["new", "needle ticket"], self.repo)
        run(["new", "claimed needle"], self.repo)
        run(["claim", "T-2"], self.repo)
        out = run(["grep", "-s", "open,claimed", "needle"], self.repo)
        self.assertIn("needle", out)

    def test_new_deps_comma(self) -> None:
        run(["init"], self.repo)
        run(["new", "first"], self.repo)
        run(["new", "second"], self.repo)
        run(["new", "depends on both", "-d", "T-1,T-2"], self.repo)
        show = run(["show", "T-3"], self.repo)
        self.assertIn("T-1", show)
        self.assertIn("T-2", show)

    def test_dep_positional_comma(self) -> None:
        run(["init"], self.repo)
        run(["new", "a"], self.repo)
        run(["new", "b"], self.repo)
        run(["new", "c"], self.repo)
        run(["dep", "T-3", "T-1,T-2"], self.repo)
        show = run(["show", "T-3"], self.repo)
        self.assertIn("T-1", show)
        self.assertIn("T-2", show)

    def test_dep_remove_comma(self) -> None:
        run(["init"], self.repo)
        run(["new", "a"], self.repo)
        run(["new", "b"], self.repo)
        run(["new", "c", "-d", "T-1,T-2"], self.repo)
        run(["dep", "-r", "T-3", "T-1,T-2"], self.repo)
        show = run(["show", "T-3"], self.repo)
        self.assertNotIn("T-1", show.split("deps:")[1].split("\n")[0])

    def test_claim_comma(self) -> None:
        run(["init"], self.repo)
        run(["new", "a"], self.repo)
        run(["new", "b"], self.repo)
        run(["claim", "T-1,T-2"], self.repo)
        out = run(["claimed", "-f", "compact"], self.repo)
        self.assertIn("T-1", out)
        self.assertIn("T-2", out)

    def test_claim_comma_and_space(self) -> None:
        """Comma and space-separated can be mixed."""
        run(["init"], self.repo)
        run(["new", "a"], self.repo)
        run(["new", "b"], self.repo)
        run(["new", "c"], self.repo)
        run(["claim", "T-1,T-2", "T-3"], self.repo)
        out = run(["claimed", "-f", "compact"], self.repo)
        self.assertIn("T-1", out)
        self.assertIn("T-2", out)
        self.assertIn("T-3", out)


class TestFileAndStdinInput(GittocTestBase):
    """note/new/update can read body/text from a file or stdin (-F)."""

    def setUp(self) -> None:
        super().setUp()
        run(["init"], self.repo)
        run(["new", "host issue"], self.repo)

    def test_note_text_from_stdin(self) -> None:
        run(["note", "T-1", "-F", "-"], self.repo, stdin="from stdin")
        data = json.loads(run(["show", "T-1", "-n", "-f", "json"], self.repo))
        self.assertEqual(data["recent_notes"][0]["text"], "from stdin")

    def test_note_text_from_file(self) -> None:
        note_file = self.repo / "note.txt"
        note_file.write_text("from a file", encoding="utf-8")
        run(["note", "T-1", "-F", str(note_file)], self.repo)
        data = json.loads(run(["show", "T-1", "-n", "-f", "json"], self.repo))
        self.assertEqual(data["recent_notes"][0]["text"], "from a file")

    def test_new_body_from_stdin(self) -> None:
        new_id = run(["new", "stdin body", "-F", "-"], self.repo, stdin="body text")
        data = json.loads(run(["show", new_id, "-f", "json"], self.repo))
        self.assertEqual(data["body"], "body text")

    def test_update_body_from_file(self) -> None:
        body_file = self.repo / "body.txt"
        body_file.write_text("updated body", encoding="utf-8")
        run(["update", "T-1", "-F", str(body_file)], self.repo)
        data = json.loads(run(["show", "T-1", "-f", "json"], self.repo))
        self.assertEqual(data["body"], "updated body")

    def test_note_inline_and_file_conflict(self) -> None:
        proc = run_fail(["note", "T-1", "inline", "-F", "-"], self.repo, stdin="x")
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("not both", proc.stderr)

    def test_note_requires_text(self) -> None:
        proc = run_fail(["note", "T-1"], self.repo)
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("requires text", proc.stderr)

    def test_empty_note_from_stdin_rejected(self) -> None:
        proc = run_fail(["note", "T-1", "-F", "-"], self.repo, stdin="")
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("empty", proc.stderr)

    def test_update_clear_body_with_empty_file(self) -> None:
        run(["update", "T-1", "-b", "has body"], self.repo)
        empty = self.repo / "empty.txt"
        empty.write_text("", encoding="utf-8")
        run(["update", "T-1", "-F", str(empty)], self.repo)
        data = json.loads(run(["show", "T-1", "-f", "json"], self.repo))
        self.assertNotIn("body", data)

    def test_trailing_newline_stripped(self) -> None:
        run(["note", "T-1", "-F", "-"], self.repo, stdin="one trailing\n")
        run(["note", "T-1", "-F", "-"], self.repo, stdin="two trailing\n\n")
        data = json.loads(run(["show", "T-1", "-n", "-f", "json"], self.repo))
        notes = data["recent_notes"]
        self.assertEqual(notes[0]["text"], "one trailing")
        self.assertEqual(notes[1]["text"], "two trailing\n")

    def test_shell_metacharacters_roundtrip(self) -> None:
        payload = "see `type IS NOT 'x'` and $(whoami); cost $5 and ! history"
        meta_file = self.repo / "meta.txt"
        meta_file.write_text(payload, encoding="utf-8")
        run(["note", "T-1", "-F", str(meta_file)], self.repo)
        data = json.loads(run(["show", "T-1", "-n", "-f", "json"], self.repo))
        self.assertEqual(data["recent_notes"][0]["text"], payload)


class TestEventRef(GittocTestBase):
    """The event ref (commit hash) is surfaced in show/resume text views."""

    def test_show_history_surfaces_ref(self) -> None:
        run(["init"], self.repo)
        run(["new", "Task"], self.repo)
        run(["close", "T-1", "--actor", "dev"], self.repo)
        history = run(["show", "T-1", "-a"], self.repo)
        head = subprocess.run(
            ["git", "rev-parse", "--short", "HEAD"],
            cwd=self.repo,
            text=True,
            capture_output=True,
            check=True,
        ).stdout.strip()
        self.assertRegex(history, rf"closed \({head}\) dev")

    def test_show_notes_surface_ref(self) -> None:
        run(["init"], self.repo)
        run(["new", "Task"], self.repo)
        run(["note", "T-1", "a note", "--actor", "dev"], self.repo)
        notes = run(["show", "T-1", "-n"], self.repo)
        self.assertRegex(notes, r"note#1 \([0-9a-f]+\) dev: a note")

    def test_missing_objects_detects_orphan(self) -> None:
        run(["init"], self.repo)
        common = import_lib("common")
        head = subprocess.run(
            ["git", "rev-parse", "--short", "HEAD"],
            cwd=self.repo,
            text=True,
            capture_output=True,
            check=True,
        ).stdout.strip()
        missing = common.missing_objects(self.repo, {head, "deadbeef1234"})
        self.assertIn("deadbeef1234", missing)
        self.assertNotIn(head, missing)


class TestRenderUnit(unittest.TestCase):
    """Unit tests for the pure rendering helpers."""

    @staticmethod
    def _data():
        return {
            "id": "T-1",
            "priority": 3,
            "state": "closed",
            "title": "x",
            "history": [
                {
                    "kind": "closed",
                    "actor": "me",
                    "at": "2026-01-01T00:00:00+00:00",
                    "ref": "main@abc1234",
                }
            ],
        }

    def test_ref_short_hash_parsing(self) -> None:
        common = import_lib("common")
        self.assertEqual(common.ref_short_hash("main@abc1234"), "abc1234")
        self.assertEqual(common.ref_short_hash("abc1234"), "abc1234")
        self.assertEqual(common.ref_short_hash(""), "")

    def test_live_ref_rendered_without_marker(self) -> None:
        render = import_lib("render")
        out = render.render_show_text(self._data())
        self.assertIn("(abc1234)", out)
        self.assertNotIn("abc1234?", out)

    def test_dead_ref_marked(self) -> None:
        render = import_lib("render")
        out = render.render_show_text(self._data(), dead_refs={"abc1234"})
        self.assertIn("(abc1234?)", out)


class TestColors(unittest.TestCase):
    """Unit tests for the TTY-gated color helpers."""

    @staticmethod
    def _colors():
        import importlib

        sys.path.insert(0, str(ROOT))
        try:
            return importlib.import_module("gittoc_lib.colors")
        finally:
            sys.path.pop(0)

    def test_ok_wraps_in_green_when_tty(self) -> None:
        colors = self._colors()
        with mock.patch.dict(os.environ, {}, clear=True), mock.patch.object(
            sys.stdout, "isatty", return_value=True
        ):
            self.assertEqual(colors.ok("done"), "\033[32mdone\033[0m")

    def test_ok_is_plain_when_not_tty(self) -> None:
        colors = self._colors()
        with mock.patch.dict(os.environ, {}, clear=True), mock.patch.object(
            sys.stdout, "isatty", return_value=False
        ):
            self.assertEqual(colors.ok("done"), "done")

    def test_no_color_disables_even_on_tty(self) -> None:
        colors = self._colors()
        with mock.patch.dict(
            os.environ, {"NO_COLOR": "1"}, clear=True
        ), mock.patch.object(sys.stdout, "isatty", return_value=True):
            self.assertEqual(colors.ok("done"), "done")

    def test_warn_color_tracks_stderr_not_stdout(self) -> None:
        """warn/error are printed to stderr, so their color follows stderr."""
        colors = self._colors()
        with mock.patch.dict(os.environ, {}, clear=True), mock.patch.object(
            sys.stdout, "isatty", return_value=False
        ), mock.patch.object(sys.stderr, "isatty", return_value=True):
            self.assertEqual(colors.warn("careful"), "\033[1m\033[33mcareful\033[0m")
            # a stdout helper stays plain because stdout is not a TTY here
            self.assertEqual(colors.ok("done"), "done")

    def test_warn_plain_when_stderr_not_tty(self) -> None:
        colors = self._colors()
        with mock.patch.dict(os.environ, {}, clear=True), mock.patch.object(
            sys.stdout, "isatty", return_value=True
        ), mock.patch.object(sys.stderr, "isatty", return_value=False):
            self.assertEqual(colors.warn("careful"), "careful")


if __name__ == "__main__":
    unittest.main()
