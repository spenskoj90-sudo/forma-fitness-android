"""Trusted publisher PRE-FLIGHT: artifact/data is untrusted, never executable.

Only the GitHub API run, pinned main source, source-owned task contracts,
checked task patches, and separate unprivileged compilation can authorize
a draft-PR proposal. This module has no GitHub write functions.
"""
import hashlib
import io
import json
from pathlib import Path
import re
import sys
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from pilot_safe_output import SOURCES, validate_patch, ProposalError
from verify_schedule_evidence import PLANS, REPO, verify_artifacts, EvidenceError

WORKFLOW_PATH = ".github/workflows/ai-work-queue.yml"
FULL_SHA = re.compile(r"[0-9a-f]{40}\Z")
TASKS = {
    "workout-date": ("forma-workout-finish-date-v1", 21,
                     "forma/workout-finish-date-20261113"),
    "backup-replay": ("forma-backup-completed-sets-v1", 15,
                      "forma/backup-completed-sets-20261112"),
}
TOTAL_COMPRESSED = 1_000_000
TOTAL_UNCOMPRESSED = 250_000
MAX_FILES = 12


class TrustError(ValueError):
    pass


def sha256(raw):
    return hashlib.sha256(raw).hexdigest()


def canonical_json(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":")).encode()


def strict_zip(raw):
    if not isinstance(raw, bytes) or not 0 < len(raw) <= TOTAL_COMPRESSED:
        raise TrustError("artifact ZIP missing or too large")
    result = {}
    total = 0
    allowed = {
        "queue.json", "forma-scheduled-plan.json", "forma-schedule-evidence.json",
        "clamp.patch", "clamp-followup.patch", "backup-pr15.patch", "workout-date.patch",
    }
    try:
        with zipfile.ZipFile(io.BytesIO(raw)) as z:
            entries = z.infolist()
            if not entries or len(entries) > MAX_FILES:
                raise TrustError("unexpected ZIP entry count")
            for e in entries:
                name = e.filename
                # Actions may preserve a single folder prefix for queue files.
                if name.startswith("forma-ai-queue/"):
                    name = name[len("forma-ai-queue/"):]
                if (name not in allowed or name in result or e.is_dir() or
                        not (e.flag_bits & 1) == 0 or
                        ((e.external_attr >> 16) & 0o170000) == 0o120000 or
                        e.file_size < 1 or e.file_size > 32768):
                    raise TrustError("unsafe or duplicated artifact member")
                total += e.file_size
                if total > TOTAL_UNCOMPRESSED:
                    raise TrustError("uncompressed artifact too large")
                with z.open(e, "r") as stream:
                    value = stream.read(e.file_size + 1)
                if len(value) != e.file_size:
                    raise TrustError("ZIP size mismatch")
                result[name] = value
    except (zipfile.BadZipFile, OSError, EOFError, RuntimeError) as exc:
        raise TrustError("invalid artifact ZIP") from exc
    return result


def validate_run(run, workflow, main_sha, expected_run_id, expected_attempt):
    if not isinstance(run, dict) or not isinstance(workflow, dict):
        raise TrustError("GitHub run or workflow record malformed")
    if (type(run.get("id")) is not int or run["id"] != expected_run_id or
            type(run.get("run_attempt")) is not int or
            run["run_attempt"] != expected_attempt or
            run.get("event") != "schedule" or
            run.get("head_branch") != "main" or
            run.get("status") != "completed" or
            run.get("conclusion") != "success" or
            run.get("head_repository", {}).get("full_name") != REPO or
            run.get("repository", {}).get("full_name") != REPO or
            type(run.get("workflow_id")) is not int or
            type(workflow.get("id")) is not int or
            run["workflow_id"] != workflow["id"] or
            workflow.get("path") != WORKFLOW_PATH or
            not isinstance(run.get("head_sha"), str) or
            not FULL_SHA.fullmatch(run["head_sha"]) or
            run["head_sha"] != main_sha):
        raise TrustError("run event, workflow ID, repository or protected HEAD invalid")


def verify_artifact(run, workflow, main_sha, artifact, zipbytes):
    validate_run(run, workflow, main_sha, run["id"], run["run_attempt"])
    name = f"forma-ai-queue-{run['id']}-{run['run_attempt']}"
    if (not isinstance(artifact, dict) or artifact.get("name") != name or
            artifact.get("expired") is not False or
            type(artifact.get("size_in_bytes")) is not int or
            artifact["size_in_bytes"] != len(zipbytes) or
            len(zipbytes) > TOTAL_COMPRESSED):
        raise TrustError("artifact ID, expiry or compressed size mismatches")
    dg = artifact.get("digest")
    if dg is not None and dg != "sha256:" + sha256(zipbytes):
        raise TrustError("downloaded artifact ZIP hash disagrees with GitHub")
    files = strict_zip(zipbytes)
    mandatory = {"queue.json", "forma-scheduled-plan.json", "forma-schedule-evidence.json"}
    if not mandatory.issubset(files):
        raise TrustError("missing schedule/queue/receipt members")
    try:
        plan = json.loads(files["forma-scheduled-plan.json"])
    except (ValueError, UnicodeError) as e:
        raise TrustError("malformed plan JSON") from e
    if not isinstance(plan, dict) or plan.get("package") not in PLANS:
        raise TrustError("unknown scheduled task package")
    package = plan["package"]
    expected_files = mandatory | {task + ".patch" for task in PLANS[package]}
    if set(files) != expected_files:
        raise TrustError("unexpected patch inventory or extra members")
    patch_raws = {task: files[task + ".patch"] for task in PLANS[package]}
    try:
        expected = verify_artifacts(
            "schedule", REPO, str(run["id"]), main_sha, main_sha,
            files["forma-scheduled-plan.json"], files["queue.json"], patch_raws)
        receipt = json.loads(files["forma-schedule-evidence.json"])
    except (EvidenceError, ValueError, TypeError, UnicodeError, AttributeError) as e:
        raise TrustError("invalid reviewed schedule artifact") from e
    if receipt != expected:
        raise TrustError("schedule receipt differs from independent recomputation")
    if package == "smoke":
        return {"result": "SKIP_SYNTHETIC", "task_id": None}, None
    task_name, target = SOURCES[package]
    task_id, known_number, known_branch = TASKS[package]
    patch = patch_raws[task_name]
    try:
        validate_patch(patch, target)
    except ProposalError as e:
        raise TrustError("product patch violates single-file contract") from e
    manifest = {
        "schema": 1,
        "result": "REVIEW_REQUIRED",
        "task_id": task_id,
        "package": package,
        "model_task": task_name,
        "target_path": target,
        "branch": "pilot/task-" + task_id,
        "state_branch": "pilot-state/" + task_id,
        "base_sha": main_sha,
        "patch_sha256": sha256(patch),
        "queue_sha256": sha256(files["queue.json"]),
        "plan_sha256": sha256(files["forma-scheduled-plan.json"]),
        "receipt_sha256": sha256(files["forma-schedule-evidence.json"]),
        "artifact_sha256": sha256(zipbytes),
        "run_id": run["id"],
        "run_attempt": run["run_attempt"],
    }
    return manifest, patch


def matching_pr(pr, task_id):
    if not isinstance(pr, dict):
        raise TrustError("invalid PR record")
    head = pr.get("head") or {}
    ref = head.get("ref")
    if not isinstance(ref, str):
        raise TrustError("PR branch record missing")
    known = [item for item in TASKS.values() if item[0] == task_id][0]
    return (ref in ("pilot/task-" + task_id, known[2]) or
            pr.get("number") == known[1])


def inspect_prs(prs, task_id):
    if not isinstance(prs, list) or len(prs) > 2000:
        raise TrustError("incomplete GitHub PR snapshot")
    matches = [pr for pr in prs if matching_pr(pr, task_id)]
    if len(matches) > 1:
        raise TrustError("multiple PRs already claim the same task")
    return matches[0] if matches else None


def manifest_digest(manifest):
    return sha256(canonical_json(manifest))
