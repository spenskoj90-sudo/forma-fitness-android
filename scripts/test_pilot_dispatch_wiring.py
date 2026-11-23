#!/usr/bin/env python3
"""Static regression checks for PILOT's read-only downstream CI routing.

These tests check wiring only. They cannot prove that GitHub has actually
delivered a repository_dispatch event or attached checks to a product PR SHA.
"""
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parent.parent
WORKFLOWS = [
    ROOT / ".github/workflows/android.yml",
    ROOT / ".github/workflows/backend-security.yml",
]


class PilotDispatchWiringTests(unittest.TestCase):
    def test_each_original_workflow_subscribes_without_losing_pr_ci(self):
        for path in WORKFLOWS:
            with self.subTest(workflow=path.name):
                code = path.read_text(encoding="utf-8")
                self.assertIn("  pull_request:\n", code)
                self.assertIn("  workflow_dispatch:\n", code)
                self.assertIn(
                    "  repository_dispatch:\n    types: [pilot-draft-ci]\n",
                    code,
                )
                self.assertIn("permissions:\n  contents: read\n", code)
                self.assertNotIn("  contents: write", code)
                self.assertNotIn("  actions: write", code)
                self.assertNotIn("  statuses: write", code)
                self.assertNotIn("  checks: write", code)

    def test_every_checkout_is_gated_before_untrusted_draft(self):
        for path, expected in zip(WORKFLOWS, [2, 1]):
            with self.subTest(workflow=path.name):
                code = path.read_text(encoding="utf-8")
                trusted = "      - name: Verify PILOT dispatch against trusted main"
                validation = (
                    '        run: python3 scripts/ai-pilot/verify_dispatch.py '
                    '--event "$GITHUB_EVENT_PATH"'
                )
                target = "      - name: Checkout exact validated PILOT draft commit"
                ref = '          ref: ${{ github.event.client_payload.head_sha }}'
                assert_sha = "      - name: Assert exact tested PILOT draft SHA"
                for needle in (trusted, validation, target, ref, assert_sha):
                    self.assertEqual(
                        code.count(needle), expected,
                        f"{path.name}: unexpected count of {needle}",
                    )
                # For each job, the trusted event checkout and validation
                # precede every checkout of untrusted candidate code.
                blocks = code.split(trusted)
                self.assertEqual(len(blocks), expected + 1)
                for block in blocks[1:]:
                    self.assertLess(block.index(validation), block.index(target))
                    self.assertLess(block.index(target), block.index(assert_sha))
                    before_target = block.split(target)[0]
                    self.assertIn("GH_TOKEN: ${{ github.token }}", before_target)
                    self.assertIn(
                        "if: github.event_name == 'repository_dispatch'",
                        before_target,
                    )
                    self.assertIn("persist-credentials: false", block)
                    self.assertIn(
                        'test "$(git rev-parse HEAD)" = "$EXPECTED_PILOT_SHA"',
                        block,
                    )

    def test_original_mandatory_checks_remain(self):
        android = WORKFLOWS[0].read_text(encoding="utf-8")
        backend = WORKFLOWS[1].read_text(encoding="utf-8")
        for name in ("  verify:", "  instrumentation:", "matrix:", "api: [26, 30, 34]"):
            self.assertIn(name, android)
        self.assertIn("  backend-security:", backend)
        self.assertIn("test_auth.py test_api_security.py test_rls.py", backend)


if __name__ == "__main__":
    unittest.main()
