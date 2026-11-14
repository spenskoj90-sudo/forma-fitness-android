"""Prepare an UNTRUSTED, read-only product patch proposal from an AI queue artifact.

Inspired by the safe-output boundary in github/gh-aw. This is deliberately
NOT a PR publisher, code executor, authenticator or release/signing tool.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

SOURCES = {
    "workout-date": ("workout-date", "domain/src/main/kotlin/ru/forma/domain/Session.kt"),
    "backup-replay": ("backup-pr15", "domain/src/main/kotlin/ru/forma/domain/Backup.kt"),
}
PLANS = {
    "workout-date": {"workout-date": []},
    "backup-replay": {
        "backup-pr15": [], "clamp": [], "clamp-followup": ["clamp"],
    },
}
FULL_SHA = re.compile(r"[0-9a-f]{40}\Z")
HASH = re.compile(r"[0-9a-f]{64}\Z")
CHECKS = ["verify", "instrumentation (26)", "instrumentation (30)",
          "instrumentation (34)", "backend-security"]
LIMIT = 32768


class ProposalError(ValueError):
    pass


def sha(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def read_evidence_file(folder: Path, name: str) -> bytes:
    file = folder / name
    if (folder.is_symlink() or file.is_symlink() or not file.is_file()
            or not 0 < file.stat().st_size <= LIMIT):
        raise ProposalError("missing or unsafe evidence file")
    return file.read_bytes()


def validate_patch(raw: bytes, expected: str):
    if b"\0" in raw or b"\r" in raw:
        raise ProposalError("unsafe patch encoding")
    try:
        patch = raw.decode("utf-8")
    except UnicodeDecodeError:
        raise ProposalError("patch must be UTF-8") from None
    lines = patch.splitlines()
    if (len(lines) < 4 or lines[0] != "--- a/" + expected or
            lines[1] != "+++ b/" + expected or not lines[2].startswith("@@ ")):
        raise ProposalError("patch must target exactly one reviewed file")
    if sum(line.startswith("--- ") for line in lines) != 1 or sum(
            line.startswith("+++ ") for line in lines) != 1:
        raise ProposalError("multiple patch files are not allowed")
    if any(line.startswith(("diff --git ", "GIT binary patch", "Binary files ",
                           "rename from ", "rename to ")) for line in lines):
        raise ProposalError("unsafe patch metadata")
    if not any(line.startswith(("+", "-")) for line in lines[3:]):
        raise ProposalError("patch has no changes")


def proposal(revision: str, package: str, evidence: Path, checkout: str) -> dict:
    if (not isinstance(revision, str) or not FULL_SHA.fullmatch(revision) or
            revision != checkout or package not in SOURCES):
        raise ProposalError("exact checked-out source revision and reviewed package required")
    task, target = SOURCES[package]
    queue_raw = read_evidence_file(evidence, "queue.json")
    patch_raw = read_evidence_file(evidence, task + ".patch")
    try:
        queue = json.loads(queue_raw)
    except (UnicodeDecodeError, json.JSONDecodeError):
        raise ProposalError("malformed checkpoint") from None
    if not isinstance(queue, dict) or set(queue) != {"identity", "records"}:
        raise ProposalError("unrecognised checkpoint fields")
    identity = queue["identity"]
    if identity != {"version": 1, "base_sha": revision, "plan": PLANS[package]}:
        raise ProposalError("checkpoint revision or approved task graph mismatch")
    records = queue["records"]
    if not isinstance(records, dict) or set(records) != set(PLANS[package]):
        raise ProposalError("unknown or incomplete task records")
    for name in PLANS[package]:
        record = records[name]
        if (not isinstance(record, dict) or record.get("status") != "PASS" or
                record.get("checks") != "PASS" or record.get("review") != "PASS" or
                "candidate" in record):
            raise ProposalError("incomplete model/check/review evidence")
    record = records[task]
    if not isinstance(record.get("patch_sha256"), str) or not HASH.fullmatch(
            record["patch_sha256"]) or sha(patch_raw) != record["patch_sha256"]:
        raise ProposalError("patch hash disagrees with checkpoint")
    validate_patch(patch_raw, target)
    return {
        "schema": 1,
        "kind": "untrusted-ai-patch-review-request",
        "status": "PENDING_TRUSTED_REVIEW",
        "base_sha": revision,
        "target_branch": "main",
        "package": package,
        "task": task,
        "target_path": target,
        "checkpoint_sha256": sha(queue_raw),
        "patch_sha256": sha(patch_raw),
        "required_checks": CHECKS,
        "model_record_claims_pass": True,
        "publisher_authorized": False,
        "merge_authorized": False,
        "release_authorized": False,
        "device_accepted": False,
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--revision", required=True)
    parser.add_argument("--package", choices=sorted(SOURCES), required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[1]
    try:
        checkout = subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=repo, text=True, timeout=8).strip()
        result = proposal(args.revision, args.package, args.evidence, checkout)
        if args.output.is_symlink() or args.output.resolve().is_relative_to(repo.resolve()):
            raise ProposalError("output cannot overwrite repository source")
        if not args.output.parent.is_dir():
            raise ProposalError("output parent directory missing")
        # Atomic receipt, restricted to the caller's existing artifact directory.
        with tempfile.NamedTemporaryFile(mode="w", dir=args.output.parent,
                                         prefix=".pilot-request-", delete=False,
                                         encoding="utf-8") as f:
            temp = Path(f.name)
            try:
                os.chmod(temp, 0o600)
                json.dump(result, f, sort_keys=True, indent=2)
                f.write("\n")
            except BaseException:
                temp.unlink(missing_ok=True)
                raise
        temp.replace(args.output)
        print("PILOT_SAFE_OUTPUT_PENDING_REVIEW " + result["patch_sha256"])
        return 0
    except (ProposalError, OSError, ValueError, subprocess.CalledProcessError,
            subprocess.TimeoutExpired) as exc:
        print("PILOT_SAFE_OUTPUT_REJECTED: " + str(exc), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
