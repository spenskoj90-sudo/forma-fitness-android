"""Verify the selected schedule and queue artifacts, never the model's authority.

Runs entirely without network, credentials, subprocesses or write permissions.
The caller MUST separately confirm GitHub API workflow run event and head SHA;
this script checks only GitHub runner inputs and local artifact consistency.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys

REPO = "spenskoj90-sudo/forma-fitness-android"
SHA = re.compile(r"[0-9a-f]{40}\Z")
DIGEST = re.compile(r"[0-9a-f]{64}\Z")
RUN_ID = re.compile(r"[1-9][0-9]{0,19}\Z")
BOUND = 32768
PLANS = {
    "smoke": {"clamp": [], "clamp-followup": ["clamp"]},
    "workout-date": {"workout-date": []},
    "backup-replay": {"backup-pr15": [], "clamp": [], "clamp-followup": ["clamp"]},
}
PATHS = {
    "clamp": "bounds.py",
    "clamp-followup": "followup_bounds.py",
    "workout-date": "domain/src/main/kotlin/ru/forma/domain/Session.kt",
    "backup-pr15": "domain/src/main/kotlin/ru/forma/domain/Backup.kt",
}


class EvidenceError(ValueError):
    pass


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def data_file(file):
    if (file.is_symlink() or not file.is_file() or not 0 < file.stat().st_size <= BOUND):
        raise EvidenceError("missing, oversized or symlinked evidence file")
    return file.read_bytes()


def decode_json(raw, expected):
    try:
        value = json.loads(raw)
    except (UnicodeError, ValueError, RecursionError):
        raise EvidenceError("invalid " + expected + " JSON") from None
    if not isinstance(value, dict):
        raise EvidenceError("expected an object for " + expected)
    return value


def check_patch(raw, path):
    if b"\0" in raw or b"\r" in raw:
        raise EvidenceError("binary or CRLF patch")
    try:
        lines = raw.decode("utf-8").splitlines()
    except UnicodeError:
        raise EvidenceError("patch not UTF-8") from None
    if (len(lines) < 4 or lines[0] != "--- a/" + path
            or lines[1] != "+++ b/" + path or not lines[2].startswith("@@ ")):
        raise EvidenceError("unexpected patch path or content")
    if (sum(x.startswith("--- ") for x in lines) != 1
            or sum(x.startswith("+++ ") for x in lines) != 1
            or not any(x.startswith(("+", "-")) for x in lines[3:])):
        raise EvidenceError("invalid patch sections")
    if any(x.startswith(("diff --git", "GIT binary patch", "Binary files ",
                        "rename from ", "rename to ")) for x in lines):
        raise EvidenceError("unsupported patch metadata")


def verify_artifacts(event, repository, run_id, revision, checkout,
                     plan_raw, queue_raw, patch_raws):
    """Return a provenance-limited integrity receipt, NEVER publisher approval."""
    if (event != "schedule" or repository != REPO or
            not isinstance(run_id, str) or not RUN_ID.fullmatch(run_id) or
            not isinstance(revision, str) or not SHA.fullmatch(revision) or
            revision != checkout):
        raise EvidenceError("event, repository, run or exact checkout mismatch")
    plan = decode_json(plan_raw, "plan")
    if set(plan) != {"schema", "base_sha", "package", "reason",
                     "open_pr_count", "authorization"}:
        raise EvidenceError("unexpected scheduled plan schema")
    package = plan["package"]
    if (type(plan["schema"]) is not int or plan["schema"] != 1
            or plan["base_sha"] != revision or package not in PLANS
            or plan["authorization"] != "read-only-reviewed-package"
            or type(plan["open_pr_count"]) is not int
            or not 0 <= plan["open_pr_count"] < 100
            or not isinstance(plan["reason"], str)
            or not 1 <= len(plan["reason"]) <= 240):
        raise EvidenceError("scheduled plan does not match runner source")
    queue = decode_json(queue_raw, "queue")
    if set(queue) != {"identity", "records"}:
        raise EvidenceError("unexpected queue checkpoint fields")
    identity = {"version": 1, "base_sha": revision, "plan": PLANS[package]}
    if queue["identity"] != identity:
        raise EvidenceError("checkpoint identity, base or dependency graph mismatch")
    records = queue["records"]
    if not isinstance(records, dict) or set(records) != set(PLANS[package]):
        raise EvidenceError("missing, duplicated or unexpected task records")
    if not isinstance(patch_raws, dict) or set(patch_raws) != set(records):
        raise EvidenceError("patch inventory does not match tasks")
    evidence_hashes = {}
    for task in PLANS[package]:
        r = records[task]
        if (not isinstance(r, dict) or r.get("status") != "PASS"
                or r.get("checks") != "PASS" or r.get("review") != "PASS"
                or "candidate" in r):
            raise EvidenceError("model queue did not complete checks and review")
        history = r.get("history")
        if (not isinstance(history, list) or not history
                or len(history) > 4 or history[-1].get("result") != "PASS"):
            raise EvidenceError("checkpoint contains no completed model attempt")
        raw = patch_raws[task]
        if not isinstance(raw, bytes) or not 0 < len(raw) <= BOUND:
            raise EvidenceError("invalid patch size or type")
        actual = digest(raw)
        if not isinstance(r.get("patch_sha256"), str) or not DIGEST.fullmatch(
                r["patch_sha256"]) or r["patch_sha256"] != actual:
            raise EvidenceError("model patch hash does not match checkpoint")
        check_patch(raw, PATHS[task])
        evidence_hashes[task + ".patch"] = actual
    return {
        "schema": 1,
        "verification": "ARTIFACT_INTEGRITY_PASS",
        "event_claimed_by_runner": "schedule",
        "source_repository": REPO,
        "workflow_run_id": run_id,
        "exact_checkout_sha": revision,
        "package": package,
        "task_type": "synthetic-smoke" if package == "smoke" else "reviewed-product-package",
        "task_ids": sorted(records),
        "open_pr_count_at_selection": plan["open_pr_count"],
        "plan_sha256": digest(plan_raw),
        "queue_sha256": digest(queue_raw),
        "patch_sha256": evidence_hashes,
        "model_queue_records_claim_pass": True,
        "actual_github_run_independently_verified": False,
        "product_pr_published": False,
        "publisher_authorized": False,
        "release_authorized": False,
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--event", required=True)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--revision", required=True)
    parser.add_argument("--checkout", required=True)
    parser.add_argument("--plan", type=Path, required=True)
    parser.add_argument("--queue", type=Path, required=True)
    parser.add_argument("--evidence-dir", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.evidence_dir.is_symlink() or args.output.is_symlink():
            raise EvidenceError("evidence directory or output is a symlink")
        plan_raw = data_file(args.plan)
        queue_raw = data_file(args.queue)
        selected = decode_json(plan_raw, "plan").get("package")
        if selected not in PLANS:
            raise EvidenceError("unknown reviewed package")
        folder = args.evidence_dir
        patches = {name: data_file(folder / (name + ".patch")) for name in PLANS[selected]}
        result = verify_artifacts(args.event, args.repo, args.run_id,
                                  args.revision, args.checkout, plan_raw,
                                  queue_raw, patches)
        output = 0
    except (EvidenceError, OSError, UnicodeError, TypeError, AttributeError) as e:
        result = {"schema": 1, "verification": "ARTIFACT_INTEGRITY_FAIL",
                  "reason": str(e)[:200],
                  "actual_github_run_independently_verified": False,
                  "product_pr_published": False}
        output = 1
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, sort_keys=True, indent=2) + "\n")
    print(json.dumps({"verification": result["verification"],
                      "run_id": args.run_id, "package": result.get("package")},
                     sort_keys=True))
    return output


if __name__ == "__main__":
    sys.exit(main())
