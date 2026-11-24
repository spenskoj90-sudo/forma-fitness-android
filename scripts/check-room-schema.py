#!/usr/bin/env python3
"""Guard the frozen Room v1/v2 schemas and additive v3 migration."""
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path

root = Path(__file__).resolve().parent.parent
v1_rel = "app/schemas/ru.forma.app.FormaDatabase/1.json"
v2_rel = "app/schemas/ru.forma.app.FormaDatabase/2.json"
v3_rel = "app/schemas/ru.forma.app.FormaDatabase/3.json"
storage = (root / "app/src/main/java/ru/forma/app/Storage.kt").read_text(encoding="utf-8")


def fail(message):
    print("FORMA Room schema FAIL: " + message, file=sys.stderr)
    raise SystemExit(1)


if not re.search(r"@Database\([^\n]*\bversion\s*=\s*3\b", storage):
    fail("version 3 must be explicit; no automatic destructive migration")
if "MIGRATION_1_2" not in storage or ".addMigrations(MIGRATION_1_2,MIGRATION_2_3)" not in storage:
    fail("existing databases require explicit v1→v2→v3 migrations")
if not (root / v1_rel).is_file():
    fail("missing frozen compiler-generated v1 baseline")
try:
    v1 = json.loads((root / v1_rel).read_text(encoding="utf-8"))["database"]
except (OSError, ValueError, KeyError) as exc:
    fail("invalid v1 baseline: " + str(exc))
if v1.get("version") != 1 or v1.get("identityHash") != "4863845aa8163bf8ac2c52e4fc5f0304":
    fail("v1 version/hash changed; data lineage must remain intact")
if len(v1["entities"]) != 1 or v1["entities"][0]["tableName"] != "profiles":
    fail("frozen v1 profile table changed")
if {field["fieldPath"] for field in v1["entities"][0]["fields"]} != {
    "id", "name", "document"
}:
    fail("frozen v1 profile columns changed")

v2_path = root / v2_rel
if not v2_path.is_file():
    fail("Room/KSP did not generate v2 schema: " + v2_rel)
try:
    v2_text = v2_path.read_text(encoding="utf-8")
    v2 = json.loads(v2_text)["database"]
except (OSError, ValueError, KeyError) as exc:
    fail("invalid generated v2 schema: " + str(exc))
if v2.get("version") != 2 or not v2.get("identityHash"):
    fail("v2 version/identity hash is missing")
if {entity["tableName"] for entity in v2["entities"]} != {
    "profiles", "sync_bindings", "sync_outbox"
}:
    fail("v2 must contain only the original profiles and new binding/outbox tables")
if not any(
    x["tableName"] == "profiles" and x["fields"] == v1["entities"][0]["fields"]
    for x in v2["entities"]
):
    fail("v2 must preserve all v1 profile columns")

for baseline, digest in {
    v1_rel: "ddeff7ecbd377f7c90051bcfab867c6e9e0e8a5480fccf49a1a402bc1a6cdc42",
    v2_rel: "aebe440fd009ba878cefadb3855f11b456e70a0c0f24467b9789de32804d9783",
}.items():
    if hashlib.sha256((root / baseline).read_bytes()).hexdigest() != digest:
        fail("modified frozen schema: " + baseline)

try:
    v3 = json.loads((root / v3_rel).read_text(encoding="utf-8"))["database"]
except (OSError, ValueError, KeyError) as exc:
    fail("invalid compiler-generated v3 schema: " + str(exc))
before = {entity["tableName"]: entity for entity in v2["entities"]}
after = {entity["tableName"]: entity for entity in v3["entities"]}
if v3.get("version") != 3 or not v3.get("identityHash"):
    fail("v3 version/identity hash is missing")
if after.keys() - before.keys() != {"sync_review", "sync_pull", "sync_resolution_archive"}:
    fail("unexpected v3 tables")
if any(after.get(table) != schema for table, schema in before.items()):
    fail("v3 must preserve the exact v2 profile, binding and outbox schema")

tracked = subprocess.run(
    ["git", "ls-files", "--", "app/schemas"], cwd=root,
    capture_output=True, text=True, check=True
).stdout.splitlines()
generated = {p.relative_to(root).as_posix() for p in (root / "app/schemas").rglob("*.json")}
if set(tracked) != {v1_rel, v2_rel, v3_rel} or generated != {v1_rel, v2_rel, v3_rel}:
    fail("track the compiler-generated v3 schema alongside the frozen baselines")
changed = subprocess.run(
    ["git", "diff", "--exit-code", "--", v1_rel, v2_rel, v3_rel], cwd=root, capture_output=True
)
if changed.returncode != 0:
    fail("compiled Room schema differs from tracked files")
print("FORMA Room schema PASS: frozen v1/v2 and additive compiler-generated v3 verified")
