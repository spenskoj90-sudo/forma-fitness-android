# FORMA — isolated Infinix Zero 30 device acceptance

Purpose: install a separate acceptance package ru.forma.fitness.physicaltest
beside any existing ru.forma.fitness app without touching its private files.
The label is FORMA Тест, version 0.2.0-device-test.

This is a disposable, non-release debug APK with an ephemeral signing key.
Do not expect update-over-install across CI builds of this testing package.
No privileged access, rooting, ADB, or uninstall of the old app is required.

## Installation and first pass

1. Leave the previously installed FORMA app in place. Download the isolated
   APK from the successful FORMA isolated phone-test APK workflow artifact.
2. On the phone allow installation from the file-manager/browser used for
   this single APK when Android prompts. Verify a distinct FORMA Тест
   launcher icon appears. Do not proceed if Android proposes replacing the
   existing FORMA rather than adding a separate app.
3. Launch offline, create two synthetic profiles, check isolation when
   switching users. New profiles should start with three strength sessions
   (Mon/Wed/Fri) plus optional cardio in Training.
4. Run a short strength scenario: warm-up > skip > first recorded set >
   pause/resume > stop > save. Record stage, timer, and visible history.
5. Test cardio for one minute, lock the device and return; try offline voice,
   tone and foreground controls with notifications allowed/denied.
6. Test measurements, dark/light mode, 1.3x font scale, and voluntary backup
   export/import using test data. Reopen app and verify data survives.
7. Pain/discomfort-stop is a software test only, not advice to induce pain.
   Report screenshot or exact message for any unexpected state.

Important: the user's old 0.1.0 app may have an incompatible certificate and
no export path. Never uninstall, clear storage or overwrite it to make this
testing build install. The isolated build cannot read the old app's private
history; test data is deliberately separate.

Result categories: PASS, FAIL, OBSERVED, SKIPPED, with Android version,
installation behavior and screenshots for failures. Physical acceptance and
permanent release signing remain outside CI.
