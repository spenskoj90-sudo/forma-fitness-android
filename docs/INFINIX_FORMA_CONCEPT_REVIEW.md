# Infinix AI → FORMA: reproducible 3D concept (review only)

## Evidence and provenance

- Local Infinix AI task ID: 20261123T103931_b06a115a.
- Base FORMA main pinned by phone: fa6c567a1bd8c9f4487a4bbb0b86980e7996bac6.
- User-supplied Infinix export: forma_20261123T103931_b06a115a.review.zip, SHA-256 e80c6d57679c7d5ce9c77e45bbe1aa9a57a73888decc757ea322d00d7b5d8a80.
- Original generated GLB: 125288 bytes, SHA-256 e7d622a770bd77026f30dfd8baa0cf0d6880de3204baf6c158be61823c27b6f5, 20 geometry nodes/meshes, 5004 triangles, 3 materials; glTF 2.0, no external resource URIs.
- Export included proposed Kotlin validator and JUnit tests. They are NOT applied in this review branch: the concept has no Android viewer, and silently bundling an unapproved asset in the app would be premature.
- This PR preserves the exact model via a deterministic standard-library generator and a hash/provenance regression, without shipping opaque binary assets in a production APK.

## Reproduce locally

    python3 scripts/forma_concept_repro.py --output /tmp/forma-original-concept.glb
    python3 -m unittest discover -s scripts -p 'test_forma_concept_repro.py' -v

The uploaded ZIP passed CRC and all four manifest SHA-256 checks. Reproduction from the original Python source yielded byte-identical GLB. Independent trimesh loading found 20 meshes and 5004 triangles. The uploaded Kotlin integrity contract was compiled locally with Kotlin/JVM 1.9.0 and passed a six-assertion harness; this does NOT count as full Android compile.

## Review and release gates

**CONCEPT_ONLY / REVIEW_REQUIRED**. The exercise media manifest remains empty (0/37). Track exercise media under [Issue #9](https://github.com/spenskoj90-sudo/forma-fitness-android/issues/9); existing [Draft PR #12](https://github.com/spenskoj90-sudo/forma-fitness-android/pull/12) owns technique-media changes.

No Android renderer, Kotlin application source, Android manifest, workout logic, Room schema, keys, production, real user data, or protected main is changed. Hosted Android CI: NOT_RUN until checks complete. Actual Android rendering: NOT_RUN. Independent exercise-technique/medical review: NOT_RUN. Owner acceptance: NOT_RUN. Do not merge as a complete 3D feature.

Next acceptance step: implement a separately reviewed Android GLB viewer with fail-closed integrity checks, accessibility, frame-time/memory/thermal metrics, and technique-media rights/validation before instructive use.
