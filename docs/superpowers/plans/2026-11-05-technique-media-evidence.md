# Technique Media Evidence Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task. One writer; no duplicate PR/Issue.

**Goal:** Prepare the integrity and identity boundary required by Issue #9 without admitting unverified exercise assets.

**Architecture:** A pure domain resolver consumes a trusted source-controlled evidence list and bytes from a local reader. It fails closed and leaves current app fallback unchanged. The initial evidence list is empty.

**Tech Stack:** Kotlin/JVM 17, existing exerciseCatalog and JUnit4; no dependencies.

**Spec:** `docs/superpowers/specs/2026-11-05-technique-media-evidence-design.md`.

## Global Constraints

- Outside PR #11; branch from protected main, one writer, no merge.
- `applicationId = ru.forma.fitness`, Room/DataStore and signing unchanged.
- No downloaded assets, network fallback, new keys, deployment or paid service.
- Structural/hash validation is not instructor or license acceptance.

## Review Focus

- Wrong exercise/equipment must never select a different demonstration.
- Duplicate IDs or shared bytes must not silently choose the first record.
- Blank review/provenance/captions must remain unavailable.
- Missing/corrupt files and read exceptions must preserve fallback.
- Traversal/remote paths and nonpositive revisions must be denied.

## Task 1: Exact identity and offline integrity resolver

Files: create `domain/src/main/kotlin/ru/forma/domain/ExerciseMedia.kt`; test `domain/src/test/kotlin/ru/forma/domain/ExerciseMediaTest.kt`.

Interface: `resolveTechniqueMedia(exerciseId:String, evidence:List<TechniqueMediaEvidence>, readAsset:(String)->ByteArray?):TechniqueMediaEvidence?`. Evidence contains exact exercise ID, Equipment, positive revision, offline path, SHA-256, license, provenance, reviewer, review reference and seven named caption fields. `techniqueMediaCatalog` starts empty.

- [x] Write JUnit tests using real exerciseCatalog and actual fixture bytes: exact matching success; wrong ID/equipment, duplicate IDs/hash reuse, blank fields/captions, nonpositive revision, remote/traversal paths, corrupt/missing/unreadable bytes denied; all real catalog entries unavailable in empty manifest.
- [x] Run `./gradlew :domain:test --tests ru.forma.domain.ExerciseMediaTest --offline --console=plain`; observe missing-interface compile failure.
- [x] Implement the exact interface in ExerciseMedia.kt using java.security.MessageDigest and conservative path/metadata checks; add no media to the catalog.
- [x] Run targeted tests then full `:domain:test`; expect zero failures/errors/skips.
- [x] Read-only review; commit only the two domain files and these design/plan documents on the independent local branch. No PR or remote publication until scope is reviewed; preserve PR #11's exact CI source.

Next block after this preparation: obtain legitimate asset+caption+review evidence, add a real packaged asset and Android reader/playback integration with instrumentation coverage. Do not mark any exercise media accepted from this preparation alone.

## Execution ledger — 05.11.2026

Base: `a508ab8de3772e05e57ed5798fc9b5d7a8078718`. One writer, separate local branch `forma/media-evidence-prep-2026-11-05`; no PR/push/merge. PR #11 remote source remains `9f98e3dfc7c93d3fdc8f94b82a54ec6893f1b22e`.

Pre-flight: one task, no shared interfaces. Scope is the existing Issue #9 evidence boundary only. No app/Room/schema/backend/signing edits.

Initial targeted red: actual Gradle `compileTestKotlin` failed for missing new interface/types, as expected. First full green: 81 domain tests / 9 media tests. Added cancellation propagation and read-count assertions, then full green: 82 domain tests / 10 media tests.

Independent fresh-context read-only review found no production resolver defect. It identified masked duplicate/hash and nonpositive-revision assertions. Both were treated as important test-contract gaps: return real fixture bytes and assert zero reads; pair revision 0/-1 with matching v0/v-1 paths. A deliberate temporary guard-removal mutation caused exactly those two tests to fail (10 tests, 2 expected assertion failures). Restored all original guards; final actual `:domain:test --offline --console=plain` PASS, exit0, 1m2s: **82 tests, 0 failures/errors/skips; media 10/10**. No mutation remains in the source. `git diff --check` PASS.

Task 1: complete as independent source preparation. Hosted Android/app/instrumentation CI on this local branch: NOT_RUN. Actual licensed/qualified-review media, Android asset reader/player integration, offline playback and phone/font/accessibility acceptance: NOT_DONE/NOT_RUN. Accepted asset coverage remains **0/37**. No deferred review findings.

Next safe source action: wire this resolver to a local Android Assets reader and existing text fallback in its own bounded review unit; test missing/corrupt assets and equipment replacement without supplying unreviewed demonstrations. Admit an actual manifest entry only after its exact asset hash/revision/captions/license/provenance and qualified review evidence are available.
