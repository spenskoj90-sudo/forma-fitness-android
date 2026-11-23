"""No network, Android SDK, SaaS credit or GitHub write token required."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from pilot_task_state import (
    CasLost, GitLedger, StateError, claim, save_patch, verify, publish_sim,
    validate_prs,
)

TASK = "pilot-synthetic-e2e-v1"


def cmd(*args):
    return subprocess.run(["git", *map(str, args)], check=True,
                          capture_output=True).stdout.decode().strip()


class PersistentTaskTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        self.source = root / "source"
        self.bare = root / "durable-ledger.git"
        cmd("init", "-q", "-b", "main", self.source)
        (self.source / "fixture.py").write_text(
            "def bounded(n):\n    return max(0,n)\n", encoding="utf-8")
        cmd("-C", self.source, "add", "fixture.py")
        cmd("-C", self.source, "-c", "user.name=PILOT-TEST",
            "-c", "user.email=pilot-test@example.invalid",
            "commit", "-q", "-m", "fixture")
        self.base = cmd("-C", self.source, "rev-parse", "HEAD")
        cmd("init", "--bare", "-q", self.bare)
        self.ledger = GitLedger(self.bare)
        self.owner = "test-worker-1"
        self.patch = (
            "--- a/fixture.py\n+++ b/fixture.py\n"
            "@@ -1,2 +1,2 @@\n"
            " def bounded(n):\n"
            "-    return max(0,n)\n"
            "+    return min(9,max(0,n))\n"
        ).encode()

    def acquire(self, owner=None, now=100):
        return claim(self.ledger, TASK, self.base, owner or self.owner, now, [])

    def saved(self):
        self.acquire()
        save_patch(self.ledger, TASK, self.owner, self.patch, self.base)

    def verified(self):
        self.saved()
        verify(self.ledger, TASK, self.owner, self.source, self.base)

    def test_full_e2e_is_simulated_not_a_real_github_pr(self):
        self.verified()
        old, result = publish_sim(self.ledger, TASK, self.owner, self.base, [])
        self.assertEqual(result["status"], "PUBLISHED")
        self.assertTrue(result["pr_url"].startswith("simulation://draft/"))
        self.assertFalse(result["pr_head_sha"])
        self.assertEqual(result["checks"]["required_ci"], "NOT_RUN")
        self.assertEqual(result["patch_sha256"], hashlib.sha256(self.patch).hexdigest())
        self.assertEqual(result["attempt"], 1)
        self.assertEqual(self.ledger.read(TASK)[0], old)

    def test_restarts_in_clean_process_after_patch_saved_without_artifact(self):
        self.saved()
        checkpoint, state, raw = self.ledger.read(TASK)
        self.assertEqual(state["status"], "PATCH_SAVED")
        self.assertEqual(raw, self.patch)
        second = GitLedger(self.bare)  # independent, persisted bare repo
        _, resumed = claim(second, TASK, self.base, "test-worker-2", 2000, [])
        self.assertEqual(resumed["attempt"], 2)
        self.assertEqual(resumed["patch_sha256"], hashlib.sha256(self.patch).hexdigest())
        self.assertNotEqual(checkpoint, second.read(TASK)[0])
        verify(second, TASK, "test-worker-2", self.source, self.base)
        _, published = publish_sim(second, TASK, "test-worker-2", self.base, [])
        self.assertEqual(published["status"], "PUBLISHED")
        self.assertEqual(published["attempt"], 2)

    def test_retry_after_publishing_reuses_same_pr_and_ref(self):
        self.verified()
        first_ref, first = publish_sim(self.ledger, TASK, self.owner, self.base, [])
        prs = [{"number": 801, "state": "open",
                "head_ref": "pilot/task-pilot-synthetic-e2e-v1"}]
        retry_ref, retry = claim(self.ledger, TASK, self.base, "test-worker-2", 4000, prs)
        self.assertEqual(first_ref, retry_ref)
        self.assertEqual(first["pr_url"], retry["pr_url"])
        last_ref, last = publish_sim(self.ledger, TASK, "test-worker-2", self.base, prs)
        self.assertEqual(last_ref, first_ref)
        self.assertEqual(last["pr_url"], first["pr_url"])

    def test_concurrent_writer_cannot_steal_active_lease(self):
        self.acquire()
        with self.assertRaisesRegex(StateError, "WRITER_BUSY"):
            claim(self.ledger, TASK, self.base, "test-worker-2", 101, [])
        _, state = self.acquire()
        self.assertEqual(state["attempt"], 1)
        _, resumed = claim(self.ledger, TASK, self.base, "test-worker-2", 1001, [])
        self.assertEqual(resumed["attempt"], 2)

    def test_atomic_compare_and_swap_rejects_stale_writer(self):
        old, state = self.acquire()
        changed = {**state, "lease_until": 2000}
        self.ledger.write(TASK, old, changed)
        with self.assertRaises(CasLost):
            self.ledger.write(TASK, old, {**state, "lease_until": 3000})

    def test_unauthorized_paths_do_not_reach_state(self):
        self.acquire()
        wrong = self.patch.replace(b"fixture.py", b".github/workflows/release.yml")
        with self.assertRaisesRegex(StateError, "PATCH_REJECTED"):
            save_patch(self.ledger, TASK, self.owner, wrong, self.base)
        self.assertEqual(self.ledger.read(TASK)[1]["status"], "CLAIMED")

    def test_corrupted_or_empty_patch_is_rejected(self):
        self.acquire()
        for invalid in (b"not a diff", b"", self.patch + b"\x00"):
            with self.subTest(invalid=invalid[:15]), self.assertRaises(StateError):
                save_patch(self.ledger, TASK, self.owner, invalid, self.base)

    def test_corrupted_saved_patch_fails_hash_readback(self):
        self.saved()
        old, state, _ = self.ledger.read(TASK)
        tampered = self.patch.replace(b"min(9", b"min(8")
        with self.assertRaisesRegex(StateError, "digest"):
            self.ledger.write(TASK, old, state, tampered)
        with self.assertRaisesRegex(StateError, "digest"):
            self.ledger.read(TASK)

    def test_valid_syntax_but_inapplicable_diff_fails_independent_git_check(self):
        self.acquire()
        wrong = self.patch.replace(b"return max(0,n)", b"return bogus(n)")
        save_patch(self.ledger, TASK, self.owner, wrong, self.base)
        with self.assertRaisesRegex(StateError, "GIT_APPLY_CHECK_FAILED"):
            verify(self.ledger, TASK, self.owner, self.source, self.base)
        self.assertEqual(self.ledger.read(TASK)[1]["status"], "PATCH_SAVED")

    def test_stale_base_requires_revalidation_before_apply_or_publish(self):
        self.saved()
        (self.source / "fixture.py").write_text(
            "def bounded(n):\n    return min(3,max(0,n))\n")
        cmd("-C", self.source, "add", "fixture.py")
        cmd("-C", self.source, "-c", "user.name=PILOT-TEST",
            "-c", "user.email=pilot-test@example.invalid",
            "commit", "-q", "-m", "changed baseline")
        new_base = cmd("-C", self.source, "rev-parse", "HEAD")
        with self.assertRaisesRegex(StateError, "STALE_BASE"):
            claim(self.ledger, TASK, new_base, "test-worker-2", 2000, [])
        with self.assertRaisesRegex(StateError, "STALE_BASE"):
            verify(self.ledger, TASK, self.owner, self.source, self.base)
        with self.assertRaises(StateError):
            publish_sim(self.ledger, TASK, self.owner, new_base, [])

    def test_approved_product_task_with_existing_pr_is_blocked(self):
        for task_id, number in (("forma-workout-finish-date-v1", 21),
                                ("forma-backup-completed-sets-v1", 15)):
            with self.subTest(task_id=task_id), self.assertRaisesRegex(
                    StateError, "TASK_OCCUPIED_BY_PR"):
                claim(self.ledger, task_id, self.base, self.owner, 100,
                      [{"number": number, "state": "open"}])

    def test_incomplete_pr_snapshot_fails_closed(self):
        for prs in (None, {}, [{"number": True, "state": "open"}],
                    [{"number": 1, "state": "closed"}],
                    [{"number": 1, "state": "open"}] * 2,
                    [{"number": i, "state": "open"} for i in range(100)]):
            with self.subTest(prs=str(prs)[:60]), self.assertRaises(StateError):
                validate_prs(prs)

    def test_synthetic_task_never_claimed_by_live_publisher(self):
        with self.assertRaisesRegex(StateError, "synthetic"):
            claim(self.ledger, TASK, self.base, "live-run-123", 100, [])

    def test_simulator_cli_reloads_state_and_preserves_one_draft(self):
        prs_file = Path(self.temp.name) / "prs.json"
        patch_file = Path(self.temp.name) / "candidate.patch"
        prs_file.write_text("[]")
        patch_file.write_bytes(self.patch)
        args = [
            sys.executable, str(Path(__file__).with_name("pilot_task_state.py")),
            "--ledger", str(self.bare), "--task-id", TASK, "--base", self.base,
            "--owner", "test-worker-1", "--now", "100", "--prs", str(prs_file),
            "--source", str(self.source), "--patch", str(patch_file),
        ]
        first = subprocess.run(args, check=True, capture_output=True, text=True)
        second = subprocess.run(args, check=True, capture_output=True, text=True)
        a, b = json.loads(first.stdout), json.loads(second.stdout)
        self.assertEqual(a, b)
        self.assertEqual(a["status"], "PUBLISHED")
        self.assertEqual(a["attempt"], 1)


if __name__ == "__main__":
    unittest.main()
