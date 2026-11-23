"""No LLM, credentials, network, or Android SDK needed for these tests."""

import pathlib
import subprocess
import unittest
from unittest.mock import patch

from scheduled_select import ScheduleError, choose_package
from queue_tasks import BACKUP, ORIGINAL, SESSION, WORKOUT_DATE

SHA = "a" * 40


class ScheduledSelectionTest(unittest.TestCase):
    def choose(self, prs, sources=None):
        sources = sources if sources is not None else {SESSION: WORKOUT_DATE, BACKUP: ORIGINAL}

        def git_show(args, **kwargs):
            key = args[-1].split(":", 1)[1]
            if key not in sources:
                raise subprocess.CalledProcessError(1, args)
            return sources[key]

        with patch("scheduled_select.subprocess.check_output", side_effect=git_show) as check:
            result = choose_package(pathlib.Path("/nonexistent"), SHA, prs)
        return result, check.call_count

    def test_occupied_task_does_not_freeze_unrelated_task(self):
        (name, reason), calls = self.choose([{"number": 21, "state": "open"}])
        self.assertEqual(name, "backup-replay")
        self.assertIn("unclaimed reviewed task", reason)
        self.assertEqual(calls, 1)

    def test_both_preapproved_tasks_occupied_fall_back_to_smoke(self):
        (name, reason), calls = self.choose([
            {"number": 21, "state": "open"}, {"number": 15, "state": "open"}])
        self.assertEqual(name, "smoke")
        self.assertIn("occupied", reason)
        self.assertEqual(calls, 0)

    def test_unrelated_open_pr_does_not_block(self):
        self.assertEqual(self.choose([{"number": 13, "state": "open"}])[0][0],
                         "workout-date")

    def test_matching_branch_claim_blocks_task_even_with_new_pr_number(self):
        prs = [{"number": 999, "state": "open",
                "head_ref": "pilot/task-forma-workout-finish-date-v1"}]
        self.assertEqual(self.choose(prs)[0][0], "backup-replay")


    def test_no_pr_selects_first_matching_reviewed_package(self):
        self.assertEqual(self.choose([])[0][0], "workout-date")

    def test_second_reviewed_package_after_first_source_changed(self):
        self.assertEqual(self.choose([], {SESSION: "already fixed", BACKUP: ORIGINAL})[0][0],
                         "backup-replay")

    def test_completed_sources_degrade_to_smoke(self):
        self.assertEqual(self.choose([], {SESSION: "fixed", BACKUP: "fixed"})[0][0],
                         "smoke")

    def test_missing_sources_degrade_to_smoke(self):
        self.assertEqual(self.choose([], {})[0][0], "smoke")

    def test_malformed_and_duplicated_pr_metadata_rejected(self):
        cases = [None, {}, [1], [{"number": 21}],
                 [{"number": 21, "state": "closed"}],
                 [{"number": 21, "state": "open"}] * 2,
                 [{"number": True, "state": "open"}],
                 [{"number": -1, "state": "open"}]]
        for entry in cases:
            with self.subTest(entry=entry), self.assertRaises(ScheduleError):
                self.choose(entry)

    def test_invalid_revisions_rejected(self):
        with self.assertRaisesRegex(ScheduleError, "invalid revision"):
            choose_package(pathlib.Path("."), "main", [])
        with self.assertRaisesRegex(ScheduleError, "invalid revision"):
            choose_package(pathlib.Path("."), "A" * 40, [])

    def test_too_many_prs_fail_closed(self):
        with self.assertRaises(ScheduleError):
            self.choose([{"number": n, "state": "open"} for n in range(1, 102)])


if __name__ == "__main__":
    unittest.main()
