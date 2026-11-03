import unittest
from pathlib import Path
import runpy

_GATE = runpy.run_path(str(Path(__file__).with_name('check-release-ci.py')))
evaluate, REQUIRED = _GATE['evaluate'], _GATE['REQUIRED']

SHA = "a" * 40


def check(name, outcome="success", identifier=1, sha=SHA, app_id=15368):
    return {"name": name, "status": "completed", "conclusion": outcome,
            "id": identifier, "head_sha": sha, "app": {"id": app_id}}


class ReleaseCiSourceTests(unittest.TestCase):
    def test_all_exact_source_checks_required(self):
        self.assertTrue(all(evaluate([check(x) for x in REQUIRED], SHA).values()))

    def test_missing_failed_wrong_sha_and_wrong_app_fail(self):
        complete = [check(x) for x in REQUIRED]
        self.assertFalse(all(evaluate(complete[:-1], SHA).values()))
        self.assertFalse(all(evaluate(complete[:-1] + [check(complete[-1]["name"], "failure")], SHA).values()))
        self.assertFalse(all(evaluate([check(x, sha="b" * 40) for x in REQUIRED], SHA).values()))
        self.assertFalse(all(evaluate([check(x, app_id=5) for x in REQUIRED], SHA).values()))

    def test_later_failure_overrules_earlier_success(self):
        complete = [check(x) for x in REQUIRED]
        complete.append(check("verify", "failure", identifier=2))
        self.assertFalse(evaluate(complete, SHA)["verify"])


if __name__ == "__main__":
    unittest.main()
