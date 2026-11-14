"""One-command, no-network Linux demo of persistent PILOT checkpoint and draft-PR dry run.

Synthetic fixture ONLY. Does not run Qwen, create a real PR, access user data,
install software, call GitHub, or grant any write permission.
"""
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

from pilot_task_state import GitLedger, claim, save_patch, verify, publish_sim


def command(*argv):
    return subprocess.check_output(["git", *map(str, argv)], timeout=12).decode().strip()


def main():
    with tempfile.TemporaryDirectory(prefix="forma-pilot-e2e-") as tmp:
        src = Path(tmp) / "source"
        archive = Path(tmp) / "durable.git"
        command("init", "-q", "-b", "main", src)
        (src / "fixture.py").write_text("def bounded(n):\n    return max(0,n)\n")
        command("-C", src, "add", "fixture.py")
        command("-C", src, "-c", "user.name=PILOT-SIM",
                "-c", "user.email=pilot-sim@example.invalid",
                "commit", "-q", "-m", "synthetic fixture")
        base = command("-C", src, "rev-parse", "HEAD")
        command("init", "-q", "--bare", archive)
        patch = (
            "--- a/fixture.py\n+++ b/fixture.py\n@@ -1,2 +1,2 @@\n"
            " def bounded(n):\n-    return max(0,n)\n+    return min(9,max(0,n))\n"
        ).encode()
        task = "pilot-synthetic-e2e-v1"
        primary = GitLedger(archive)
        claim(primary, task, base, "test-first-run", 100, [])
        save_patch(primary, task, "test-first-run", patch, base)
        before_restart = primary.read(task)[0]
        # Simulate the runner disappearing after patch persistence.
        resumed = GitLedger(archive)
        _, state = claim(resumed, task, base, "test-second-run", 3000, [])
        assert state["attempt"] == 2
        assert resumed.read(task)[2] == patch
        verify(resumed, task, "test-second-run", src, base)
        commit, published = publish_sim(resumed, task, "test-second-run", base, [])
        repeated_commit, repeated = publish_sim(resumed, task, "test-third-run", base, [])
        assert repeated_commit == commit
        assert repeated["pr_url"] == published["pr_url"]
        assert before_restart != commit
        assert published["patch_sha256"] == hashlib.sha256(patch).hexdigest()
        assert published["checks"]["git_apply_check"] == "PASS"
        assert published["checks"]["required_ci"] == "NOT_RUN"
        print(json.dumps({
            "result": "SIMULATION_PASS",
            "source_base_sha": base,
            "task_id": task,
            "attempts": published["attempt"],
            "patch_sha256": published["patch_sha256"],
            "durable_state_commit": commit,
            "git_apply_check": published["checks"]["git_apply_check"],
            "simulated_draft_pr": published["pr_url"],
            "real_pr_created": False,
            "real_schedule": "NOT_RUN",
            "real_product_model": "NOT_RUN",
            "release_signing": "NOT_RUN",
        }, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
