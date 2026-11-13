import os
from pathlib import Path
import socket
import tempfile
import unittest

from local_socket import owns_listener
from queue_run import WorkQueue, Task, CheckFailure
from run import MODELS, PilotFailure


class WorkQueueTest(unittest.TestCase):
    def test_repair_review_fallback_dependencies_and_restart(self):
        events = []

        def propose(task, model, feedback):
            events.append((task.name, model, feedback))
            if task.name == "blocked":
                raise PilotFailure("PROVIDER_QUOTA")
            return "bad" if feedback == "" else "good"

        def verify(candidate):
            if candidate == "bad":
                raise CheckFailure("compile", "type mismatch")
            return "verified patch"

        def review(task, model, patch):
            events.append(("review", model, patch))
            return True

        tasks = [Task("blocked", (), verify), Task("dependent", ("blocked",), verify),
                 Task("independent", (), verify), Task("after", ("independent",), verify)]
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            first = WorkQueue(root, "a" * 40, tasks, propose, review)
            first.run(steps=1)
            self.assertEqual(first.records["blocked"]["status"], "NOT_RUN")
            self.assertEqual(first.records["independent"]["status"], "NOT_RUN")
            second = WorkQueue(root, "a" * 40, tasks, propose, review)
            second.run()
            self.assertEqual(second.records["blocked"]["status"], "FAIL")
            self.assertEqual(second.records["dependent"]["status"], "NOT_RUN")
            self.assertEqual(second.records["independent"]["status"], "PASS")
            self.assertEqual(second.records["after"]["status"], "PASS")
            self.assertIn(("independent", MODELS[0], "compile: type mismatch\nPrevious expression: bad"), events)
            self.assertIn(("review", MODELS[1], "verified patch"), events)
            self.assertTrue((root / "independent.patch").is_file())

    def test_rejects_checkpoint_from_a_different_commit(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            tasks = [Task("one", (), lambda candidate: candidate)]
            WorkQueue(root, "a" * 40, tasks, lambda *_: "patch", lambda *_: True).run()
            with self.assertRaisesRegex(PilotFailure, "CHECKPOINT_MISMATCH"):
                WorkQueue(root, "b" * 40, tasks, None, None)

    def test_review_rejection_returns_to_writer(self):
        proposals = []

        def propose(task, model, feedback):
            proposals.append(feedback)
            return "second" if feedback else "first"

        with tempfile.TemporaryDirectory() as tmp:
            queue = WorkQueue(Path(tmp), "a" * 40,
                              [Task("one", (), lambda value: value)],
                              propose, lambda _, __, patch: patch != "first")
            queue.run()
            self.assertEqual(queue.records["one"]["status"], "PASS")
            self.assertIn("review:", proposals[1])

    def test_unavailable_reviewer_preserves_candidate_and_allows_other_work(self):
        proposed = []

        def propose(task, *_):
            proposed.append(task.name)
            return task.name

        def review(_, __, patch):
            if patch == "deferred":
                raise PilotFailure("PROVIDER_UNREACHABLE")
            return True

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            tasks = [Task(name, (), lambda value: value) for name in ("deferred", "other")]
            first = WorkQueue(root, "a" * 40, tasks, propose, review)
            first.run()
            self.assertEqual(first.records["deferred"]["checks"], "PASS")
            self.assertEqual(first.records["deferred"]["review"], "NOT_RUN")
            self.assertEqual(first.records["deferred"]["status"], "NOT_RUN")
            self.assertEqual(first.records["other"]["status"], "PASS")
            self.assertFalse((root / "deferred.patch").exists())
            resumed = WorkQueue(root, "a" * 40, tasks, propose, lambda *_: True)
            resumed.run()
            self.assertEqual(resumed.records["deferred"]["status"], "PASS")
            self.assertEqual(proposed, ["deferred", "other"])

    def test_attempts_bound_provider_failures(self):
        models = []

        def propose(task, model, feedback):
            models.append(model)
            raise PilotFailure("PROVIDER_UNREACHABLE")

        with tempfile.TemporaryDirectory() as tmp:
            queue = WorkQueue(Path(tmp), "a" * 40,
                              [Task("one", (), lambda _: self.fail("unexpected verification"))],
                              propose, lambda *_: self.fail("unexpected review"))
            queue.run()
            self.assertEqual(models, list(MODELS))
            self.assertEqual(queue.records["one"]["checks"], "NOT_RUN")


    def test_interrupted_verification_reuses_saved_candidate(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)

            def interrupt(_):
                raise KeyboardInterrupt

            task = Task("one", (), interrupt)
            with self.assertRaises(KeyboardInterrupt):
                WorkQueue(root, "a" * 40, [task], lambda *_: "saved", None).run()

            def verify(candidate):
                self.assertEqual(candidate, "saved")
                return "patch"

            task = Task("one", (), verify)
            resumed = WorkQueue(root, "a" * 40, [task],
                                lambda *_: self.fail("proposal already saved"), lambda *_: True)
            resumed.run()
            self.assertEqual(resumed.records["one"]["status"], "PASS")

    def test_failed_preflight_skips_model_and_runs_independent_task(self):
        tasks = [Task("blocked", (), None, blocker="Kotlin unavailable"),
                 Task("independent", (), lambda value: value)]
        proposed = []

        def propose(task, *_):
            proposed.append(task.name)
            return "patch"

        with tempfile.TemporaryDirectory() as tmp:
            queue = WorkQueue(Path(tmp), "a" * 40, tasks, propose, lambda *_: True)
            queue.run()
            self.assertEqual(proposed, ["independent"])
            self.assertEqual(queue.records["blocked"]["status"], "FAIL")
            self.assertEqual(queue.records["blocked"]["checks"], "NOT_RUN")
            resumed_tasks = [Task("blocked", (), lambda value: value), tasks[1]]
            resumed = WorkQueue(Path(tmp), "a" * 40, resumed_tasks, propose, lambda *_: True)
            resumed.run()
            self.assertEqual(resumed.records["blocked"]["status"], "PASS")
            self.assertEqual(proposed, ["independent", "blocked"])

    def test_non_ascii_response_cannot_poison_checkpoint(self):
        proposed = iter(["\ud800", "valid"])
        with tempfile.TemporaryDirectory() as tmp:
            queue = WorkQueue(Path(tmp), "a" * 40, [Task("one", (), lambda value: value)],
                              lambda *_: next(proposed), lambda *_: True)
            queue.run()
            self.assertEqual(queue.records["one"]["history"][0]["result"], "INVALID_RESPONSE")
            self.assertEqual(queue.records["one"]["status"], "PASS")

    def test_requires_the_owned_loopback_listener(self):
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            listener.listen()
            port = listener.getsockname()[1]
            self.assertTrue(owns_listener(os.getpid(), port))
            self.assertFalse(owns_listener(0, port))
        self.assertFalse(owns_listener(os.getpid(), port))
