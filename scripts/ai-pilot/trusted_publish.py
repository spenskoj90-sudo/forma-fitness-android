"""PILOT trusted publisher. Never run this with the model runner's privileges.

The read-only inspect job validates an authentic GitHub scheduled run/artifact;
an isolated domain-test job uses no write credentials. Only after both succeed
may the privileged job create a durable task checkpoint, one branch and one
DRAFT PR. Model bytes are data, never commands or Python imports.
"""
import argparse
import base64
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

from trusted_artifacts import (
    REPO, TASKS, TrustError, canonical_json, inspect_prs,
    manifest_digest, sha256, validate_run, verify_artifact,
)
from trusted_github import GitHubClient, GitHubFailure

GIT_SHA = re.compile(r"[0-9a-f]{40}\Z")
TASK_PATH_PREFIX = "pilot-state/"
MAX_FILE = 120000


def verified_candidate(api, run_id, attempt, expected_sha):
    if (type(run_id) is not int or run_id < 1 or
            type(attempt) is not int or attempt < 1 or
            not GIT_SHA.fullmatch(expected_sha)):
        raise TrustError("invalid requested source run and SHA")
    main = api.main_sha()
    run = api.run(run_id)
    wf = api.workflow()
    validate_run(run, wf, main, run_id, attempt)
    if main != expected_sha:
        raise TrustError("source run no longer matches protected main")
    item = api.artifact(run_id, attempt)
    raw_zip = api.zip_artifact(item["id"])
    manifest, patch = verify_artifact(run, wf, main, item, raw_zip)
    if manifest["result"] == "SKIP_SYNTHETIC":
        return manifest, None
    existing = inspect_prs(api.pulls(), manifest["task_id"])
    if existing and (existing.get("head") or {}).get("ref") != manifest["branch"]:
        return {
            **manifest, "result": "SKIP_EXISTING_PR",
            "pr_number": existing["number"],
            "pr_url": existing.get("html_url"),
        }, None
    # The deterministic PILOT branch may already have a draft PR after a
    # crash before the published checkpoint was saved. Let the publisher
    # reconcile it; it must never create a second one.
    return {**manifest, "result": "READY"}, patch


def make_git_blob(api, raw):
    if not isinstance(raw, bytes) or len(raw) > MAX_FILE:
        raise TrustError("refusing large Git blob")
    blob = api.post("/git/blobs", {
        "content": base64.b64encode(raw).decode("ascii"),
        "encoding": "base64",
    })
    value = blob.get("sha")
    if not isinstance(value, str) or not GIT_SHA.fullmatch(value):
        raise TrustError("GitHub did not return Git blob SHA")
    return value


def make_git_commit(api, parent, files, message):
    if not GIT_SHA.fullmatch(parent) or not isinstance(files, dict):
        raise TrustError("invalid Git commit parent or files")
    base_commit = api.get("/git/commits/" + parent)
    base_tree = base_commit.get("tree", {}).get("sha")
    if not isinstance(base_tree, str) or not GIT_SHA.fullmatch(base_tree):
        raise TrustError("missing base Git tree")
    entries = []
    for path, raw in sorted(files.items()):
        if path not in {"pilot-state.json", "pilot-patch.diff",
                        "domain/src/main/kotlin/ru/forma/domain/Session.kt",
                        "domain/src/main/kotlin/ru/forma/domain/Backup.kt"}:
            raise TrustError("unapproved Git tree path")
        entries.append({
            "path": path, "mode": "100644", "type": "blob",
            "sha": make_git_blob(api, raw),
        })
    tree = api.post("/git/trees", {"base_tree": base_tree, "tree": entries})
    sha = tree.get("sha")
    if not isinstance(sha, str) or not GIT_SHA.fullmatch(sha):
        raise TrustError("invalid resulting tree")
    new = api.post("/git/commits", {
        "message": message,
        "tree": sha,
        "parents": [parent],
    })
    result = new.get("sha")
    if not isinstance(result, str) or not GIT_SHA.fullmatch(result):
        raise TrustError("invalid resulting Git commit")
    return result


def content_from_ref(api, ref, path):
    if path not in ("pilot-state.json", "pilot-patch.diff"):
        raise TrustError("unexpected checkpoint lookup path")
    blob = api.get(f"/contents/{path}?ref={ref}")
    if not isinstance(blob, dict) or blob.get("encoding") != "base64":
        raise TrustError("checkpoint content not encoded")
    try:
        raw = base64.b64decode(blob["content"], validate=False)
    except (TypeError, ValueError) as e:
        raise TrustError("checkpoint base64 invalid") from e
    if len(raw) > MAX_FILE:
        raise TrustError("checkpoint content oversized")
    return raw


def load_state(api, task_id):
    branch = TASK_PATH_PREFIX + task_id
    ref = api.branch_ref(branch)
    if ref is None:
        return None, None
    sha = ref.get("object", {}).get("sha")
    if not isinstance(sha, str) or not GIT_SHA.fullmatch(sha):
        raise TrustError("stored task ref has no commit")
    try:
        state = json.loads(content_from_ref(api, branch, "pilot-state.json"))
        patch = content_from_ref(api, branch, "pilot-patch.diff")
    except (ValueError, UnicodeError, KeyError) as e:
        raise TrustError("corrupt persisted task checkpoint") from e
    if (not isinstance(state, dict) or state.get("schema") != 1 or
            state.get("task_id") != task_id or
            state.get("patch_sha256") != sha256(patch)):
        raise TrustError("persisted task state/patch mismatch")
    return sha, (state, patch)


def create_ref(api, branch, commit):
    if not re.fullmatch(r"(?:pilot/task-|pilot-state/)[a-z0-9-]{3,75}", branch):
        raise TrustError("unapproved ref branch")
    result = api.post("/git/refs", {"ref": "refs/heads/" + branch, "sha": commit})
    if result.get("object", {}).get("sha") != commit:
        raise TrustError("Git ref creation was not confirmed")
    saved = api.branch_ref(branch)
    if not saved or saved.get("object", {}).get("sha") != commit:
        raise TrustError("Git ref creation readback mismatch")
    return commit


def update_ref(api, branch, old, new):
    if not GIT_SHA.fullmatch(old) or not GIT_SHA.fullmatch(new):
        raise TrustError("invalid Git reference update")
    saved = api.branch_ref(branch)
    if not saved or saved.get("object", {}).get("sha") != old:
        raise TrustError("concurrent writer changed checkpoint before update")
    try:
        api.patch("/git/refs/heads/" + branch, {"sha": new, "force": False})
    except GitHubFailure as e:
        raise TrustError("non-fast-forward/concurrent checkpoint write refused") from e
    saved = api.branch_ref(branch)
    if not saved or saved.get("object", {}).get("sha") != new:
        raise TrustError("checkpoint update readback mismatch")


def checkpoint(api, manifest, patch):
    task = manifest["task_id"]
    branch = manifest["state_branch"]
    old, stored = load_state(api, task)
    if stored:
        state, previous_patch = stored
        if (state.get("base_sha") != manifest["base_sha"] or
                state.get("patch_sha256") != manifest["patch_sha256"] or
                state.get("run_id") != manifest["run_id"] or
                previous_patch != patch or
                state.get("status") not in ("READY", "PUBLISHED")):
            raise TrustError("existing durable task state does not match source")
        if state["status"] == "READY":
            # Crash/retry: preserve the same approved patch while recording
            # the resumed attempt on the durable server-side Git ref.
            if type(state.get("attempt")) is not int or not 1 <= state["attempt"] < 32:
                raise TrustError("invalid or exhausted checkpoint attempts")
            resumed = {**state, "attempt": state["attempt"] + 1}
            next_commit = make_git_commit(
                api, old, {"pilot-state.json": canonical_json(resumed) + b"\n"},
                "chore(pilot): resume verified task " + task)
            update_ref(api, branch, old, next_commit)
            after, val = load_state(api, task)
            if after != next_commit or val is None or val[0] != resumed:
                raise TrustError("resumed checkpoint readback mismatch")
            return after, resumed
        return old, state
    initial = {
        "schema": 1,
        "task_id": task,
        "base_sha": manifest["base_sha"],
        "source_run_id": manifest["run_id"],
        "run_id": manifest["run_id"],
        "run_attempt": manifest["run_attempt"],
        "patch_sha256": manifest["patch_sha256"],
        "artifact_sha256": manifest["artifact_sha256"],
        "target_path": manifest["target_path"],
        "branch": manifest["branch"],
        "status": "READY",
        "attempt": 1,
        "writer": "trusted-publisher-workflow",
        "checks": {
            "github_source": "PASS",
            "schedule_receipt": "PASS",
            "read_only_domain_tests": "PASS",
            "required_pr_checks": "NOT_RUN",
        },
        "pr_number": None,
        "pr_url": None,
        "pr_head_sha": None,
    }
    new = make_git_commit(
        api, manifest["base_sha"],
        {"pilot-state.json": canonical_json(initial) + b"\n",
         "pilot-patch.diff": patch},
        "chore(pilot): durable reviewed checkpoint " + task)
    create_ref(api, branch, new)
    reread, val = load_state(api, task)
    if reread != new or val is None or val[0] != initial or val[1] != patch:
        raise TrustError("persisted task checkpoint readback mismatch")
    return new, initial


def apply_as_data(api, manifest, patch):
    """Use git apply as a PARSER only; do not execute or import modified code."""
    path = manifest["target_path"]
    if path not in (
        "domain/src/main/kotlin/ru/forma/domain/Session.kt",
        "domain/src/main/kotlin/ru/forma/domain/Backup.kt",
    ):
        raise TrustError("target path not preapproved")
    raw = api.get(f"/contents/{path}?ref={manifest['base_sha']}")
    if raw.get("encoding") != "base64":
        raise TrustError("source file encoding unsupported")
    try:
        original = base64.b64decode(raw["content"])
    except (KeyError, ValueError, TypeError) as e:
        raise TrustError("invalid trusted source blob") from e
    if len(original) > MAX_FILE:
        raise TrustError("trusted source file oversized")
    with tempfile.TemporaryDirectory(prefix="pilot-data-only-") as temp:
        workspace = Path(temp)
        file = workspace / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_bytes(original)
        patchfile = workspace / "candidate.diff"
        patchfile.write_bytes(patch)
        for cmd in (
            ["git", "apply", "--check", str(patchfile)],
            ["git", "apply", str(patchfile)],
        ):
            p = subprocess.run(cmd, cwd=workspace, timeout=15,
                               capture_output=True, check=False)
            if p.returncode:
                raise TrustError("reviewed patch no longer applies to exact source")
        after = file.read_bytes()
        if after == original or len(after) > MAX_FILE:
            raise TrustError("invalid applied patch result")
        unexpected = [p for p in workspace.rglob("*") if p.is_file() and p not in (file, patchfile)]
        if unexpected:
            raise TrustError("patch created files outside approved target")
        return after


def check_existing_product_branch(api, manifest, expected_after):
    branch = manifest["branch"]
    ref = api.branch_ref(branch)
    if ref is None:
        return None
    commit = ref.get("object", {}).get("sha")
    if not isinstance(commit, str) or not GIT_SHA.fullmatch(commit):
        raise TrustError("invalid existing product branch SHA")
    obj = api.get("/git/commits/" + commit)
    if [x.get("sha") for x in obj.get("parents", [])] != [manifest["base_sha"]]:
        raise TrustError("existing task branch diverged from approved source")
    file = api.get(f"/contents/{manifest['target_path']}?ref={branch}")
    try:
        contents = base64.b64decode(file["content"])
    except (ValueError, TypeError, KeyError) as e:
        raise TrustError("existing task branch file invalid") from e
    if contents != expected_after:
        raise TrustError("existing task branch has different code")
    # Verify no second file was changed, even if another writer created the branch.
    compare = api.get(f"/compare/{manifest['base_sha']}...{branch}")
    changed = compare.get("files")
    if not isinstance(changed, list) or len(changed) != 1 or changed[0].get("filename") != manifest["target_path"]:
        raise TrustError("existing product branch contains unreviewed changes")
    return commit


def product_commit(api, manifest, patch):
    after = apply_as_data(api, manifest, patch)
    exists = check_existing_product_branch(api, manifest, after)
    if exists:
        return exists
    commit = make_git_commit(api, manifest["base_sha"], {
        manifest["target_path"]: after,
    }, "fix(forma): PILOT reviewed " + manifest["task_id"])
    create_ref(api, manifest["branch"], commit)
    saved = check_existing_product_branch(api, manifest, after)
    if saved != commit:
        raise TrustError("new task commit was not verified")
    return commit


def create_draft(api, manifest, commit):
    matches = [pr for pr in api.pulls()
               if (pr.get("head") or {}).get("ref") == manifest["branch"]]
    if len(matches) > 1:
        raise TrustError("duplicate same-task PR discovered")
    if matches:
        pr = matches[0]
        if (pr.get("head", {}).get("sha") != commit or
                pr.get("base", {}).get("ref") != "main" or
                not pr.get("draft")):
            raise TrustError("existing same-task PR mismatched approved draft")
        return pr
    title = "fix(forma): PILOT " + manifest["task_id"]
    body = (
        "PILOT reviewed local-Qwen candidate. Product task ID: " +
        manifest["task_id"] + "\n\nSource scheduled run: " +
        f"https://github.com/{REPO}/actions/runs/{manifest['run_id']}" +
        "\nExact base SHA: " + manifest["base_sha"] +
        "\nPatch SHA-256: " + manifest["patch_sha256"] +
        "\nModel/independent review: PASS (queue claim verified). " +
        "Read-only domain tests: PASS (inspector job).\n" +
        "Required Android/backend PR CI: NOT_RUN until this PR is opened.\n" +
        "Automatically created as **DRAFT**; no merge or release authorized.")
    pr = api.post("/pulls", {
        "title": title, "head": manifest["branch"], "base": "main",
        "draft": True, "maintainer_can_modify": False, "body": body,
    })
    if (not pr.get("draft") or
            pr.get("head", {}).get("sha") != commit or
            pr.get("base", {}).get("ref") != "main" or
            type(pr.get("number")) is not int):
        raise TrustError("created PR metadata did not match safe contract")
    return pr


def finish_checkpoint(api, manifest, old, state, patch, pr, commit):
    if state["status"] == "PUBLISHED":
        if state["pr_number"] != pr["number"]:
            raise TrustError("published checkpoint disagrees with PR")
        return old
    if state["status"] != "READY":
        raise TrustError("unexpected checkpoint stage")
    updated = {**state, "status": "PUBLISHED",
               "pr_number": pr["number"], "pr_url": pr.get("html_url"),
               "pr_head_sha": commit}
    newer = make_git_commit(
        api, old, {"pilot-state.json": canonical_json(updated) + b"\n"},
        "chore(pilot): record one draft PR " + manifest["task_id"])
    update_ref(api, manifest["state_branch"], old, newer)
    saved_sha, value = load_state(api, manifest["task_id"])
    if saved_sha != newer or value is None or value[0] != updated:
        raise TrustError("published state readback mismatch")
    return newer


def publish(api, manifest, patch):
    if manifest.get("result") != "READY" or patch is None:
        raise TrustError("publisher must receive a reviewed product task")
    if api.main_sha() != manifest["base_sha"]:
        raise TrustError("STALE_BASE: protected main changed")
    matched = inspect_prs(api.pulls(), manifest["task_id"])
    if matched and (matched.get("head") or {}).get("ref") != manifest["branch"]:
        # An earlier reviewed task is owned by a separate product PR.
        return {"status": "SKIP_EXISTING_PR", "number": matched["number"]}
    old, state = checkpoint(api, manifest, patch)
    # Branch creation and the external PR API are separate writes. Fail
    # closed if main changes between the first check and publication.
    if api.main_sha() != manifest["base_sha"]:
        raise TrustError("STALE_BASE: protected main changed during publication")
    commit = product_commit(api, manifest, patch)
    if api.main_sha() != manifest["base_sha"]:
        raise TrustError("STALE_BASE: protected main changed before draft PR")
    pr = create_draft(api, manifest, commit)
    updated_state = finish_checkpoint(api, manifest, old, state, patch, pr, commit)
    return {"status": "DRAFT_CREATED_OR_REUSED",
            "pr_url": pr.get("html_url"),
            "pr_number": pr["number"], "head_sha": commit,
            "base_sha": manifest["base_sha"],
            "checkpoint_sha": updated_state,
            "task_id": manifest["task_id"]}


def write_atomic(path, content):
    if path.is_symlink() or not path.parent.is_dir():
        raise TrustError("invalid output path")
    with tempfile.NamedTemporaryFile(
        mode="wb", dir=path.parent, prefix=".pilot-publisher-", delete=False,
    ) as f:
        candidate = Path(f.name)
        try:
            os.chmod(candidate, 0o600)
            f.write(content)
        except BaseException:
            candidate.unlink(missing_ok=True)
            raise
    candidate.replace(path)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=("inspect", "publish"), required=True)
    parser.add_argument("--run-id", type=int, required=True)
    parser.add_argument("--run-attempt", type=int, required=True)
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--out-dir", type=Path, required=True)
    parser.add_argument("--expected-manifest-sha")
    parser.add_argument("--github-output", type=Path)
    args = parser.parse_args()
    try:
        repo = Path(__file__).resolve().parents[2]
        local_sha = subprocess.check_output(
            ["git", "-C", str(repo), "rev-parse", "HEAD"], timeout=8,
            text=True).strip()
        if local_sha != args.source_sha:
            raise TrustError("trusted workflow checkout differs from run head")
        client = GitHubClient()
        manifest, patch = verified_candidate(
            client, args.run_id, args.run_attempt, args.source_sha)
        args.out_dir.mkdir(parents=True, exist_ok=True)
        if args.out_dir.is_symlink():
            raise TrustError("output is symlinked")
        if args.mode == "inspect":
            ready = manifest["result"] == "READY"
            if ready:
                write_atomic(args.out_dir / "reviewed.patch", patch)
                write_atomic(args.out_dir / "reviewed.json", canonical_json(manifest))
            if args.github_output:
                # Only fixed safe words and digests ever enter GitHub job outputs.
                with args.github_output.open("a") as f:
                    f.write("ready=" + ("true" if ready else "false") + "\n")
                    f.write("manifest_sha=" +
                            (manifest_digest(manifest) if ready else "none") + "\n")
            result = {"status": manifest["result"], "publisher_authorized": False}
        else:
            if args.expected_manifest_sha != manifest_digest(manifest):
                raise TrustError("read-only inspector/publisher artifact mismatch")
            result = publish(client, manifest, patch)
        print(json.dumps(result, sort_keys=True))
        return 0
    except (TrustError, GitHubFailure, OSError, ValueError,
            subprocess.CalledProcessError, subprocess.TimeoutExpired) as e:
        print("PILOT_TRUSTED_PUBLISH_REFUSED: " + str(e), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
