"""Synthetic end-to-end tests; fake GitHub REST, no tokens, network or billing."""
import base64
import copy
import hashlib
import io
import json
from pathlib import Path
import stat
import unittest
import zipfile

from test_schedule_evidence import fixture
from verify_schedule_evidence import REPO, PLANS, verify_artifacts
from trusted_artifacts import (
    TrustError, TASKS, canonical_json, inspect_prs, manifest_digest,
    sha256, strict_zip, validate_run, verify_artifact, matching_pr,
)
from trusted_publish import (
    apply_as_data, checkpoint, create_draft, finish_checkpoint,
    make_git_commit, product_commit, publish, verified_candidate,
)

BASE = "a" * 40
PATH = "domain/src/main/kotlin/ru/forma/domain/Session.kt"
BEFORE = b"fun test() = Workout(s.id,s.day,s.startedMs,now,s.sets,\n"
AFTER = b"fun test() = Workout(s.id,s.day,s.startedMs,maxOf(now,s.startedMs),s.sets,\n"
PATCH = (
    "--- a/" + PATH + "\n+++ b/" + PATH +
    "\n@@ -1 +1 @@\n-" + BEFORE.decode().rstrip("\n") +
    "\n+" + AFTER.decode().rstrip("\n") + "\n"
).encode()


def artifact_fixture(package="workout-date", change=None):
    plan, queue, patches = fixture(package)
    if package == "workout-date":
        patches["workout-date"] = PATCH
        queue["records"]["workout-date"]["patch_sha256"] = sha256(PATCH)
    if change:
        change(plan, queue, patches)
    files = {
        "forma-scheduled-plan.json": json.dumps(plan).encode(),
        "queue.json": json.dumps(queue).encode(),
    }
    files.update({n + ".patch": raw for n, raw in patches.items()})
    receipt = verify_artifacts(
        "schedule", REPO, "12345", BASE, BASE, files["forma-scheduled-plan.json"],
        files["queue.json"], patches)
    files["forma-schedule-evidence.json"] = json.dumps(receipt).encode()
    with io.BytesIO() as stream:
        with zipfile.ZipFile(stream, "w", compression=zipfile.ZIP_DEFLATED) as z:
            for name, raw in sorted(files.items()):
                z.writestr(name, raw)
        payload = stream.getvalue()
    return files, payload


def run_info():
    return {
        "id": 12345, "run_attempt": 1, "event": "schedule", "head_branch": "main",
        "head_sha": BASE, "status": "completed", "conclusion": "success",
        "workflow_id": 987, "head_repository": {"full_name": REPO},
        "repository": {"full_name": REPO},
    }


def workflow():
    return {"id": 987, "path": ".github/workflows/ai-work-queue.yml"}


class FakeAPI:
    """Approximate Git Data REST with separate refs, trees, blobs and PR records."""
    def __init__(self, package="workout-date"):
        self.base = BASE
        self.run_record = run_info()
        self.wf_record = workflow()
        self.files, self.zip = artifact_fixture(package)
        self.art_record = {
            "id": 991, "name": "forma-ai-queue-12345-1", "expired": False,
            "size_in_bytes": len(self.zip), "digest": "sha256:" + sha256(self.zip),
        }
        self.refs = {}
        self.prs = []
        self.blobs = {}
        self.trees = {"1" * 40: {PATH: BEFORE}}
        self.commits = {BASE: {"tree": {"sha": "1" * 40}, "parents": []}}
        self.counter = 0
        self.draft_post_count = 0

    def main_sha(self):
        return self.base

    def run(self, _n):
        return self.run_record

    def workflow(self):
        return self.wf_record

    def artifact(self, _n, _attempt):
        return self.art_record

    def zip_artifact(self, _i):
        return self.zip

    def pulls(self):
        return self.prs

    def branch_ref(self, branch):
        return {"object": {"sha": self.refs[branch]}} if branch in self.refs else None

    def new_sha(self):
        self.counter += 1
        return f"{self.counter + 1000:040x}"

    def get(self, path):
        if path.startswith("/git/commits/"):
            return self.commits[path.removeprefix("/git/commits/")]
        if path.startswith("/contents/"):
            route, branch = path.removeprefix("/contents/").split("?ref=", 1)
            commit = self.refs.get(branch, branch)
            tree = self.commits[commit]["tree"]["sha"]
            return {
                "encoding": "base64",
                "content": base64.b64encode(self.trees[tree][route]).decode(),
                "sha": hashlib.sha1(self.trees[tree][route]).hexdigest(),
            }
        if path.startswith("/compare/"):
            base, branch = path.removeprefix("/compare/").split("...", 1)
            old_tree = self.trees[self.commits[base]["tree"]["sha"]]
            new_commit = self.refs[branch]
            new_tree = self.trees[self.commits[new_commit]["tree"]["sha"]]
            return {"files": [{"filename": x} for x in new_tree if
                              old_tree.get(x) != new_tree[x]]}
        raise AssertionError("unexpected GET " + path)

    def post(self, path, body):
        if path == "/git/blobs":
            raw = base64.b64decode(body["content"])
            ref = self.new_sha()
            self.blobs[ref] = raw
            return {"sha": ref}
        if path == "/git/trees":
            data = dict(self.trees[body["base_tree"]])
            for entry in body["tree"]:
                data[entry["path"]] = self.blobs[entry["sha"]]
            ref = self.new_sha()
            self.trees[ref] = data
            return {"sha": ref}
        if path == "/git/commits":
            ref = self.new_sha()
            self.commits[ref] = {"tree": {"sha": body["tree"]},
                                 "parents": [{"sha": x} for x in body["parents"]]}
            return {"sha": ref}
        if path == "/git/refs":
            branch = body["ref"].removeprefix("refs/heads/")
            if branch in self.refs:
                raise TrustError("ref already exists")
            self.refs[branch] = body["sha"]
            return {"object": {"sha": body["sha"]}}
        if path == "/pulls":
            self.draft_post_count += 1
            commit = self.refs[body["head"]]
            record = {
                "number": 501, "html_url": "https://github.com/" + REPO + "/pull/501",
                "state": "open", "draft": body["draft"], "base": {"ref": "main"},
                "head": {"ref": body["head"], "sha": commit},
            }
            self.prs.append(record)
            return record
        raise AssertionError("unexpected POST " + path)

    def patch(self, path, body):
        branch = path.removeprefix("/git/refs/heads/")
        prior = self.refs[branch]
        obj = self.commits[body["sha"]]
        if body.get("force") is not False or [x["sha"] for x in obj["parents"]] != [prior]:
            raise TrustError("non-fast-forward checkpoint")
        self.refs[branch] = body["sha"]
        return {"object": {"sha": body["sha"]}}


class TrustedPublisherTests(unittest.TestCase):
    def test_valid_reviewed_artifact_is_non_privileged(self):
        api = FakeAPI()
        manifest, patch = verified_candidate(api, 12345, 1, BASE)
        self.assertEqual(manifest["result"], "READY")
        self.assertEqual(manifest["task_id"], "forma-workout-finish-date-v1")
        self.assertEqual(manifest["patch_sha256"], sha256(patch))
        self.assertFalse("token" in canonical_json(manifest).decode())

    def test_real_workflow_run_required(self):
        for edit in (
            lambda api: api.run_record.update(event="workflow_dispatch"),
            lambda api: api.run_record.update(head_branch="fork"),
            lambda api: api.run_record.update(conclusion="failure"),
            lambda api: api.run_record.update(workflow_id=123),
            lambda api: api.run_record.update(head_sha="b" * 40),
            lambda api: api.run_record.update(run_attempt=9),
            lambda api: api.run_record.update(head_repository={"full_name": "evil/repo"}),
            lambda api: setattr(api, "base", "b" * 40),
            lambda api: api.wf_record.update(path=".github/workflows/malicious.yml"),
        ):
            api = FakeAPI()
            edit(api)
            with self.subTest(run=api.run_record, base=api.base), self.assertRaises(TrustError):
                verified_candidate(api, 12345, 1, BASE)

    def test_invalid_compressed_integrity_is_rejected(self):
        for mutator in (
            lambda api: api.art_record.update(digest="sha256:" + "0" * 64),
            lambda api: api.art_record.update(expired=True),
            lambda api: api.art_record.update(size_in_bytes=1),
            lambda api: setattr(api, "zip", api.zip + b"tamper"),
        ):
            api = FakeAPI()
            mutator(api)
            with self.assertRaises(TrustError):
                verified_candidate(api, 12345, 1, BASE)

    def test_invalid_archive_paths_and_duplicates_are_blocked(self):
        for names in (
            ["../../etc/hosts"],
            ["queue.json", "queue.json"],
            ["unapproved-file"],
            ["forma-ai-queue/../../secret"],
            ["forma-ai-queue/queue.json", "queue.json"],
        ):
            buf = io.BytesIO()
            with zipfile.ZipFile(buf, "w") as z:
                for name in names:
                    z.writestr(name, b"x")
            with self.subTest(names=names), self.assertRaises(TrustError):
                strict_zip(buf.getvalue())

    def test_archive_symlink_and_zip_bomb_are_blocked(self):
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as z:
            info = zipfile.ZipInfo("queue.json")
            info.create_system = 3
            info.external_attr = (stat.S_IFLNK | 0o777) << 16
            z.writestr(info, b"/etc/passwd")
        with self.assertRaises(TrustError):
            strict_zip(buf.getvalue())
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
            z.writestr("queue.json", b"x" * 40000)
        with self.assertRaises(TrustError):
            strict_zip(buf.getvalue())

    def test_signed_receipt_is_not_a_self_proof(self):
        api = FakeAPI()
        original_files, _ = artifact_fixture()
        original_files["forma-schedule-evidence.json"] = json.dumps(
            {"verification": "ARTIFACT_INTEGRITY_PASS",
             "publisher_authorized": True}).encode()
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as z:
            for name, raw in original_files.items():
                z.writestr(name, raw)
        api.zip = buf.getvalue()
        api.art_record.update(size_in_bytes=len(api.zip),
                              digest="sha256:" + sha256(api.zip))
        with self.assertRaisesRegex(TrustError, "receipt"):
            verified_candidate(api, 12345, 1, BASE)

    def test_synthetic_smoke_never_eligible_for_pr(self):
        api = FakeAPI("smoke")
        manifest, patch = verified_candidate(api, 12345, 1, BASE)
        self.assertEqual(manifest["result"], "SKIP_SYNTHETIC")
        self.assertIsNone(patch)
        self.assertEqual(api.draft_post_count, 0)

    def test_existing_approved_pr_blocks_duplicate(self):
        api = FakeAPI()
        api.prs = [{"number": 21, "head": {
            "ref": "forma/workout-finish-date-20261113", "sha": "b" * 40},
            "state": "open", "draft": False}]
        manifest, patch = verified_candidate(api, 12345, 1, BASE)
        self.assertEqual(manifest["result"], "SKIP_EXISTING_PR")
        self.assertIsNone(patch)

    def test_clean_one_draft_and_durable_checkpoint(self):
        api = FakeAPI()
        manifest, patch = verified_candidate(api, 12345, 1, BASE)
        result = publish(api, manifest, patch)
        self.assertEqual(result["status"], "DRAFT_CREATED_OR_REUSED")
        self.assertEqual(api.draft_post_count, 1)
        self.assertEqual(len(api.prs), 1)
        self.assertTrue(api.prs[0]["draft"])
        self.assertEqual(api.commits[result["head_sha"]]["parents"], [{"sha": BASE}])
        branch = "pilot-state/" + manifest["task_id"]
        self.assertIn(branch, api.refs)
        reread, state = checkpoint(api, manifest, patch)
        self.assertEqual(state["status"], "PUBLISHED")
        self.assertEqual(state["pr_number"], 501)
        self.assertEqual(state["checks"]["required_pr_checks"], "NOT_RUN")
        self.assertEqual(reread, result["checkpoint_sha"])

    def test_reexecution_finds_exact_same_draft(self):
        api = FakeAPI()
        manifest, patch = verified_candidate(api, 12345, 1, BASE)
        original = publish(api, manifest, patch)
        manifest2, patch2 = verified_candidate(api, 12345, 1, BASE)
        repeated = publish(api, manifest2, patch2)
        self.assertEqual(repeated["pr_number"], original["pr_number"])
        self.assertEqual(repeated["head_sha"], original["head_sha"])
        self.assertEqual(repeated["checkpoint_sha"], original["checkpoint_sha"])
        self.assertEqual(api.draft_post_count, 1)

    def test_interruption_after_persisted_patch_can_resume(self):
        api = FakeAPI()
        manifest, patch = verified_candidate(api, 12345, 1, BASE)
        initial, state = checkpoint(api, manifest, patch)
        self.assertEqual(state["status"], "READY")
        self.assertIsNone(state["pr_number"])
        new = publish(api, manifest, patch)
        self.assertNotEqual(new["checkpoint_sha"], initial)
        self.assertEqual(api.draft_post_count, 1)

    def test_unfinished_checkpoint_retry_increments_attempt_once_per_retry(self):
        api = FakeAPI()
        manifest, patch = verified_candidate(api, 12345, 1, BASE)
        first_ref, first = checkpoint(api, manifest, patch)
        next_ref, second = checkpoint(api, manifest, patch)
        self.assertEqual(first["attempt"], 1)
        self.assertEqual(second["attempt"], 2)
        self.assertEqual(second["patch_sha256"], first["patch_sha256"])
        self.assertNotEqual(first_ref, next_ref)
        done = publish(api, manifest, patch)
        self.assertEqual(api.draft_post_count, 1)
        self.assertEqual(checkpoint(api, manifest, patch)[1]["attempt"], 3)
        self.assertEqual(done["status"], "DRAFT_CREATED_OR_REUSED")

    def test_interruption_after_product_branch_recovers(self):
        api = FakeAPI()
        manifest, patch = verified_candidate(api, 12345, 1, BASE)
        checkpoint(api, manifest, patch)
        old_commit = product_commit(api, manifest, patch)
        result = publish(api, manifest, patch)
        self.assertEqual(result["head_sha"], old_commit)
        self.assertEqual(api.draft_post_count, 1)

    def test_interruption_after_pr_before_state_update_recovers(self):
        api = FakeAPI()
        manifest, patch = verified_candidate(api, 12345, 1, BASE)
        checkpoint(api, manifest, patch)
        commit = product_commit(api, manifest, patch)
        create_draft(api, manifest, commit)
        self.assertEqual(api.draft_post_count, 1)
        m2, p2 = verified_candidate(api, 12345, 1, BASE)
        result = publish(api, m2, p2)
        self.assertEqual(result["head_sha"], commit)
        self.assertEqual(api.draft_post_count, 1)
        self.assertEqual(checkpoint(api, manifest, patch)[1]["status"], "PUBLISHED")

    def test_stale_base_and_modified_task_branch_fail_closed(self):
        api = FakeAPI()
        manifest, patch = verified_candidate(api, 12345, 1, BASE)
        api.base = "b" * 40
        with self.assertRaisesRegex(TrustError, "STALE_BASE"):
            publish(api, manifest, patch)
        api.base = BASE
        product_commit(api, manifest, patch)
        branch = manifest["branch"]
        api.trees[api.commits[api.refs[branch]]["tree"]["sha"]][PATH] = b"bad"
        with self.assertRaisesRegex(TrustError, "different code"):
            publish(api, manifest, patch)

    def test_duplicate_task_branches_and_prs_fail_closed(self):
        api = FakeAPI()
        api.prs = [
            {"number": 17, "head": {"ref": "pilot/task-forma-workout-finish-date-v1"}},
            {"number": 18, "head": {"ref": "pilot/task-forma-workout-finish-date-v1"}},
        ]
        with self.assertRaisesRegex(TrustError, "multiple PR"):
            inspect_prs(api.pulls(), "forma-workout-finish-date-v1")

    def test_malformed_and_wrong_target_patch_refused(self):
        api = FakeAPI()
        manifest, patch = verified_candidate(api, 12345, 1, BASE)
        with self.assertRaises(TrustError):
            apply_as_data(api, {**manifest, "target_path": ".github/workflows/release.yml"}, patch)
        with self.assertRaises(TrustError):
            apply_as_data(api, manifest, patch.replace(b"Workout", b"OtherBad"))

    def test_manifest_hash_differs_after_changing_attempt(self):
        api = FakeAPI()
        manifest, _ = verified_candidate(api, 12345, 1, BASE)
        self.assertNotEqual(manifest_digest(manifest),
                            manifest_digest({**manifest, "run_attempt": 2}))


if __name__ == "__main__":
    unittest.main()
