"""Read-only provenance gate for dispatched Android/backend tests on one PILOT PR.

GitHub GITHUB_TOKEN-created PR events may need manual workflow approval; a
repository_dispatch event (Contents:write) can trigger the SAME existing CI
workflows without actions:write. Its checks are attached to the dispatch event
on default main, NOT to the product PR head; preserve that distinction.
"""
import argparse
import json
import os
from pathlib import Path
import re
import sys

from trusted_artifacts import REPO, TASKS, TrustError, sha256
from trusted_github import GitHubClient
from trusted_publish import content_from_ref

SHA = re.compile(r"[0-9a-f]{40}\Z")
DIGEST = re.compile(r"[0-9a-f]{64}\Z")
FIELDS = {"task_id", "pr_number", "head_sha", "base_sha",
          "patch_sha256", "source_run_id"}


def validate_dispatch(event, pr, branch, state, raw_patch, run, actual_main):
    if (not isinstance(event, dict) or event.get("action") != "pilot-draft-ci"
            or (event.get("repository") or {}).get("full_name") != REPO):
        raise TrustError("unrecognized repository dispatch source")
    payload = event.get("client_payload")
    if not isinstance(payload, dict) or set(payload) != FIELDS:
        raise TrustError("unexpected dispatch payload schema")
    task = payload.get("task_id")
    if task not in (v[0] for v in TASKS.values()):
        raise TrustError("task ID is not in the reviewed catalog")
    if (type(payload["pr_number"]) is not int or payload["pr_number"] < 1 or
            type(payload["source_run_id"]) is not int or payload["source_run_id"] < 1 or
            any(not isinstance(payload[name], str) or not SHA.fullmatch(payload[name])
                for name in ("head_sha", "base_sha")) or
            not isinstance(payload["patch_sha256"], str) or
            not DIGEST.fullmatch(payload["patch_sha256"])):
        raise TrustError("invalid dispatch digest or immutable SHA")
    target = "pilot/task-" + task
    if (not isinstance(pr, dict) or pr.get("number") != payload["pr_number"] or
            pr.get("state") != "open" or pr.get("draft") is not True or
            (pr.get("head") or {}).get("ref") != target or
            (pr.get("head") or {}).get("sha") != payload["head_sha"] or
            (pr.get("head") or {}).get("repo", {}).get("full_name", REPO) != REPO or
            (pr.get("base") or {}).get("ref") != "main" or
            (pr.get("base") or {}).get("sha") != payload["base_sha"]):
        raise TrustError("PR does not match immutable dispatch candidate")
    if not branch or branch.get("object", {}).get("sha") != payload["head_sha"]:
        raise TrustError("task branch ref changed after review")
    if not isinstance(state, dict) or state.get("schema") != 1 or state.get("status") != "PUBLISHED":
        raise TrustError("task checkpoint not published")
    for key, value in (
        ("task_id", task),
        ("base_sha", payload["base_sha"]),
        ("patch_sha256", payload["patch_sha256"]),
        ("pr_head_sha", payload["head_sha"]),
        ("pr_number", payload["pr_number"]),
        ("run_id", payload["source_run_id"]),
    ):
        if state.get(key) != value:
            raise TrustError("persisted task checkpoint differs from dispatch")
    if not isinstance(raw_patch, bytes) or sha256(raw_patch) != payload["patch_sha256"]:
        raise TrustError("persisted patch hash changed")
    if (actual_main != payload["base_sha"] or
            not isinstance(run, dict) or run.get("id") != payload["source_run_id"] or
            run.get("event") != "schedule" or
            run.get("status") != "completed" or
            run.get("conclusion") != "success" or
            run.get("head_sha") != payload["base_sha"] or
            run.get("head_branch") != "main" or
            (run.get("head_repository") or {}).get("full_name") != REPO):
        raise TrustError("original schedule or current protected main no longer valid")
    return payload


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--event", type=Path, required=True)
    args = parser.parse_args()
    try:
        event = json.loads(args.event.read_text(encoding="utf-8"))
        if os.environ.get("GITHUB_EVENT_NAME") != "repository_dispatch":
            raise TrustError("only repository_dispatch is admitted")
        payload = event.get("client_payload") if isinstance(event, dict) else None
        if not isinstance(payload, dict) or type(payload.get("pr_number")) is not int:
            raise TrustError("invalid dispatch payload")
        task = payload.get("task_id")
        if task not in (v[0] for v in TASKS.values()):
            raise TrustError("unreviewed task ID")
        api = GitHubClient()
        pr = api.get("/pulls/" + str(payload["pr_number"]))
        branch = api.branch_ref("pilot/task-" + task)
        state_bytes = content_from_ref(api, "pilot-state/" + task, "pilot-state.json")
        state = json.loads(state_bytes)
        patch = content_from_ref(api, "pilot-state/" + task, "pilot-patch.diff")
        run = api.run(payload["source_run_id"])
        accepted = validate_dispatch(
            event, pr, branch, state, patch, run, api.main_sha())
        summary = Path(os.environ.get("GITHUB_STEP_SUMMARY", "/dev/null"))
        with summary.open("a") as f:
            f.write(
                "## PILOT repository_dispatch exact-source verification\n" +
                "Source schedule run: " + str(accepted["source_run_id"]) + "\n" +
                "Task ID: " + accepted["task_id"] + "\n" +
                "Draft PR: #" + str(accepted["pr_number"]) + "\n" +
                "**Code actually checked out and tested:** " + accepted["head_sha"] +
                "\nThese checks are linked to the dispatch workflow run on main; " +
                "do not claim they are native required PR-head status contexts.\n")
        print("PILOT_DISPATCH_SOURCE_PASS " + accepted["head_sha"])
        return 0
    except (TrustError, OSError, ValueError, TypeError, AttributeError, KeyError) as e:
        print("PILOT_DISPATCH_SOURCE_FAIL: " + str(e)[:240], file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
