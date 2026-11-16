"""Verify a staged FORMA model task is explicitly scoped and schema-aligned.

No model, Android SDK, GitHub token or network access is needed.
"""
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts" / "ai-pilot"))
sys.path.insert(0, str(ROOT / "scripts"))

from queue_tasks import (
    PROGRESS, PROGRESS_FILTER, kotlin_expression, progress_chronology_task,
)
from scheduled_select import ALLOWED
from verify_schedule_evidence import PLANS, PATHS
from pilot_safe_output import SOURCES, PLANS as PROPOSAL_PLANS
from pilot_task_state import TASKS


class Failure(Exception):
    def __init__(self, stage, message):
        super().__init__(stage + ": " + message)


class ProgressChronologyCatalogTests(unittest.TestCase):
    def test_target_and_task_id_identical_across_all_read_only_contracts(self):
        task_id = "forma-progress-chronology-v1"
        package = "progress-chronology"
        self.assertEqual(PATHS[package], PROGRESS)
        self.assertEqual(SOURCES[package], (package, PROGRESS))
        self.assertEqual(PLANS[package], {package: []})
        self.assertEqual(PROPOSAL_PLANS[package], PLANS[package])
        self.assertEqual(TASKS[task_id]["package"], package)
        self.assertEqual(TASKS[task_id]["path"], PROGRESS)
        self.assertEqual(TASKS[task_id]["occupied_prs"], ())
        matches = [x for x in ALLOWED if x[0] == package]
        self.assertEqual(len(matches), 1)
        self.assertEqual(matches[0][1:4], (task_id, PROGRESS, PROGRESS_FILTER))
        self.assertIn("pilot/task-" + task_id, matches[0][5])

    def test_exact_source_marker_still_exists_once(self):
        source = (ROOT / PROGRESS).read_text(encoding="utf-8")
        self.assertEqual(source.count(PROGRESS_FILTER), 1)
        self.assertIn(".filter { " + PROGRESS_FILTER + " }", source)

    def test_generated_patch_only_changes_chronology_filter(self):
        source = (ROOT / PROGRESS).read_text(encoding="utf-8")
        with patch("queue_tasks.subprocess.check_output", return_value=source):
            verifier, brief = progress_chronology_task(ROOT, "a" * 40, Failure)
        captured = []
        with patch("queue_tasks.verify_domain", side_effect=lambda *args: captured.append(args)):
            result = verifier("it.finishedMs >= it.startedMs")
        self.assertIn(b"+", result.encode())
        self.assertTrue(result.startswith("--- a/" + PROGRESS + "\n"))
        self.assertEqual(len(captured), 1)
        self.assertEqual(captured[0][2], PROGRESS)
        self.assertEqual(captured[0][4], "ProgressChronologyTest.kt")
        self.assertIn("it.status == Phase.DONE && (it.finishedMs >= it.startedMs)", captured[0][3])
        self.assertIn("finishedMs", brief)
        self.assertNotIn("it.status == Phase.DONE && (it.finishedMs >= it.startedMs)",
                         source)

    def test_rejects_unapproved_properties_and_changed_base(self):
        source = (ROOT / PROGRESS).read_text(encoding="utf-8")
        with patch("queue_tasks.subprocess.check_output", return_value=source):
            verifier, _ = progress_chronology_task(ROOT, "a" * 40, Failure)
        with self.assertRaises(Failure):
            verifier("it.finishedMs >= it.anotherTimestamp")
        with patch("queue_tasks.subprocess.check_output",
                   return_value=source.replace(PROGRESS_FILTER, "already fixed")):
            with self.assertRaisesRegex(Failure, "base"):
                progress_chronology_task(ROOT, "a" * 40, Failure)

    def test_kotlin_expression_policy_rejects_command_like_source(self):
        for candidate in ("System.exit(0)", "it.finishedMs; println(1)", "it.finishedMs = 0"):
            with self.subTest(candidate=candidate), self.assertRaises(Failure):
                kotlin_expression(candidate, Failure, {"it", "finishedMs", "startedMs"})


if __name__ == "__main__":
    unittest.main()
