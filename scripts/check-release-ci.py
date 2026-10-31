#!/usr/bin/env python3
"""Fail-closed exact-SHA CI gate for owner-controlled FORMA signing.

Uses only GitHub Actions read-only token, never a signing secret.
"""
import json
import os
import sys
from urllib.request import Request, urlopen

REQUIRED = {"verify", "instrumentation (26)", "instrumentation (30)", "instrumentation (34)"}
ACTIONS_APP_ID = 15368


def evaluate(checks, exact_sha):
    latest = {}
    for check in checks:
        name = check.get("name")
        if name not in REQUIRED or check.get("head_sha") != exact_sha:
            continue
        if (check.get("app") or {}).get("id") != ACTIONS_APP_ID:
            continue
        if name not in latest or check.get("id", 0) > latest[name].get("id", 0):
            latest[name] = check
    return {name: (
        latest[name]["status"] == "completed"
        and latest[name]["conclusion"] == "success"
    ) if name in latest else False for name in sorted(REQUIRED)}


def main():
    if len(sys.argv) != 3:
        raise SystemExit("Usage: check-release-ci.py <owner/repo> <exact-sha>")
    repo, sha = sys.argv[1:]
    if repo != "spenskoj90-sudo/forma-fitness-android" or len(sha) != 40 or any(c not in "0123456789abcdef" for c in sha):
        raise SystemExit("Unexpected repository or source SHA")
    token = os.environ.get("GITHUB_TOKEN", "")
    if not token:
        raise SystemExit("Missing read-only GitHub Actions token")
    req = Request(
        f"https://api.github.com/repos/{repo}/commits/{sha}/check-runs?per_page=100",
        headers={"Authorization": f"Bearer {token}",
                 "Accept": "application/vnd.github+json",
                 "X-GitHub-Api-Version": "2022-11-28"}
    )
    with urlopen(req, timeout=15) as response:
        checks = json.load(response)["check_runs"]
    result = evaluate(checks, sha)
    for name, passed in result.items():
        print(f"{name}: {'PASS' if passed else 'FAIL'}")
    if not all(result.values()):
        raise SystemExit("Exact main source CI is not fully successful")
    print(f"Exact source {sha} approved by all required Android checks")


if __name__ == "__main__":
    main()
