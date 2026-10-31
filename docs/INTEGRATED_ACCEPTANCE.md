# FORMA — integrated release, visual and multi-device acceptance

Owner update: 2026-10-24.
**No FORMA installation on either phone.** Poco is new; Infinix has been
factory-reset. There is no currently installed personal data to migrate.
First official installation will establish FORMA's permanent signing identity.

## One-app policy

- Only production application ID `ru.forma.fitness`.
- The previous `FORMA Тест` and `FORMA Проверка` draft workflows were
  superseded, with #4/#5 closed and unified draft PR #6 targeted to `main`.
- No debug or one-off signed APK is distributed as the first official app.
- No uninstall/clear-data as part of any future update. Android requires
  the same installed signature lineage and increasing versionCode.
- Release artifacts are private and produced only through the manually
  initiated, protected `signed-release.yml` workflow after verification.
- The signing key/secure backups/Actions secrets and update hosting are NOT
  configured or declared complete by these source changes.

## Engineering gates

- `domain:test`, `app:testDebugUnitTest`, `app:lintDebug` and an
  exact-commit emulator journey on API26/API30/API34 all pass.
- Existing Room v1 data works under app restarts and update-over-install;
  no schema reset, no unexpected profile mutation, no backup overwrite.
- Updater rejects bad HTTPS source, wrong package name, downgraded/same
  versionCode, wrong signer, invalid APK identity, malformed or mismatched
  SHA-256 and bad download. The Android installer asks for approval.
- First signed APK is tested on a clean Poco and Infinix; its signer SHA-256
  and hash are recorded. Only then install versionCode+1 from the exact same
  key over the first, and compare saved profile IDs/history/measurements.
- Keep copies of non-sensitive exact-SHA CI logs and device screenshots;
  never upload private profile/health data or signing keys into artifacts.

## Functional and visual gates

1. Offline first launch, create 2+ fictitious local profiles, select and
   independently record different workouts and body measurements. Confirm
   no history appears under the wrong local profile. Verify search when
   profile count exceeds four.
2. Three default separated strength sessions, optional cardio, safe pause,
   stop due to discomfort (software only), and recovery state persisted.
3. Choose HOME, GYM and COMBINED; a HOME workout never proposes a selected
   gym-only machine. Changing place invalidates stale substitutions.
   Confirm the main-screen start uses the same place.
4. Review all available exercise cues and errors; current muscles are
   highlighted schematically, **not** vetted demonstrations. Exercise-specific
   videos/animations remain a separate acceptance gate (#9).
5. Check the proportional body mannequin before/after synthetic shoulder,
   chest, waist and hips entries, with front/back visibility and clear
   disclaimer about estimation.
6. Check dark/green visual reference `Фитнес-приложение FORMA — путь к силе.png`,
   phone aspect ratios, status/navigation insets, 1.3x text scaling, keyboard,
   touch target sizes, large histories and lengthy user names.
7. Selected-profile JSON backup exports only that profile; importing into
   another clean test installation adds a separate copy, not an overwrite.
   All-profiles backup requires a warning and explicit user confirmation.
8. Exercise foreground timer works on Android background/lock-screen,
   speech and metronome work where Android offline Russian TTS is installed;
   test notifications allowed/denied and battery restrictions.

## Cloud account release gate (not yet passed)

The local-profile screen is NOT secure account login. Before claiming the
same private history is recoverable with sign-in on Infinix and Poco:

- Deploy a dedicated FORMA-only backend with verified identity and user
  sessions. NEVER share SENTINEL credentials or data infrastructure.
- Enforce real account-level access checks and forced RLS on all profile,
  workout and measurement operations, including account deletion/export.
- Prove opt-in local-to-cloud claim; reconnect both phones under one
  verified account, create distinct offline sessions, reconnect in opposite
  order, check idempotency and explicit conflict resolution.
- Check logout, account switch, token revocation, phone loss, offline login
  limits, account data deletion and privacy/consent flows.

`backend/migrations/001_account_owned_data.sql` and `domain/SyncMerge.kt`
are groundwork only; they do not mean a live backend or sync is enabled.

## Release verdict vocabulary

- **SOURCE READY:** files and tests exist, but Android build unverified.
- **CI READY:** exact-SHA unit, lint and emulator matrix passed.
- **DEVICE READY:** signed app accepted on both phones, update preserves data.
- **MULTI-DEVICE READY:** authenticated backend and both-phone cloud
  restoration/conflict checks pass.
- **PRODUCT ACCEPTED:** visual fidelity, accessible demo assets, privacy and
  all selected user requirements accepted.

Do not label any gate complete based on source commits alone.
