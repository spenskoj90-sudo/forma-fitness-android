# Infinix AI → FORMA: reproducible 3D concept (review only)

## Evidence and provenance

- Local Infinix AI task ID: 20261123T103931_b06a115a.
- Base FORMA main pinned by phone: fa6c567a1bd8c9f4487a4bbb0b86980e7996bac6.
- User-supplied Infinix export: forma_20261123T103931_b06a115a.review.zip, SHA-256 e80c6d57679c7d5ce9c77e45bbe1aa9a57a73888decc757ea322d00d7b5d8a80.
- Original generated GLB: 125288 bytes, SHA-256 e7d622a770bd77026f30dfd8baa0cf0d6880de3204baf6c158be61823c27b6f5, 20 geometry nodes/meshes, 5004 triangles, 3 materials; glTF 2.0, no external resource URIs.
- Export proposed a Kotlin validator and JUnit tests. The isolated review branch now adapts that integrity contract, adds an opt-in GLES 2.0 viewer with rotate/zoom controls, and compiles the exact original GLB at build time from a deterministic generator. It never adds the asset to the exercise-technique manifest.
- This PR preserves the exact model via a deterministic standard-library generator and a hash/provenance regression, without shipping opaque binary assets in a production APK.

## Reproduce locally

    python3 scripts/forma_concept_repro.py --output /tmp/forma-original-concept.glb
    python3 -m unittest discover -s scripts -p 'test_forma_concept_repro.py' -v

The uploaded ZIP passed CRC and all four manifest SHA-256 checks. Reproduction from the original Python source yielded byte-identical GLB. Independent trimesh loading found 20 meshes and 5004 triangles. The uploaded Kotlin integrity contract was compiled locally with Kotlin/JVM 1.9.0 and passed six integrity checks. The first hosted Android build compiled the new viewer, but the new Robolectric test failed at SDK selection (Robolectric DefaultSdkPicker): fixed by pinning SDK 28, identical to existing repository tests. Final exact-head Android CI must be checked separately.

## Review and release gates

**CONCEPT_ONLY / REVIEW_REQUIRED**. The exercise media manifest remains empty (0/37). Track exercise media under [Issue #9](https://github.com/spenskoj90-sudo/forma-fitness-android/issues/9); existing [Draft PR #12](https://github.com/spenskoj90-sudo/forma-fitness-android/pull/12) owns technique-media changes.

This branch adds optional OpenGL ES 2.0 viewer code under the existing body screen, leaving the original 2D profile diagram intact. Model bytes are generated at build time and SHA-256 validated before decoding. The 3D viewer is not an exercise demonstration and stays user-initiated. No workout logic, Room schema, signer, production, real user data, protected main or exercise manifest is changed. Hosted CI: inspect new exact HEAD. Actual rendering and pixel fidelity on Infinix/Poco: NOT_RUN. Medical/technique review: NOT_RUN. Owner acceptance: NOT_RUN. Do not merge as completed 3D technique media.

Next acceptance step: complete exact-head Android CI including emulator opt-in controls, visually check actual GLB rendering on physical Infinix and Poco (frame-time, memory, thermal and accessibility), then obtain any required rights and medical/technique review separately before instructional use. Existing Draft PR #12 retains responsibility for exercise demonstrations.
