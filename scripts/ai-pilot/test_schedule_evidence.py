"""Schedule evidence validation: all data is synthetic; no GitHub API or models."""
import copy
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from verify_schedule_evidence import (
    EvidenceError, PLANS, REPO, digest, verify_artifacts,
)

SHA = "a" * 40
TASK_PATHS = {
    "clamp": "bounds.py",
    "clamp-followup": "followup_bounds.py",
    "workout-date": "domain/src/main/kotlin/ru/forma/domain/Session.kt",
    "backup-pr15": "domain/src/main/kotlin/ru/forma/domain/Backup.kt",
}


def make_patch(path):
    return ("--- a/" + path + "\n+++ b/" + path +
            "\n@@ -1 +1 @@\n-old\n+new\n").encode()


def fixture(package="smoke"):
    plan = {
        "schema": 1, "base_sha": SHA, "package": package,
        "reason": "approved task selection", "open_pr_count": 5,
        "authorization": "read-only-reviewed-package",
    }
    patches = {name: make_patch(TASK_PATHS[name]) for name in PLANS[package]}
    queue = {"identity": {"version": 1, "base_sha": SHA,
                          "plan": PLANS[package]},
             "records": {
                 name: {"status": "PASS", "checks": "PASS", "review": "PASS",
                        "patch_sha256": digest(patches[name]),
                        "history": [{"model": "local-test", "reviewer": "local-test-2",
                                     "result": "PASS"}]}
                 for name in patches
             }}
    return plan, queue, patches


class ScheduleEvidenceTests(unittest.TestCase):
    def evidence(self, package="smoke", edit=None, event="schedule", repo=REPO,
                 run="12345", revision=SHA, checkout=SHA):
        plan, queue, patches = fixture(package)
        if edit is not None:
            edit(plan, queue, patches)
        return verify_artifacts(
            event, repo, run, revision, checkout,
            json.dumps(plan).encode(), json.dumps(queue).encode(), patches)

    def test_synthetic_smoke_evidence_is_integrity_only(self):
        result = self.evidence()
        self.assertEqual(result["verification"], "ARTIFACT_INTEGRITY_PASS")
        self.assertEqual(result["task_type"], "synthetic-smoke")
        self.assertEqual(result["task_ids"], sorted(PLANS["smoke"]))
        self.assertFalse(result["actual_github_run_independently_verified"])
        self.assertFalse(result["product_pr_published"])
        self.assertFalse(result["publisher_authorized"])
        self.assertEqual(len(result["patch_sha256"]), 2)

    def test_reviewed_product_package_preserves_provenance_limit(self):
        for package in ("workout-date", "backup-replay"):
            with self.subTest(package=package):
                result = self.evidence(package)
                self.assertEqual(result["task_type"], "reviewed-product-package")
                self.assertFalse(result["actual_github_run_independently_verified"])

    def test_manual_run_not_mislabeled_schedule(self):
        with self.assertRaisesRegex(EvidenceError, "event"):
            self.evidence(event="workflow_dispatch")

    def test_wrong_repository_run_id_or_checkout_fails(self):
        invalid = ({"repo": "attacker/repository"}, {"run": "abc"},
                   {"revision": "b" * 40}, {"checkout": "b" * 40})
        for entry in invalid:
            with self.subTest(entry=entry), self.assertRaises(EvidenceError):
                self.evidence(**entry)

    def test_wrong_checkpoint_revision_or_plan_graph_fails(self):
        for edit in (
            lambda p, q, r: q["identity"].update(base_sha="b" * 40),
            lambda p, q, r: q["identity"].update(plan={"clamp": ["oops"]}),
            lambda p, q, r: p.update(base_sha="b" * 40),
        ):
            with self.subTest(edit=str(edit)), self.assertRaises(EvidenceError):
                self.evidence(edit=edit)

    def test_missing_or_unexpected_queue_task_fails(self):
        for edit in (
            lambda p, q, r: q["records"].pop("clamp"),
            lambda p, q, r: q["records"].update(fake={"status": "PASS"}),
        ):
            with self.assertRaises(EvidenceError):
                self.evidence(edit=edit)

    def test_incomplete_check_review_or_candidate_fails(self):
        for key, val in (("status", "NOT_RUN"), ("checks", "FAIL"),
                         ("review", "NOT_RUN"), ("candidate", "bogus")):
            def edit(_p, q, _r):
                q["records"]["clamp"][key] = val
            with self.subTest(field=key), self.assertRaises(EvidenceError):
                self.evidence(edit=edit)

    def test_corrupt_patch_hash_fails(self):
        with self.assertRaisesRegex(EvidenceError, "hash"):
            self.evidence(edit=lambda p, q, r: r.__setitem__(
                "clamp", r["clamp"] + b"+injected\n"))

    def test_extra_or_missing_patch_inventory_fails(self):
        for edit in (lambda p, q, r: r.pop("clamp"),
                     lambda p, q, r: r.update(extraneous=b"+secret\n")):
            with self.assertRaisesRegex(EvidenceError, "inventory"):
                self.evidence(edit=edit)

    def test_wrong_path_and_binary_patch_fail(self):
        for patch in (make_patch(".github/workflows/release.yml"),
                      make_patch(TASK_PATHS["clamp"]) + b"\0"):
            def edit(p, q, r):
                r["clamp"] = patch
                q["records"]["clamp"]["patch_sha256"] = digest(patch)
            with self.subTest(patch=patch[:24]), self.assertRaises(EvidenceError):
                self.evidence(edit=edit)

    def test_unknown_schema_fields_and_pr_count_rejected(self):
        for edit in (lambda p, q, r: p.update(publisher_authorized=True),
                     lambda p, q, r: p.update(open_pr_count=100),
                     lambda p, q, r: p.update(open_pr_count=True),
                     lambda p, q, r: p.update(authorization="privileged")):
            with self.assertRaises(EvidenceError):
                self.evidence(edit=edit)

    def test_missing_completed_attempt_is_not_accepted(self):
        with self.assertRaises(EvidenceError):
            self.evidence(edit=lambda p, q, r: q["records"]["clamp"].update(history=[]))

    def test_cli_outputs_fail_receipt_when_queue_absent(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            plan, _, _ = fixture()
            (root / "plan.json").write_text(json.dumps(plan))
            args = [sys.executable,
                    str(Path(__file__).with_name("verify_schedule_evidence.py")),
                    "--event", "schedule", "--repo", REPO, "--run-id", "123",
                    "--revision", SHA, "--checkout", SHA,
                    "--plan", str(root / "plan.json"),
                    "--queue", str(root / "queue.json"),
                    "--evidence-dir", str(root),
                    "--output", str(root / "receipt.json")]
            result = subprocess.run(args, capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(json.loads((root / "receipt.json").read_text())
                             ["verification"], "ARTIFACT_INTEGRITY_FAIL")


if __name__ == "__main__":
    unittest.main()
