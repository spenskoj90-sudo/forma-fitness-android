# PILOT: exact-head downstream CI for generated draft PRs

This wiring is a dependency of the independently reviewed trusted draft
publisher in PR #29. The two existing source-controlled workflows retain
their native `push`, `pull_request`, and `workflow_dispatch` triggers.
They additionally subscribe to `repository_dispatch` with
`event_type=pilot-draft-ci`, using the existing publisher's minimal
`contents:write` permission to send the dispatch. No `actions:write`,
`checks:write`, or `statuses:write` scope has been added.

In each Android `verify` / API26/API30/API34 job and the backend-security
job, the initial checkout is the workflow event's **trusted main SHA**.
Before checkout or execution of the generated draft's code, the job runs
`scripts/ai-pilot/verify_dispatch.py --event "$GITHUB_EVENT_PATH"`
with **read-only** `GH_TOKEN` from that main checkout. The verifier (owned
by PR #29) must cross-check the event payload against GitHub API schedule
provenance, protected main, the exact open draft PR and head ref, and the
durable task state plus patch hash. On failure, the job stops without
checking out the candidate. Only after PASS does it checkout the immutable
head SHA in the verified payload, with `persist-credentials: false`,
and assert `git rev-parse HEAD` equals that SHA before using Gradle,
Python, PostgreSQL, or emulator test commands.

This introduces **no new authority** for the Qwen/llama.cpp model. Both
GitHub Actions workflows still grant only `contents:read`; they never
consume production signing secrets. Their original tests and API26/30/34
matrix are unchanged.

## Important GitHub status limitation

A `repository_dispatch` workflow starts on **the default branch's event
commit**, even though these jobs deliberately checkout and test the
generated *product* PR's SHA. Therefore the resulting workflow job statuses
**are not native required check contexts on the product PR head**. Record
the exact tested commit in the workflow/job evidence. Do not merge the
generated draft automatically, treat source-branch check requirements as
satisfied, or label downstream checks as PR-native. Any permission to
create native head-SHA statuses or invoke `workflow_dispatch` through
`actions:write` is a separate owner/security decision.

The new wiring depends on the reviewed `verify_dispatch.py` from PR #29
being on protected main before a `pilot-draft-ci` delivery; until then
an incoming event fails closed (the trusted verifier is missing).

## Verification contract

- `python3 -m unittest discover -s scripts -p test_pilot_dispatch_wiring.py -v`
  verifies the workflow routing and immutable-head gate order at source
  level and is exercised by existing Android PR `verify` CI.
- Existing Android `verify`, API26, API30, API34, and backend-security
  workflows must pass on the exact integration PR HEAD before merge.
- A genuine end-to-end event on a new **unclaimed approved** model task,
  publisher-created draft PR, and associated exact-head downstream test
  must be independently verified from GitHub run metadata. This is
  **NOT_RUN** until that event exists; no simulation substitutes for it.
- Physical Infinix/Poco installation, permanent release signing and
  preservation of app data across upgrades remain separate acceptance
  gates.

Existing journal: [PROJECTS #532](https://github.com/spenskoj90-sudo/alpha-0/issues/532).
