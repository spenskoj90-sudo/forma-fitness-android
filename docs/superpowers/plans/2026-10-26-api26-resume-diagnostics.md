# API26 resume evidence and PR validation plan

> Native execution in the existing PR #6 branch; one coherent batch. No release or merge acceptance is implied.

**Goal:** Identify where the API26 Resume command stops and make its CI evidence originate from a pull_request event.

**Architecture:** Preserve the original recovery assertion, timeout, repository transactions, signer, API26/30/34 matrix and existing artifact consumers. Add debug-only transition outcomes and screenshots while Activity is alive. Remove feature-branch push triggering when PR triggering is introduced.

**Spec:** Master v2.4 and Forma issue #10. Source head: d16af329bf181cbe0bd9b96531b6a89276d95764.

## Tasks and checks

- [x] Read exact-head source and API26 run 40097981189/job 120885472400 logcat artifact 12562311354. No requestAudioFocus from the Recovery process appears after the tap; this does not prove a cause.
- [x] Add non-PII debug outcomes to FormaViewModel.resume; preserve exception propagation and command semantics.
- [x] Add before-tap and timeout screenshots/semantics to RecoveryTest, retaining durable resume/live tick/stop/history assertions and 20-second timeout.
- [x] Add pull_request; retain push main and workflow_dispatch. Cancel only superseded initial PR runs; isolate main/manual/rerun evidence. Keep all three emulators and existing artifact paths.
- [ ] Verify YAML, shell syntax and diff locally; Android SDK/Gradle unavailable here, no compile claim.
- [ ] Publish one commit to the SAME PR #6 branch after current-head lease verification; no extra PR.
- [ ] Read exact-head CI failures together; use diagnostic evidence to establish cause before a behavioral fix.
- [ ] Retest API26/30/34 on the fix SHA. Keep #10 open and PR draft until proven; physical signing/update gates remain separate.

## Review focus

- Initial PR duplicate push: no forma/** push trigger after PR introduction.
- Manual and main runs: cancellation false, unique run IDs.
- Resume still fails: capture the actual app before teardown, never change timeout/mark flaky.
- Sensitive records: trace logs contain booleans and exception class only; screenshots are synthetic emulator evidence.
- Recovery safety: no schema, profile, session timing, signer or production-provider changes.

## Rollback

Revert this diagnostic commit in the same branch. No data migration or provider state rollback is needed. This batch does not implement FAST/FULL/RELEASE separation or required-check enforcement; Forma Free private enforcement remains unverified.
