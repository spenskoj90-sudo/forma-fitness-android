"""Fail-closed, authenticated schedule PR discovery; no network or credentials used."""
from pathlib import Path
import re
import unittest


WORKFLOW = Path(__file__).resolve().parent.parent / ".github/workflows/ai-work-queue.yml"


class PRSnapshotSecurityTests(unittest.TestCase):
    def test_queue_has_read_only_pr_metadata_permission(self):
        source = WORKFLOW.read_text(encoding="utf-8")
        job = source.split("\n  queue:\n", 1)[1]
        permissions = job.split("\n    permissions:\n", 1)[1].split("\n    runs-on:", 1)[0]
        self.assertIn("      contents: read\n", permissions)
        self.assertIn("      actions: read\n", permissions)
        self.assertIn("      pull-requests: read", permissions.splitlines())
        self.assertNotIn("write", permissions)
        self.assertEqual(source.count("pull-requests: read"), 1)

    def test_schedule_selector_uses_short_lived_authenticated_gh_api(self):
        source = WORKFLOW.read_text(encoding="utf-8")
        step = source.split("      - name: Select reviewed scheduled work\n", 1)[1]
        step = step.split("      - name: Restore exact-revision evidence\n", 1)[0]
        self.assertIn("if: github.event_name == 'schedule'", step)
        self.assertIn("env:\n          GH_TOKEN: ${{ github.token }}", step)
        self.assertIn("gh api --method GET", step)
        self.assertIn("pulls?state=open&per_page=100", step)
        self.assertIn("map({number, state, head_ref: .head.ref})", step)
        self.assertNotIn("curl --disable", step)
        self.assertIn("scripts/ai-pilot/scheduled_select.py", step)

    def test_model_runner_does_not_receive_gh_token(self):
        source = WORKFLOW.read_text(encoding="utf-8")
        run = source.split("      - name: Run the reviewed queue\n", 1)[1]
        run = run.split("      - name: Show task outcomes\n", 1)[0]
        self.assertNotIn("GH_TOKEN", run)
        self.assertIn("run-queue.sh", run)
        # The token is scoped to the selector step, not job/global env.
        self.assertNotRegex(
            source.split("      - name: Check reviewed entrypoint\n", 1)[0],
            r"(?m)^\s*GH_TOKEN:",
        )


if __name__ == "__main__":
    unittest.main()
