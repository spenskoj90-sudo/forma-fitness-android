"""Offline regression checks for the synthetic pilot's restart and failure paths."""
import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock
from urllib.error import HTTPError

import run

BASE = "a" * 40
MODEL = run.MODELS[0]


class PilotTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.output = Path(directory.name)
        self.enterContext(contextlib.redirect_stderr(io.StringIO()))

    def execute(self, base=BASE, model=MODEL):
        return run.run(self.output, base, 1, model)

    def checkpoint(self):
        return json.loads((self.output / "checkpoint.json").read_text())

    def test_quota_is_distinct_from_unavailable_network(self):
        for error, code in [(HTTPError(None, 429, "", None, None), "PROVIDER_QUOTA"),
                            (OSError(), "PROVIDER_UNREACHABLE")]:
            with self.subTest(code=code), mock.patch("urllib.request.OpenerDirector.open", side_effect=error):
                self.assertEqual(self.execute(), 1)
                self.assertEqual(self.checkpoint()["error"], code)
                self.assertEqual(self.checkpoint()["checks"], "NOT_RUN")
                self.assertFalse((self.output / "candidate.patch").exists())

    def test_failed_checks_allow_a_new_model(self):
        with mock.patch.object(run, "propose", return_value="max(0, n)"):
            self.assertEqual(self.execute(), 1)
        self.assertEqual(self.checkpoint()["checks"], "FAIL")
        with mock.patch.object(run, "propose", return_value="min(9, max(0, n))"):
            self.assertEqual(self.execute(model=run.MODELS[1]), 0)
        self.assertEqual(self.checkpoint()["model"], run.MODELS[1])

    def test_interruption_after_edit_resumes_without_provider(self):
        with mock.patch.object(run, "propose", return_value="min(9, max(0, n))"):
            with mock.patch.object(run, "verify_source", side_effect=KeyboardInterrupt):
                with self.assertRaises(KeyboardInterrupt):
                    self.execute()
        self.assertEqual(self.checkpoint()["next"], "verify_candidate")
        with mock.patch.object(run, "propose", side_effect=AssertionError("provider must not run")):
            self.assertEqual(self.execute(model=run.MODELS[1]), 0)
        self.assertEqual(self.checkpoint()["model"], MODEL)

    def test_wrong_base_keeps_original_evidence(self):
        with mock.patch.object(run, "propose", return_value="min(9, max(0, n))"):
            self.assertEqual(self.execute(), 0)
        previous = self.checkpoint()
        self.assertEqual(self.execute("b" * 40), 1)
        self.assertEqual(self.checkpoint(), previous)

    def test_disallowed_edit_is_never_executed_or_persisted(self):
        with mock.patch.object(run, "propose", return_value='__import__("os").system("id")'):
            self.assertEqual(self.execute(), 1)
        self.assertEqual(self.checkpoint()["error"], "INVALID_EDIT")
        self.assertNotIn("expression", self.checkpoint())


if __name__ == "__main__":
    unittest.main()
