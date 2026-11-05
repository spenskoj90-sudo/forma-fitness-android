# Exercise media evidence preparation — Issue #9

Status: independent source preparation, not exercise-media acceptance. No assets, player or UI claims added. Keep outside PR #11.

Issue #9 requires exact exercise-ID/equipment matching, traceable asset hash, revision, license/provenance, instructor review, accessible technique captions and offline files. Current catalog/text/schematic fallback stays available. This block builds a deterministic domain resolver for a trusted, source-reviewed evidence manifest; it does not certify movement accuracy or license validity merely because strings are present.

Approaches considered: adding media directly is blocked by missing licensed/instructor-reviewed assets; a UI/player now would have no accepted input. A pure Kotlin evidence resolver plus behavioral tests is independently useful and needs no owner signing/cloud gate. This is the engineer's implementation choice under the user's instruction to continue safe independent preparation.

The resolver accepts the exact exercise ID, a trusted evidence list, and a local asset-byte reader. It returns evidence only when the catalog ID/equipment match, IDs and asset hashes are unambiguous, required provenance/review/caption fields are nonblank, revision is positive, the path is exactly `technique/<exerciseId>/v<revision>.mp4` or `.webm`, and actual bytes match SHA-256. Missing, malformed, unknown, duplicate, mismatched or unreadable input returns unavailable. No network fallback. Caption fields cover setup, start, motion, finish, range, breathing and errors. The manifest starts empty; honest accepted coverage remains 0/37.

No Profile/Room/DataStore/schema/applicationId/signing/deployment changes. Future app integration must use the packaged trusted manifest and Android Assets reader, keep the current fallback for unavailable assets, and open playback only after the resolver succeeds. Human review must bind the exact asset hash/revision and caption contents before any record is accepted into that manifest. Owner/instructor review, 37 real assets, actual offline playback and Infinix/Poco accessibility checks remain separate, uncompleted gates.

## F2 Android vertical — 05.11.2026

Continue local 015cb6269a8405e5b71c6c1b9970e38bf1d270de; PR #11 stays separate. Android Assets manifest v1 must equal the source-admitted registry, not merely contain plausible review strings. Strict JSON parsing rejects unknown/duplicate fields. Bound manifest to 256 KiB, video to 16 MiB, duration to 3 minutes, dimensions to 1920×1080. Initially ship an empty manifest and no demonstration files: 0/37 accepted.

Use native MediaExtractor/MediaCodecList on Dispatchers.IO to validate MP4/H.264 (the initial Android playback allowlist; WebM remains unavailable) before offering playback. Read/hash/probe once off main, retain private bounded bytes; use MediaDataSource for the same bytes in MediaPlayer. No file cache, URL, network, autoplay, audio focus or sound. Pause on lifecycle background; release on disposal/surface destruction. Decode failure returns text fallback. Existing workout controls and persistence stay independent.

A shared Compose component retains cue and mistakes, exposes an accessible 48dp disclosure/play control, displays all seven caption stages and attribution, and keys its entire state by exercise ID/equipment. Program alternatives, library and active exercise use the same component; stale asynchronous loads cannot show the prior exercise. Text flows in the existing scroll container with no fixed-height caption clipping.

Regression coverage: domain resolver, manifest/admission/size/integrity/format pipeline unit tests; test-APK-only synthetic video validates actual AssetManager, extractor, decoder and player offline; Compose switching, empty production manifest, large fonts/semantics and reachable controls. Existing journey/recovery/storage remain required. No synthetic media enters src/main.
