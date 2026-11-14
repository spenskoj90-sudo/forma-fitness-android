"""Safe-output request parser tests: no credentials, network or git mutation."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from pilot_safe_output import ProposalError, PLANS, SOURCES, proposal

REV = "a" * 40


class SafeOutputTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.dir = Path(self.temp.name)
        self.package = "workout-date"
        self.task, self.path = SOURCES[self.package]
        self.patch = (f"--- a/{self.path}\n+++ b/{self.path}\n"
                      "@@ -1 +1 @@\n-old\n+new\n").encode()
        self.queue = {
            "identity": {"version": 1, "base_sha": REV, "plan": PLANS[self.package]},
            "records": {self.task: {
                "status": "PASS", "checks": "PASS", "review": "PASS",
                "patch_sha256": hashlib.sha256(self.patch).hexdigest(),
                "history": [{"result": "PASS"}],
            }},
        }
        self.flush()

    def flush(self):
        (self.dir / "queue.json").write_text(json.dumps(self.queue), encoding="utf-8")
        (self.dir / f"{self.task}.patch").write_bytes(self.patch)

    def make(self):
        return proposal(REV, self.package, self.dir, REV)

    def test_good_product_patch_is_untrusted_and_not_authorized(self):
        out = self.make()
        self.assertEqual(out["status"], "PENDING_TRUSTED_REVIEW")
        self.assertEqual(out["target_path"], self.path)
        self.assertEqual(out["base_sha"], REV)
        self.assertEqual(out["patch_sha256"], hashlib.sha256(self.patch).hexdigest())
        for key in ("publisher_authorized", "merge_authorized",
                    "release_authorized", "device_accepted"):
            self.assertFalse(out[key])
        self.assertEqual(len(out["required_checks"]), 5)

    def test_wrong_commit_or_non_product_package_is_rejected(self):
        with self.assertRaisesRegex(ProposalError, "exact"):
            proposal("b" * 40, self.package, self.dir, REV)
        with self.assertRaisesRegex(ProposalError, "exact"):
            proposal("main", self.package, self.dir, "main")
        with self.assertRaisesRegex(ProposalError, "exact"):
            proposal(REV, "smoke", self.dir, REV)

    def test_bad_checkpoint_base_or_graph_is_rejected(self):
        self.queue["identity"]["base_sha"] = "b" * 40
        self.flush()
        with self.assertRaisesRegex(ProposalError, "graph"):
            self.make()
        self.queue["identity"]["base_sha"] = REV
        self.queue["identity"]["plan"] = {"workout-date": ["hidden"]}
        self.flush()
        with self.assertRaisesRegex(ProposalError, "graph"):
            self.make()

    def test_incomplete_or_unreviewed_patch_fails_closed(self):
        for field, value in (("status", "NOT_RUN"), ("checks", "FAIL"),
                             ("review", "NOT_RUN"), ("candidate", "pending")):
            self.queue["records"][self.task][field] = value
            self.flush()
            with self.subTest(field=field), self.assertRaises(ProposalError):
                self.make()
            if field == "candidate":
                self.queue["records"][self.task].pop(field)
            else:
                self.queue["records"][self.task][field] = "PASS"

    def test_tampered_patch_hash_is_rejected(self):
        self.patch += b"+injected\n"
        self.flush()
        with self.assertRaisesRegex(ProposalError, "hash"):
            self.make()

    def test_extra_unexpected_record_or_field_is_rejected(self):
        self.queue["records"]["another"] = {}
        self.flush()
        with self.assertRaises(ProposalError):
            self.make()
        self.queue["records"].pop("another")
        self.queue["publisher_override"] = True
        self.flush()
        with self.assertRaises(ProposalError):
            self.make()

    def test_other_target_and_multiple_patch_headers_are_rejected(self):
        self.patch = self.patch.replace(self.path.encode(), b".github/workflows/release.yml")
        self.queue["records"][self.task]["patch_sha256"] = hashlib.sha256(self.patch).hexdigest()
        self.flush()
        with self.assertRaisesRegex(ProposalError, "reviewed file"):
            self.make()
        self.patch = (f"--- a/{self.path}\n+++ b/{self.path}\n@@ -1 +1 @@\n-x\n+y\n"
                      "--- a/extra\n+++ b/extra\n@@ -1 +1 @@\n-a\n+b\n").encode()
        self.queue["records"][self.task]["patch_sha256"] = hashlib.sha256(self.patch).hexdigest()
        self.flush()
        with self.assertRaisesRegex(ProposalError, "multiple"):
            self.make()

    def test_symlinked_artifact_is_rejected(self):
        original = self.dir / f"{self.task}.patch"
        target = self.dir / "target"
        original.rename(target)
        original.symlink_to(target)
        with self.assertRaisesRegex(ProposalError, "unsafe"):
            self.make()

    def test_oversized_queue_is_rejected_before_parsing(self):
        (self.dir / "queue.json").write_bytes(b"x" * 32769)
        with self.assertRaisesRegex(ProposalError, "unsafe"):
            self.make()

    def test_no_binary_or_crlf_patch_allowed(self):
        for raw in (self.patch + b"\x00", self.patch.replace(b"\n", b"\r\n")):
            self.patch = raw
            self.queue["records"][self.task]["patch_sha256"] = hashlib.sha256(raw).hexdigest()
            self.flush()
            with self.assertRaisesRegex(ProposalError, "encoding"):
                self.make()
            self.patch = raw.replace(b"\x00", b"").replace(b"\r\n", b"\n")

    def test_backup_package_uses_exact_distinct_target(self):
        self.package = "backup-replay"
        self.task, self.path = SOURCES[self.package]
        self.patch = (f"--- a/{self.path}\n+++ b/{self.path}\n"
                      "@@ -1 +1 @@\n-old\n+new\n").encode()
        self.queue = {
            "identity": {"version": 1, "base_sha": REV, "plan": PLANS[self.package]},
            "records": {name: {"status": "PASS", "checks": "PASS", "review": "PASS"}
                        for name in PLANS[self.package]},
        }
        self.queue["records"][self.task]["patch_sha256"] = hashlib.sha256(self.patch).hexdigest()
        self.flush()
        self.assertEqual(self.make()["target_path"], self.path)


if __name__ == "__main__":
    unittest.main()
