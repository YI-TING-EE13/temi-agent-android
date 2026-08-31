# Current Status

Status date: **2026-08-31**

This document is the current-truth record for the accepted Android publication
baseline. It separates current source facts from verified evidence,
not-verified behavior, historical notes, and external dependencies. The
feature-level evidence matrix is in [VERIFIED_FEATURES.md](./VERIFIED_FEATURES.md),
and the source-oriented tree is in [REPOSITORY_MAP.md](./REPOSITORY_MAP.md).

## Canonical repository

| Field | Accepted value |
| --- | --- |
| Repository | `YI-TING-EE13/temi-agent-android` |
| Canonical branch | `main` |
| Required starting canonical `main` | `e8cdf7859923c082162fd49be6c435287fd301e8` |
| Previously accepted implementation/runtime baseline | `8c458888657efca5384c6d51e5ec57e8b385d987` |
| Current documentation HEAD | See the repository's current `main` branch |
| Android package | `com.robotemi.agent` |
| Repository release candidate | `1.0.4` / `versionCode 5` |
| Current physical Temi baseline before V4B | `1.0.3` / `versionCode 4` |
| Status date | `2026-08-31` |

The previously accepted implementation/runtime evidence remains tied to
`8c458888657efca5384c6d51e5ec57e8b385d987`. The V4A candidate starts from
`e8cdf7859923c082162fd49be6c435287fd301e8` and restores the two authorized
project-owned exercise resources while advancing the repository artifact to
versionCode 5 / versionName 1.0.4. V4A does not install the candidate or
replace the current physical Temi baseline, which remains versionCode 4 /
versionName 1.0.3 until V4B.

The literal current documentation-inclusive HEAD should be read from the
repository's current GitHub `main` branch, rather than hardcoded in this
current-status document. Any future Android source or runtime change requires
fresh re-acceptance before it can replace the accepted implementation/runtime
baseline.

## Verified build baseline

The current source and A1 acceptance record establish this build baseline:

| Item | Baseline |
| --- | --- |
| JDK | 21 |
| Gradle | 8.13, through the included wrapper |
| Android Gradle Plugin | 8.13.2 |
| Kotlin | 1.9.20 |
| `compileSdk` | 34 |
| `minSdk` | 23 |
| `targetSdk` | 30 |
| Temi SDK | `com.robotemi:sdk:1.134.1` |

A1 evidence is **VERIFIED** at build level:

- `288/288` JVM tests passed.
- `assembleDebug` passed.
- The signed `assembleDemo` build passed.
- Demo artifact preflight passed.
- A fresh-clone rebuild passed, including the public-source build path.

The public CI workflow covers JDK 21, JVM tests, and `assembleDebug`. It does
not provide Demo signing, Temi device, private backend, or full AI6 evidence.

## Previously accepted Demo artifact contract

The previously accepted Demo variant had this packaged contract:

| Field | Value |
| --- | --- |
| Package | `com.robotemi.agent` |
| `versionCode` | `3` |
| `versionName` | `1.0.2` |
| Demo `debuggable` | `false` |
| Media v1.1 enabled | `true` |
| Media attach deadline | `10000 ms` |
| Expected Demo signer SHA-256 | `4DA8461B45B02FADCB042F63151FEE05D56EBD5105EB721D7D62E30B88513A7F` |

## V4A repository release candidate

The V4A repository candidate has this intended packaged contract:

| Field | Value |
| --- | --- |
| Package | `com.robotemi.agent` |
| `versionCode` | `5` |
| `versionName` | `1.0.4` |
| Project-owned raw resources | `elderly_hand_exercise.mp4`, `elderly_leg_exercise.mp4` |
| Media v1.1 enabled | `true` in Demo |
| Media attach deadline | `10000 ms` in Demo |
| Physical playback status | Not `VERIFIED_DEVICE`; V4B pending |

The two exercise resources are `TRACKED_PROJECT_ASSET` files. The command
allowlist remains limited to `elderly_hand_exercise` and
`elderly_leg_exercise`; arbitrary media paths and URLs remain prohibited.

The signer digest is a public artifact-verification identity; the private
signing inputs remain out of band. An APK hash identifies one concrete built
artifact and is not a permanent source property. Source HEAD, version,
signer identity, and packaged policy establish the recorded provenance. This
repository does not currently claim bit-for-bit reproducible APK builds.

## Current physical Temi baseline

At the start of V4A, the task-provided physical Temi baseline is
`com.robotemi.agent`, versionCode 4 / versionName 1.0.3. V4A performs no APK
installation, uninstall, data clear, reboot, runtime media test, MQTT change,
or AI6 restart. The candidate remains pending V4B physical acceptance.

## Previously accepted physical Temi evidence

The following **VERIFIED_DEVICE** evidence was accepted for the earlier
forward-upgrade run. The operator endpoint and machine details are intentionally
omitted:

- Existing signed app provenance was inspected.
- Forward upgrade from `versionCode 2` to `versionCode 3` was accepted.
- Exactly one normal `adb install -r` succeeded.
- No downgrade was used.
- The Android `userId`, `dataDir`, and `firstInstallTime` were preserved.
- The installed APK matched the built acceptance artifact.
- The installed Demo was non-debuggable.
- `MainActivity` launch passed.
- No immediate application fatal exception was observed.

This is a bounded physical upgrade and launch record, not a claim that every
robot capability or lifecycle path is device-accepted.

## MQTT Android-side acceptance

The Android-side evidence records the following **VERIFIED** facts:

- `MqttLifecycleService` starts and owns the long-lived Android-side MQTT
  lifecycle.
- The persisted MQTT runtime endpoint remained usable after the upgrade.
- Android performed broker connection attempts.
- The physical Temi canonical app received CONNACK and reached `CONNECTED` in
  the accepted lab evidence.
- No MQTT service crash loop was observed.

The broker, AI6 backend, and their lifecycle are **EXTERNAL_DEPENDENCY**. The
final Android/AI6 contract is **PENDING_AI6_FINAL_CONTRACT**. The following
claims remain **NOT_VERIFIED** by this baseline: final full-stack AI6
acceptance, a final canonical `noop` round trip, care end-to-end behavior,
resident-identity end-to-end behavior, and physical completion of all command
actions.

## Build-time feature state

The accepted Demo build-time state is:

| BuildConfig field | Accepted Demo value |
| --- | --- |
| `MEDIA_V11_ENABLED` | `true` |
| `MEDIA_V11_ATTACH_DEADLINE_MS` | `10000` |
| `RESIDENT_IDENTITY_ENABLED` | `false` |
| `CARE_REPORT_ENABLED` | `false` |
| `LEGACY_MQTT_ACTIONS_ENABLED` | `false` |

These are Android build/runtime configuration facts. They do not prove that
the corresponding external backend, broker, WebSocket, identity, care, or
media deployment is available. Resident Identity and Care Report are therefore
disabled in the accepted Demo baseline even though their source and JVM tests
are present.

## Known handover gaps

Only the following are current gaps for handover:

- Final Android/AI6 contract compatibility review is pending the AI6 freeze.
- Physical playback of the restored exercise resources remains pending V4B.
- Release and tag policy is not yet finalized.
- Signing private-key custody remains an out-of-band operator responsibility.
- The applicable license decision has not yet been selected.

The old performance note under `docs/performance/` is **HISTORICAL** engineering
evidence and is not a current device-acceptance record. Resolved publication,
security, and version-upgrade blockers are not repeated here as current
blockers.

## Evidence vocabulary

- **CURRENT**: describes the accepted source tree or current configuration.
- **VERIFIED**: backed by the stated source, unit, build, device, or end-to-end
  evidence level.
- **NOT_VERIFIED**: no sufficient acceptance evidence was available.
- **HISTORICAL**: retained context that must not be used as current acceptance.
- **EXTERNAL_DEPENDENCY**: outside the Android repository and its direct
  control boundary.
