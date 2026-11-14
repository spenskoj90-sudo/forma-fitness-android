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


ALLOWED = (
    ("workout-date", SESSION, WORKOUT_DATE),
    ("backup-replay", BACKUP, ORIGINAL),
)


class ScheduleError(ValueError):
    pass


def choose_package(repo: Path, revision: str, pull_requests: object) -> tuple[str, str]:
    """Return (package, reason); fail closed when any draft/review is outstanding."""
    if not re.fullmatch(r"[0-9a-f]{40}", revision):
        raise ScheduleError("invalid revision")
    if not isinstance(pull_requests, list) or len(pull_requests) > 100:
        raise ScheduleError("invalid PR snapshot")
    if any(not isinstance(pr, dict) or not isinstance(pr.get("number"), int)
           or isinstance(pr.get("number"), bool) or pr["number"] < 1
           or pr.get("state") != "open" for pr in pull_requests):
        raise ScheduleError("invalid open PR entry")
    if len({pr["number"] for pr in pull_requests}) != len(pull_requests):
        raise ScheduleError("duplicate PR entries")
    if pull_requests:
        return "smoke", "open PRs: product changes require existing-branch review"
    for name, path, marker in ALLOWED:
        try:
            source = subprocess.check_output(
                ["git", "show", f"{revision}:{path}"], cwd=repo,
                text=True, stderr=subprocess.DEVNULL, timeout=8)
        except (subprocess.CalledProcessError, FileNotFoundError, subprocess.TimeoutExpired):
            continue
        if source.count(marker) == 1:
            return name, f"exact-revision reviewed source matches {name}"
    return "smoke", "no open reviewed product package on this revision"


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
