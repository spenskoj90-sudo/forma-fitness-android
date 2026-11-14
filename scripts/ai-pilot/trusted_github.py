"""GitHub API transport for the trusted (not model-runner) PILOT publisher."""
import json
import os
import re
import urllib.error
import urllib.parse
import urllib.request

from trusted_artifacts import REPO, TOTAL_COMPRESSED, TrustError

BASE = "https://api.github.com/repos/" + REPO
ALLOWED_REDIRECT_SUFFIXES = (
    ".blob.core.windows.net", ".githubusercontent.com",
    ".actions.githubusercontent.com", ".github.com",
)


class GitHubFailure(TrustError):
    pass


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class GitHubClient:
    def __init__(self, token=None):
        self.token = token or os.environ.get("GH_TOKEN")
        if not self.token:
            raise GitHubFailure("missing scoped GitHub job token")

    def request(self, method, path, obj=None, absent=False):
        if (not isinstance(path, str) or not path.startswith("/") or
                "//" in path or ".." in path or
                not method in ("GET", "POST", "PATCH")):
            raise GitHubFailure("invalid GitHub endpoint")
        data = json.dumps(obj).encode() if obj is not None else None
        req = urllib.request.Request(
            BASE + path, method=method, data=data,
            headers={"Authorization": "Bearer " + self.token,
                     "Accept": "application/vnd.github+json",
                     "X-GitHub-Api-Version": "2022-11-28",
                     "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=25) as response:
                raw = response.read(2_000_000)
        except urllib.error.HTTPError as e:
            if e.code == 404 and absent:
                return None
            raise GitHubFailure(f"GitHub {method} endpoint returned HTTP {e.code}") from None
        except (urllib.error.URLError, TimeoutError) as e:
            raise GitHubFailure("GitHub endpoint unavailable") from e
        if not raw:
            # POST /dispatches succeeds with HTTP 204 (no response body).
            return {}
        try:
            return json.loads(raw)
        except (ValueError, UnicodeError) as e:
            raise GitHubFailure("non-JSON GitHub response") from e

    def get(self, path, absent=False):
        return self.request("GET", path, absent=absent)

    def post(self, path, body):
        return self.request("POST", path, body)

    def patch(self, path, body):
        return self.request("PATCH", path, body)

    def main_sha(self):
        result = self.get("/git/ref/heads/main")
        sha = result.get("object", {}).get("sha")
        if not isinstance(sha, str) or not re.fullmatch("[0-9a-f]{40}", sha):
            raise GitHubFailure("protected main ref missing")
        return sha

    def run(self, run_id):
        if type(run_id) is not int or run_id < 1:
            raise GitHubFailure("invalid run ID")
        return self.get(f"/actions/runs/{run_id}")

    def workflow(self):
        return self.get("/actions/workflows/ai-work-queue.yml")

    def artifact(self, run_id, attempt):
        data = self.get(f"/actions/runs/{run_id}/artifacts?per_page=100")
        items = data.get("artifacts")
        if not isinstance(items, list) or data.get("total_count", 0) > 100:
            raise GitHubFailure("incomplete artifact list")
        expected = f"forma-ai-queue-{run_id}-{attempt}"
        matches = [x for x in items if x.get("name") == expected]
        if len(matches) != 1:
            raise GitHubFailure("missing or duplicated exact-attempt artifact")
        item = matches[0]
        if type(item.get("id")) is not int or item["id"] < 1:
            raise GitHubFailure("invalid artifact ID")
        return item

    def zip_artifact(self, artifact_id):
        if type(artifact_id) is not int or artifact_id < 1:
            raise GitHubFailure("invalid ZIP artifact ID")
        request = urllib.request.Request(
            BASE + f"/actions/artifacts/{artifact_id}/zip",
            headers={"Authorization": "Bearer " + self.token,
                     "Accept": "application/vnd.github+json"})
        opener = urllib.request.build_opener(NoRedirect())
        raw = None
        try:
            with opener.open(request, timeout=25) as response:
                raw = response.read(TOTAL_COMPRESSED + 1)
        except urllib.error.HTTPError as e:
            if e.code not in (301, 302, 303, 307, 308):
                raise GitHubFailure(f"artifact API returned HTTP {e.code}") from None
            location = e.headers.get("Location", "")
            parsed = urllib.parse.urlparse(location)
            host = (parsed.hostname or "").lower()
            if (parsed.scheme != "https" or not host or
                    not any(host.endswith(s) for s in ALLOWED_REDIRECT_SUFFIXES)):
                raise GitHubFailure("unsafe artifact redirect destination")
            # Never forward GitHub bearer credentials to the signed download URL.
            try:
                with urllib.request.urlopen(location, timeout=30) as response:
                    raw = response.read(TOTAL_COMPRESSED + 1)
            except (urllib.error.URLError, TimeoutError) as exc:
                raise GitHubFailure("artifact signed URL could not be downloaded") from exc
        except (urllib.error.URLError, TimeoutError) as exc:
            raise GitHubFailure("artifact API unavailable") from exc
        if not raw or len(raw) > TOTAL_COMPRESSED:
            raise GitHubFailure("downloaded ZIP too large or missing")
        return raw

    def pulls(self):
        all_prs = []
        for page in range(1, 22):
            data = self.get(f"/pulls?state=all&per_page=100&page={page}")
            if not isinstance(data, list):
                raise GitHubFailure("invalid PR search response")
            all_prs.extend(data)
            if len(data) < 100:
                return all_prs
            if len(all_prs) > 2000:
                raise GitHubFailure("PR pagination exceeds safe cap")
        raise GitHubFailure("incomplete PR pagination")

    def branch_ref(self, branch):
        if not re.fullmatch(r"(?:pilot/task-|pilot-state/)[a-z0-9-]{3,75}", branch):
            raise GitHubFailure("branch outside task namespace")
        return self.get("/git/ref/heads/" + branch, absent=True)
