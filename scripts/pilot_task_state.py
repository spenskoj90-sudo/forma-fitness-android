"""CAS-backed PILOT task checkpoint. No network, GitHub token, model or PR writes.

This module simulates the trusted state/publisher boundary using an independent
local bare Git repository. A real hosted state ref/publisher requires separately
approved contents/pull-requests write permissions; Actions here stays read-only.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile

from pilot_safe_output import validate_patch, ProposalError

FULL_SHA = re.compile(r"[0-9a-f]{40}\Z")
SLUG = re.compile(r"[a-z][a-z0-9-]{2,70}\Z")
TASKS = {
    "forma-workout-finish-date-v1": {
        "package": "workout-date",
        "path": "domain/src/main/kotlin/ru/forma/domain/Session.kt",
        "occupied_prs": (21,),
        "occupied_branches": ("forma/workout-finish-date-20261113",),
    },
    "forma-backup-completed-sets-v1": {
        "package": "backup-replay",
        "path": "domain/src/main/kotlin/ru/forma/domain/Backup.kt",
        "occupied_prs": (15,),
        "occupied_branches": ("forma/backup-completed-sets-20261112",),
    },
    # CI-only fixture; NEVER eligible for a product schedule or live publication.
    "pilot-synthetic-e2e-v1": {
        "package": "synthetic",
        "path": "fixture.py",
        "occupied_prs": (),
        "occupied_branches": (),
    },
}
MAX_PATCH = 32768
REQUIRED = ("verify", "instrumentation (26)", "instrumentation (30)",
            "instrumentation (34)", "backend-security")


class StateError(ValueError):
    pass


class CasLost(StateError):
    pass


def git(*args, input=None, check=True):
    p = subprocess.run(["git", *map(str, args)], input=input,
                       capture_output=True, check=False, timeout=15)
    if p.returncode and check:
        raise StateError("Git operation failed: " + args[0])
    return p.stdout if p.returncode == 0 else None


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def validate_prs(prs):
    if not isinstance(prs, list) or len(prs) >= 100:
        raise StateError("incomplete or invalid open PR snapshot")
    seen = set()
    for pr in prs:
        if not isinstance(pr, dict) or type(pr.get("number")) is not int or pr["number"] < 1 or pr.get("state") != "open":
            raise StateError("invalid open PR metadata")
        if pr["number"] in seen:
            raise StateError("duplicate open PR metadata")
        seen.add(pr["number"])
        if "head_ref" in pr and (not isinstance(pr["head_ref"], str) or
                                not re.fullmatch(r"[A-Za-z0-9._/-]{1,140}", pr["head_ref"])):
            raise StateError("invalid PR branch")


def occupied(task_id, prs):
    validate_prs(prs)
    spec = TASKS[task_id]
    branch = "pilot/task-" + task_id
    return any(pr["number"] in spec["occupied_prs"] or
               pr.get("head_ref") in (*spec["occupied_branches"], branch)
               for pr in prs)


class GitLedger:
    def __init__(self, bare):
        self.bare = Path(bare)
        if self.bare.is_symlink() or not self.bare.is_dir():
            raise StateError("persistent Git ledger must already exist")
        if git("--git-dir", self.bare, "rev-parse", "--is-bare-repository") != b"true\n":
            raise StateError("ledger is not an independent bare Git repository")

    def ref(self, task_id):
        if task_id not in TASKS or not SLUG.fullmatch(task_id):
            raise StateError("task ID not preapproved")
        return "refs/heads/pilot-state/" + task_id

    def read(self, task_id):
        ref = self.ref(task_id)
        commit = git("--git-dir", self.bare, "rev-parse", "--verify", ref, check=False)
        if commit is None:
            return None, None, None
        old = commit.decode().strip()
        if not FULL_SHA.fullmatch(old):
            raise StateError("ledger ref corrupted")
        try:
            state = json.loads(git("--git-dir", self.bare, "show", old + ":state.json"))
            patch = git("--git-dir", self.bare, "show", old + ":patch.diff", check=False)
        except (TypeError, ValueError):
            raise StateError("ledger JSON corrupted") from None
        if not isinstance(state, dict) or state.get("task_id") != task_id:
            raise StateError("ledger state identity mismatch")
        if state.get("status") in ("PATCH_SAVED", "VERIFIED", "PUBLISHED"):
            if patch is None or state.get("patch_sha256") != digest(patch):
                raise StateError("saved patch unavailable or digest mismatch")
        elif patch is not None:
            raise StateError("unexpected patch content")
        return old, state, patch

    def write(self, task_id, expected, state, patch=None):
        ref = self.ref(task_id)
        blob = git("--git-dir", self.bare, "hash-object", "-w", "--stdin",
                   input=(json.dumps(state, sort_keys=True, separators=(",", ":")) + "\n").encode()).decode().strip()
        entries = [f"100644 blob {blob}\tstate.json\n"]
        if patch is not None:
            if not isinstance(patch, bytes) or not 0 < len(patch) <= MAX_PATCH:
                raise StateError("invalid patch size")
            pb = git("--git-dir", self.bare, "hash-object", "-w", "--stdin", input=patch).decode().strip()
            entries.append(f"100644 blob {pb}\tpatch.diff\n")
        tree = git("--git-dir", self.bare, "mktree", input="".join(entries).encode()).decode().strip()
        args = ["-c", "user.name=PILOT-SIM", "-c", "user.email=pilot-sim@example.invalid",
                "--git-dir", self.bare, "commit-tree", tree]
        if expected:
            args += ["-p", expected]
        commit = git(*args, input=b"durable checkpoint\n").decode().strip()
        compare = expected or "0" * 40
        if git("--git-dir", self.bare, "update-ref", ref, commit, compare, check=False) is None:
            raise CasLost("concurrent writer changed task ref")
        # Trust only readback after compare-and-swap.
        actual, saved, saved_patch = self.read(task_id)
        if actual != commit or saved != state or saved_patch != patch:
            raise StateError("commit readback mismatch")
        return commit


def claim(ledger, task_id, base, owner, now, prs):
    if task_id not in TASKS or task_id.startswith("pilot-synthetic") and not owner.startswith("test-"):
        raise StateError("unknown or synthetic task outside test-only mode")
    if not FULL_SHA.fullmatch(base) or not isinstance(owner, str) or not re.fullmatch(r"[A-Za-z0-9-]{4,70}", owner):
        raise StateError("invalid exact base or writer identifier")
    if type(now) is not int or now < 0:
        raise StateError("invalid clock")
    if occupied(task_id, prs):
        raise StateError("TASK_OCCUPIED_BY_PR")
    old, state, patch = ledger.read(task_id)
    if state is None:
        next_state = {
            "schema": 1, "task_id": task_id, "package": TASKS[task_id]["package"],
            "base_sha": base, "branch": "pilot/task-" + task_id,
            "status": "CLAIMED", "attempt": 1, "writer": owner,
            "lease_until": now + 900, "patch_sha256": None,
            "checks": {}, "pr_url": None, "pr_head_sha": None,
        }
    else:
        if state["status"] == "PUBLISHED":
            return old, state
        if state["base_sha"] != base:
            raise StateError("STALE_BASE_REQUIRES_REVALIDATION")
        if state["lease_until"] > now and state["writer"] != owner:
            raise StateError("WRITER_BUSY")
        next_state = dict(state)
        if state["writer"] != owner:
            next_state["attempt"] = state["attempt"] + 1
        next_state.update(writer=owner, lease_until=now + 900)
    return ledger.write(task_id, old, next_state, patch), next_state


def save_patch(ledger, task_id, owner, raw, base):
    old, state, _ = ledger.read(task_id)
    if state is None or state["base_sha"] != base or state["writer"] != owner or state["status"] != "CLAIMED":
        raise StateError("invalid patch ownership or stale base")
    if not isinstance(raw, bytes) or len(raw) > MAX_PATCH:
        raise StateError("invalid patch content")
    try:
        validate_patch(raw, TASKS[task_id]["path"])
    except ProposalError as e:
        raise StateError("PATCH_REJECTED: " + str(e)) from None
    next_state = {**state, "status": "PATCH_SAVED", "patch_sha256": digest(raw),
                  "checks": {"apply": "NOT_RUN", "review": "NOT_RUN", "CI": "NOT_RUN"}}
    return ledger.write(task_id, old, next_state, raw)


def verify(ledger, task_id, owner, source_repo, base):
    old, state, patch = ledger.read(task_id)
    if state is None or state["base_sha"] != base or state["writer"] != owner or state["status"] != "PATCH_SAVED":
        raise StateError("wrong stage or stale checkout")
    if git("-C", source_repo, "rev-parse", "HEAD").decode().strip() != base:
        raise StateError("STALE_BASE_REQUIRES_REVALIDATION")
    with tempfile.TemporaryDirectory(prefix="pilot-patch-check-") as scratch:
        # Independent clean checkout, not the model's worktree.
        git("clone", "--quiet", "--no-checkout", source_repo, scratch)
        git("-C", scratch, "checkout", "-q", "--detach", base)
        if git("-C", scratch, "apply", "--check", "-", input=patch, check=False) is None:
            raise StateError("GIT_APPLY_CHECK_FAILED")
    next_state = {**state, "status": "VERIFIED",
                  "checks": {"base": "PASS", "path": "PASS",
                             "digest": "PASS", "git_apply_check": "PASS",
                             "model_review_claim": "NOT_PROVEN",
                             "required_ci": "NOT_RUN"}}
    return ledger.write(task_id, old, next_state, patch)


def publish_sim(ledger, task_id, owner, base, prs):
    old, state, patch = ledger.read(task_id)
    if state is None:
        raise StateError("task has no durable checkpoint")
    if state["status"] == "PUBLISHED":
        return old, state
    if occupied(task_id, prs):
        raise StateError("existing product PR must be reused, not duplicated")
    if state["status"] != "VERIFIED" or state["base_sha"] != base or state["writer"] != owner:
        raise StateError("publication refused without exact reviewed state")
    if state["checks"].get("git_apply_check") != "PASS":
        raise StateError("verification missing")
    next_state = {**state, "status": "PUBLISHED",
                  "pr_url": "simulation://draft/" + task_id + "/" + state["patch_sha256"][:16],
                  "pr_head_sha": None}
    try:
        return ledger.write(task_id, old, next_state, patch), next_state
    except CasLost:
        actual, concurrent, _ = ledger.read(task_id)
        if concurrent is not None and concurrent.get("status") == "PUBLISHED" and concurrent.get("patch_sha256") == state["patch_sha256"]:
            return actual, concurrent
        raise


def main():
    parser = argparse.ArgumentParser(description="Local bare-Git CAS dry-run; NO GitHub writer")
    parser.add_argument("--ledger", type=Path, required=True)
    parser.add_argument("--task-id", choices=sorted(TASKS), required=True)
    parser.add_argument("--base", required=True)
    parser.add_argument("--owner", required=True)
    parser.add_argument("--now", type=int, required=True)
    parser.add_argument("--prs", type=Path, required=True)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--patch", type=Path, required=True)
    args = parser.parse_args()
    try:
        prs = json.loads(args.prs.read_text())
        ledger = GitLedger(args.ledger)
        _, state = claim(ledger, args.task_id, args.base, args.owner, args.now, prs)
        if state["status"] == "CLAIMED":
            save_patch(ledger, args.task_id, args.owner, args.patch.read_bytes(), args.base)
        _, state, _ = ledger.read(args.task_id)
        if state["status"] == "PATCH_SAVED":
            verify(ledger, args.task_id, args.owner, args.source, args.base)
        _, state = publish_sim(ledger, args.task_id, args.owner, args.base, prs)
        print(json.dumps({"status": state["status"], "task_id": args.task_id,
                          "base_sha": args.base, "attempt": state["attempt"],
                          "patch_sha256": state["patch_sha256"],
                          "simulated_pr": state["pr_url"]}, sort_keys=True))
        return 0
    except (StateError, OSError, ValueError, json.JSONDecodeError, subprocess.TimeoutExpired) as error:
        print("PILOT_STATE_REJECTED: " + str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
