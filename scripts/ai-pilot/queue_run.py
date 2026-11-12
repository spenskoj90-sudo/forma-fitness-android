"""Run one reviewed FORMA work package with local models and durable checkpoints."""

import argparse
from dataclasses import dataclass
import fcntl
import hashlib
import http.client
import json
from pathlib import Path
import re
import subprocess
import sys
import urllib.error
import urllib.request

from run import MODELS, NoRedirect, PilotFailure, write_atomic
from queue_tasks import make_tasks


@dataclass(frozen=True)
class Task:
    name: str
    dependencies: tuple
    verify: object
    brief: str = ""
    blocker: str = ""


class CheckFailure(Exception):
    def __init__(self, phase, detail):
        super().__init__(f"{phase}: {detail}")
        self.phase = phase


def valid_candidate(value):
    return isinstance(value, str) and 0 < len(value) <= 450 and value.isascii()


def sha256(value):
    return hashlib.sha256(value.encode()).hexdigest()


class WorkQueue:
    def __init__(self, root, revision, tasks, propose, review):
        self.root = root
        self.tasks = tasks
        self.propose = propose
        self.review = review
        self.checkpoint = root / "queue.json"
        self.identity = {"version": 1, "base_sha": revision,
                         "plan": {task.name: list(task.dependencies) for task in tasks}}
        self.records = {task.name: {"status": "NOT_RUN", "checks": "NOT_RUN",
                                    "review": "NOT_RUN", "history": []} for task in tasks}
        if self.checkpoint.exists():
            self.restore()

    def restore(self):
        try:
            saved = json.loads(self.checkpoint.read_text())
            if saved["identity"] != self.identity or saved["records"].keys() != self.records.keys():
                raise ValueError
            for name, record in saved["records"].items():
                if record["status"] not in {"PASS", "FAIL", "NOT_RUN"}:
                    raise ValueError
                if "candidate" in record and not valid_candidate(record["candidate"]):
                    raise ValueError
                if len(record["history"]) > 4:
                    raise ValueError
                if record["status"] == "PASS":
                    if record["checks"] != "PASS" or record["review"] != "PASS":
                        raise ValueError
                    if sha256((self.root / f"{name}.patch").read_text()) != record["patch_sha256"]:
                        raise ValueError
            self.records = saved["records"]
        except (ValueError, KeyError, TypeError, AttributeError, OSError, RecursionError):
            raise PilotFailure("CHECKPOINT_MISMATCH") from None

    def save(self):
        write_atomic(self.checkpoint, json.dumps(
            {"identity": self.identity, "records": self.records}, indent=2) + "\n")

    def attempt(self, task):
        record = self.records[task.name]
        if task.blocker:
            record.update(status="FAIL", feedback=task.blocker)
            record.pop("candidate", None)
            self.save()
            return
        history = record["history"]
        model = MODELS[min(len(history) // 2, 1)]
        reviewer = MODELS[1 - MODELS.index(model)]
        record.update(checks="NOT_RUN", review="NOT_RUN")
        stage = "proposal"
        candidate = record.get("candidate")
        try:
            if candidate is None:
                candidate = self.propose(task, model, record.get("feedback", ""))
                if not valid_candidate(candidate):
                    raise PilotFailure("INVALID_RESPONSE")
                record["candidate"] = candidate
                self.save()
            stage = "checks"
            patch = task.verify(candidate)
            record["checks"] = "PASS"
            stage = "review"
            if not self.review(task, reviewer, patch):
                raise CheckFailure("review", "Independent review rejected the candidate")
            write_atomic(self.root / f"{task.name}.patch", patch)
            record.update(status="PASS", review="PASS", patch_sha256=sha256(patch))
            record.pop("feedback", None)
            record.pop("candidate", None)
            history.append({"model": model, "reviewer": reviewer, "result": "PASS"})
        except (CheckFailure, PilotFailure) as error:
            if stage != "proposal":
                record[stage] = "FAIL"
            diagnostic = str(error)[:1200]
            record["feedback"] = (
                f"{diagnostic}\nPrevious expression: {candidate}" if candidate is not None else diagnostic
            )
            record.pop("candidate", None)
            history.append({"model": model, "reviewer": reviewer if stage == "review" else None,
                            "stage": stage, "result": str(error).split(":", 1)[0]})
            if stage == "proposal" and str(error).startswith("PROVIDER_"):
                if len(history) == 1:
                    # Advance straight to the other provider on a transport failure.
                    history.append({"model": model, "result": "SKIPPED_PROVIDER_FAILURE"})
                elif model == MODELS[1]:
                    record["status"] = "FAIL"
            if len(history) >= 4:
                record["status"] = "FAIL"
        self.save()

    def run(self, steps=16):
        self.save()
        for _ in range(steps):
            ready = (task for task in self.tasks
                     if self.records[task.name]["status"] == "NOT_RUN"
                     and all(self.records[dep]["status"] == "PASS" for dep in task.dependencies))
            task = min(ready, key=lambda item: len(self.records[item.name]["history"]), default=None)
            if task is None:
                break
            self.attempt(task)


def complete(port, pid, model, system, prompt, schema, max_tokens):
    listeners = subprocess.check_output(["ss", "-H", "-lptn", f"sport = :{port}"], text=True)
    if not pid or f"pid={pid}," not in listeners:
        raise PilotFailure("PROVIDER_UNREACHABLE")
    request = urllib.request.Request(
        f"http://127.0.0.1:{port}/v1/chat/completions",
        data=json.dumps({
            "model": model, "temperature": 0, "max_tokens": max_tokens,
            "messages": [{"role": "system", "content": system},
                         {"role": "user", "content": prompt}],
            "response_format": {"type": "json_schema", "json_schema": {
                "name": "result", "strict": True, "schema": schema}},
        }).encode(),
        headers={"Content-Type": "application/json"},
    )
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    try:
        with opener.open(request, timeout=60) as response:
            data = response.read(16385)
    except urllib.error.HTTPError as error:
        raise PilotFailure("PROVIDER_QUOTA" if error.code == 429 else "PROVIDER_HTTP") from None
    except (OSError, http.client.HTTPException):
        raise PilotFailure("PROVIDER_UNREACHABLE") from None
    try:
        if len(data) > 16384:
            raise ValueError
        answer = json.loads(json.loads(data)["choices"][0]["message"]["content"])
        field = schema["required"][0]
        if not isinstance(answer, dict) or set(answer) != {field}:
            raise ValueError
        return answer[field]
    except (ValueError, KeyError, TypeError, IndexError, RecursionError):
        raise PilotFailure("INVALID_RESPONSE") from None


def schema(field, kind):
    return {"type": "object", "properties": {field: {"type": kind}},
            "required": [field], "additionalProperties": False}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--revision", required=True)
    parser.add_argument("--package", choices=("smoke", "backup-replay"), required=True)
    parser.add_argument("--primary-port", type=int, default=18787)
    parser.add_argument("--reserve-port", type=int, default=18788)
    parser.add_argument("--primary-pid", type=int, default=0)
    parser.add_argument("--reserve-pid", type=int, default=0)
    parser.add_argument("--steps", type=int, default=16, choices=range(1, 17))
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repo, text=True).strip()
    ports = dict(zip(MODELS, (args.primary_port, args.reserve_port)))
    pids = dict(zip(MODELS, (args.primary_pid, args.reserve_pid)))
    if (not re.fullmatch(r"[a-f0-9]{40}", args.revision) or args.revision != head
            or len(set(ports.values())) != 2 or any(not 1 <= port <= 65535 for port in ports.values())):
        parser.error("expected exact checkout SHA and two distinct valid TCP ports")
    if subprocess.run(["git", "diff", "--quiet", head, "--", "scripts/ai-pilot"], cwd=repo).returncode:
        parser.error("queue implementation differs from the expected commit")

    def propose(task, model, feedback):
        return complete(ports[model], pids[model], model, "You are the sole code writer. Return JSON.",
                          f"{task.brief}\nPrevious diagnostic, if any:\n{feedback or '(none)'}",
                          schema("expression", "string"), 512)

    def review(task, model, patch):
        return complete(
            ports[model], pids[model], model,
            "You are the independent read-only reviewer. Reject concrete violations of the task. "
            "Return JSON with approved=true if the patch meets the criteria.",
            f"{task.brief}\nCandidate diff:\n{patch}", schema("approved", "boolean"), 64,
        ) is True

    args.output.mkdir(mode=0o700, parents=True, exist_ok=True)
    with (args.output / ".writer").open("a") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise PilotFailure("WRITER_BUSY") from None
        queue = WorkQueue(args.output, args.revision, make_tasks(repo, args.revision, args.package, Task, CheckFailure),
                          propose, review)
        queue.run(args.steps)
        for name, record in queue.records.items():
            print(f"{name}: {record['status']} (checks {record['checks']}, review {record['review']})")
        return int(any(record["status"] != "PASS" for record in queue.records.values()))


if __name__ == "__main__":
    try:
        sys.exit(main())
    except PilotFailure as error:
        sys.exit(str(error))
