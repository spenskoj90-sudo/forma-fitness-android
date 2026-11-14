"""Synthetic repository_dispatch proofs, no API tokens or network."""
import copy
import unittest

from test_trusted_publisher import FakeAPI, BASE, REPO, verified_candidate, publish
from trusted_publish import checkpoint, load_state
from trusted_artifacts import TrustError
from verify_dispatch import validate_dispatch


def fixture():
    api = FakeAPI()
    manifest, patch = verified_candidate(api, 12345, 1, BASE)
    publish(api, manifest, patch)
    event = {
        "action": "pilot-draft-ci",
        "repository": {"full_name": REPO},
        "client_payload": copy.deepcopy(api.dispatch_events[0]["client_payload"]),
    }
    state = load_state(api, manifest["task_id"])[1]
    assert state is not None
    return (event, api.prs[0], api.branch_ref(manifest["branch"]),
            state[0], state[1], api.run_record, api.base)


class DispatchProofTests(unittest.TestCase):
    def test_exact_product_head_dispatch_is_grounded(self):
        args = fixture()
        payload = validate_dispatch(*args)
        self.assertEqual(payload["head_sha"], args[2]["object"]["sha"])
        self.assertEqual(payload["pr_number"], args[1]["number"])

    def test_payload_not_created_by_reviewed_publisher_rejected(self):
        for key, value in (
            ("task_id", "pilot-synthetic-e2e-v1"), ("pr_number", 7),
            ("head_sha", "b" * 40), ("base_sha", "b" * 40),
            ("patch_sha256", "0" * 64), ("source_run_id", 9999),
        ):
            args = list(fixture())
            args[0]["client_payload"][key] = value
            with self.subTest(key=key), self.assertRaises(TrustError):
                validate_dispatch(*args)

    def test_extra_privilege_key_rejected(self):
        args = list(fixture())
        args[0]["client_payload"]["signer"] = "danger"
        with self.assertRaisesRegex(TrustError, "schema"):
            validate_dispatch(*args)

    def test_workflow_run_not_a_repository_dispatch_rejected(self):
        args = list(fixture())
        args[0]["action"] = "pull_request"
        with self.assertRaises(TrustError):
            validate_dispatch(*args)

    def test_closed_or_promoted_product_pr_not_admitted(self):
        for key, value in (("state", "closed"), ("draft", False)):
            args = list(fixture())
            args[1][key] = value
            with self.subTest(key=key), self.assertRaises(TrustError):
                validate_dispatch(*args)

    def test_product_branch_or_checkpoint_tampering_rejected(self):
        args = list(fixture())
        args[2]["object"]["sha"] = "b" * 40
        with self.assertRaises(TrustError):
            validate_dispatch(*args)
        args = list(fixture())
        args[3]["pr_head_sha"] = "b" * 40
        with self.assertRaises(TrustError):
            validate_dispatch(*args)

    def test_patch_data_changed_after_authorization_rejected(self):
        args = list(fixture())
        args[4] = args[4] + b"evil"
        with self.assertRaises(TrustError):
            validate_dispatch(*args)

    def test_stale_main_and_untrusted_source_run_rejected(self):
        args = list(fixture())
        args[6] = "b" * 40
        with self.assertRaises(TrustError):
            validate_dispatch(*args)
        args = list(fixture())
        args[5]["event"] = "workflow_dispatch"
        with self.assertRaises(TrustError):
            validate_dispatch(*args)

    def test_source_and_dispatch_pr_number_both_exact(self):
        args = list(fixture())
        args[5]["id"] += 1
        with self.assertRaises(TrustError):
            validate_dispatch(*args)


if __name__ == "__main__":
    unittest.main()
