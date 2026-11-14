"""Fail-closed, read-only scheduling for already reviewed FORMA work packages.

The file contents and package mapping are maintained by a protected-main review.
Untrusted PR metadata never becomes an agent instruction or a shell command.
"""

import argparse
import json
from pathlib import Path
import re
import subprocess
import sys

from queue_tasks import BACKUP, ORIGINAL, SESSION, WORKOUT_DATE


# All entries are reviewed source-controlled packages. Their task identities and
# existing PR aliases are used only for de-duplication, never as model prompts.
# A different, unrelated PR must not freeze the queue.
ALLOWED = (
    ("workout-date", "forma-workout-finish-date-v1", SESSION, WORKOUT_DATE,
     frozenset({21}), frozenset({"forma/workout-finish-date-20261113",
                               "pilot/task-forma-workout-finish-date-v1"})),
    ("backup-replay", "forma-backup-completed-sets-v1", BACKUP, ORIGINAL,
     frozenset({15}), frozenset({"forma/backup-completed-sets-20261112",
                               "pilot/task-forma-backup-completed-sets-v1"})),
)


class ScheduleError(ValueError):
    pass


def choose_package(repo: Path, revision: str, pull_requests: object) -> tuple[str, str]:
    """Return (package, reason); only an occupied matching task blocks itself."""
    if not re.fullmatch(r"[0-9a-f]{40}", revision):
        raise ScheduleError("invalid revision")
    if not isinstance(pull_requests, list) or len(pull_requests) >= 100:
        raise ScheduleError("invalid PR snapshot")
    if any(not isinstance(pr, dict) or not isinstance(pr.get("number"), int)
           or isinstance(pr.get("number"), bool) or pr["number"] < 1
           or pr.get("state") != "open" for pr in pull_requests):
        raise ScheduleError("invalid open PR entry")
    if len({pr["number"] for pr in pull_requests}) != len(pull_requests):
        raise ScheduleError("duplicate PR entries")
    for name, task_id, path, marker, known_numbers, known_branches in ALLOWED:
        occupied = any(
            pr["number"] in known_numbers or
            pr.get("head_ref") in known_branches or
            (isinstance(pr.get("head_ref"), str) and
             pr["head_ref"] == f"pilot/task-{task_id}")
            for pr in pull_requests
        )
        if occupied:
            continue
        try:
            source = subprocess.check_output(
                ["git", "show", f"{revision}:{path}"], cwd=repo,
                text=True, stderr=subprocess.DEVNULL, timeout=8)
        except (subprocess.CalledProcessError, FileNotFoundError, subprocess.TimeoutExpired):
            continue
        if source.count(marker) == 1:
            return name, f"unclaimed reviewed task {task_id} on exact revision"
    return "smoke", "reviewed packages are occupied or do not match this exact source"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--revision", required=True)
    parser.add_argument("--prs", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repo, text=True).strip()
    if head != args.revision:
        raise ScheduleError("checkout revision mismatch")
    if not args.prs.is_file() or args.prs.stat().st_size > 200_000:
        raise ScheduleError("PR snapshot missing or too large")
    prs = json.loads(args.prs.read_text(encoding="utf-8"))
    package, reason = choose_package(repo, args.revision, prs)
    payload = {"schema": 1, "base_sha": args.revision, "package": package,
               "reason": reason, "open_pr_count": len(prs),
               "authorization": "read-only-reviewed-package"}
    args.output.write_text(json.dumps(payload, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    print(f"Selected reviewed package: {package} ({reason})")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ScheduleError, json.JSONDecodeError) as exc:
        sys.exit(f"SCHEDULE_REJECTED: {exc}")
