"""Offline, deterministic PILOT role routing; never installs or executes a tool.

Only explicitly audited, already running PILOT components can be selected.
Every external candidate remains a preview until separate engineering review.
"""
import argparse
import json
from pathlib import Path
import re
import sys

CATALOG = Path(__file__).resolve().parent / "ai-pilot" / "tool_catalog.json"
ROLES = frozenset({
    "coordination", "author", "review", "inference", "qa", "build",
    "security", "design", "asset3d", "render3d",
})
ACTIVE = {
    "pilot-queue": "spenskoj90-sudo/forma-fitness-android",
    "llama-cpp": "ggml-org/llama.cpp",
    "github-runner": "actions/runner",
}
ALLOWED_LICENSES = {
    "INTERNAL", "MIT", "Apache-2.0", "MPL-2.0",
    "AGPL-3.0", "NOASSERTION",
}
RECORD_FIELDS = {
    "id", "repo", "license", "roles", "stage", "priority",
    "scope", "gate", "paid_api_required", "privileged_write",
    "persistent_host", "service_quota", "secret_required",
}
POLICY = {
    "default": "deny", "execution_from_catalog": False, "auto_install": False,
    "auto_promote": False, "paid_api": False, "privileged_write": False,
    "production": False, "signing": False,
}
SLUG = re.compile(r"[a-z][a-z0-9-]{1,60}\Z")
REPO = re.compile(r"[A-Za-z0-9-]+/[A-Za-z0-9_.-]+\Z")
DAY = re.compile(r"20[0-9]{2}-[01][0-9]-[0-3][0-9]\Z")


class PolicyError(ValueError):
    pass


def checked_catalog(data):
    if not isinstance(data, dict) or set(data) != {"schema", "reviewed_utc", "policy", "tools"}:
        raise PolicyError("invalid registry root")
    if type(data["schema"]) is not int or data["schema"] != 1 or data["policy"] != POLICY:
        raise PolicyError("registry policy or version changed")
    if not isinstance(data["reviewed_utc"], str) or not DAY.fullmatch(data["reviewed_utc"]):
        raise PolicyError("missing review date")
    records = data["tools"]
    if not isinstance(records, list) or not 3 <= len(records) <= 64:
        raise PolicyError("invalid tool inventory")
    ids = set()
    active = set()
    for t in records:
        if not isinstance(t, dict) or set(t) != RECORD_FIELDS:
            raise PolicyError("invalid tool fields")
        name, repo, stage = t["id"], t["repo"], t["stage"]
        if not isinstance(name, str) or not SLUG.fullmatch(name) or name in ids:
            raise PolicyError("invalid or duplicate tool ID")
        ids.add(name)
        if not isinstance(repo, str) or not REPO.fullmatch(repo):
            raise PolicyError("invalid GitHub repository identifier")
        if t["license"] not in ALLOWED_LICENSES or stage not in ("active", "evaluate", "defer"):
            raise PolicyError("unknown license or integration stage")
        if type(t["priority"]) is not int or not 0 <= t["priority"] <= 100:
            raise PolicyError("invalid deterministic priority")
        roles = t["roles"]
        if not isinstance(roles, list) or not roles or len(set(roles)) != len(roles) or any(
            not isinstance(x, str) or x not in ROLES for x in roles
        ):
            raise PolicyError("invalid tool role")
        if any(type(t[flag]) is not bool for flag in (
            "paid_api_required", "privileged_write", "persistent_host",
            "service_quota", "secret_required",
        )):
            raise PolicyError("invalid risk flag")
        if any(not isinstance(t[key], str) or not 8 <= len(t[key]) <= 240
               or "\n" in t[key] for key in ("scope", "gate")):
            raise PolicyError("invalid non-executable note")
        if stage == "active":
            if name not in ACTIVE or repo != ACTIVE[name]:
                raise PolicyError("unreviewed tool cannot become active")
            if (t["paid_api_required"] or t["privileged_write"] or
                    t["persistent_host"] or t["secret_required"] or
                    t["license"] == "NOASSERTION"):
                raise PolicyError("active tool violates permissions/cost policy")
            active.add(name)
    if active != set(ACTIVE):
        raise PolicyError("mandatory active baseline changed")
    return records


def route(records, role, include_candidates=False):
    """Returns a read-only route recommendation; NEVER grants execution."""
    if role not in ROLES:
        raise PolicyError("unrecognised task role")
    matching = sorted((t for t in records if role in t["roles"]),
                      key=lambda t: (t["priority"], t["id"]))
    usable = [t for t in matching if t["stage"] == "active"]
    output = {
        "role": role,
        "status": "ROUTABLE" if usable else "BLOCKED",
        "selected": usable[0]["id"] if usable else None,
        "execution_granted": False,
        "reason": "Existing approved component; planning only" if usable else
                  "No approved executor; human engineering gate remains",
    }
    if include_candidates:
        output["candidates"] = [
            {"id": t["id"], "stage": t["stage"], "gate": t["gate"]}
            for t in matching if t["stage"] != "active"
        ]
    return output


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--role", choices=sorted(ROLES))
    parser.add_argument("--candidates", action="store_true")
    parser.add_argument("--validate", action="store_true")
    args = parser.parse_args()
    if not args.role and not args.validate:
        parser.error("specify --role or --validate")
    try:
        if not CATALOG.is_file() or CATALOG.stat().st_size > 100_000:
            raise PolicyError("registry missing or oversized")
        records = checked_catalog(json.loads(CATALOG.read_text(encoding="utf-8")))
        result = (route(records, args.role, args.candidates) if args.role else
                  {"status": "PASS", "tools": len(records), "active": len(ACTIVE),
                   "execution_granted": False})
        print(json.dumps(result, sort_keys=True, ensure_ascii=False))
        return 0
    except (PolicyError, OSError, ValueError, TypeError, UnicodeError) as exc:
        print("PILOT_TOOL_POLICY_FAIL: " + str(exc), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
