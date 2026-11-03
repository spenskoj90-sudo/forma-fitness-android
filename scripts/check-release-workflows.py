#!/usr/bin/env python3
"""Source-level check of FORMA-only release signing routes. Never reads secrets."""
from pathlib import Path
import re
import sys

workflows = Path(__file__).resolve().parent.parent / ".github" / "workflows"
approved = workflows / "signed-release.yml"
legacy = workflows / "release-candidate.yml"


def require(condition: bool, message: str) -> None:
    if not condition:
        print("FORMA signing guard FAIL: " + message, file=sys.stderr)
        raise SystemExit(1)


require(not legacy.exists(), "legacy candidate workflow must be deleted")
require(approved.is_file(), "approved manual release workflow is missing")
files = sorted(workflows.glob("*.yml")) + sorted(workflows.glob("*.yaml"))
uses_secret = re.compile(r"secrets\s*(?:\.\s*|\[\s*['\"])FORMA_RELEASE_[A-Z0-9_]+")
consumers = [path for path in files if uses_secret.search(path.read_text(encoding="utf-8"))]
require(consumers == [approved], "exactly one FORMA release secret consumer is allowed")
source = approved.read_text(encoding="utf-8")
events = re.search(r"(?m)^on:\s*\n((?:[ \t]+.*\n|\n)*)", source)
require(events is not None, "on: declaration is missing")
top_events = [line.strip().rstrip(":") for line in events.group(1).splitlines()
              if line.startswith("  ") and not line.startswith("    ") and line.strip()]
require(top_events == ["workflow_dispatch"], "only manual workflow_dispatch is permitted")
require("if: github.ref == 'refs/heads/main'" in source, "main-only job guard is missing")
require(re.search(r"(?m)^    environment:\s*forma-release\s*$", source) is not None,
        "protected forma-release environment is required")
require(re.search(r"(?m)^permissions:\s*\n  contents:\s*read\s*\n  checks:\s*read\s*$", source) is not None,
        "read-only contents/checks permissions are required")
print("FORMA signing guard PASS: one manual main-only environment-gated signing route")

# Gradle caches may retain a plaintext signed APK or signing configuration.
require(re.search(r"uses: gradle/actions/setup-gradle@[0-9a-f]{40}\s*\n        with:\s*\n          cache-disabled: true",source) is not None,
        "release Gradle cache must be disabled")
for flag in ('--no-build-cache','--no-configuration-cache','--no-daemon','--no-scan'):
    require(flag in source,"release Gradle invocation missing "+flag)
uploads=re.findall(r"uses: actions/upload-artifact@[^\n]+\n((?:        .*\n|\n)*)",source)
require(len(uploads)==1,"exactly one allowlisted encrypted delivery upload is permitted")
subjects=re.findall(r"^            (\S+)$",uploads[0],re.MULTILINE)
require(subjects==['private-delivery/forma-release.aesgcm.json','private-delivery/APK_IDENTITY.json','private-delivery/RECEIPT.json'],
        "only encrypted capsule and allowlisted metadata may be uploaded")
print("FORMA delivery guard PASS: no plaintext APK upload or Gradle cache publication")
