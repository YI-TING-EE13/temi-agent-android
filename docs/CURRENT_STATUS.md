# Current Status

Status date: **2026-09-01**

This document is the current-truth record for the accepted Android publication
baseline. It separates current source facts from verified evidence,
not-verified behavior, historical notes, and external dependencies. The
feature-level evidence matrix is in [VERIFIED_FEATURES.md](./VERIFIED_FEATURES.md),
and the source-oriented tree is in [REPOSITORY_MAP.md](./REPOSITORY_MAP.md).
The successor landing register is [HANDOVER_READINESS.md](./handover/HANDOVER_READINESS.md).

## Canonical repository

| Field | Accepted value |
| --- | --- |
| Repository | `YI-TING-EE13/temi-agent-android` |
| Public clone URL | `https://github.com/YI-TING-EE13/temi-agent-android.git` |
| Canonical branch | `main` |
| Required canonical `main` baseline for G3B | `b22cce606074e1843bcd4770517482336522942e` |
| Historical V4E candidate starting `main` | `e08d7f46835d2dcffd76a77bf3e1fb423dc9c0ce` |
| Previously accepted implementation/runtime baseline | `8c458888657efca5384c6d51e5ec57e8b385d987` |
| Current documentation HEAD | See the repository's current `main` branch |
| Android package | `com.robotemi.agent` |
| Repository release candidate | `1.0.5` / `versionCode 6` |
| Historical physical Temi baseline before V4F | `1.0.4` / `versionCode 5` |
| Current accepted physical Temi | `1.0.5` / `versionCode 6` |
| Status date | `2026-08-31` |

The previously accepted implementation/runtime evidence remains tied to
`8c458888657efca5384c6d51e5ec57e8b385d987`. The V4E candidate starts from
`e08d7f46835d2dcffd76a77bf3e1fb423dc9c0ce`, retains the two authorized
project-owned exercise resources, and adds the central Temi top-safe-area
policy while advancing the repository artifact to versionCode 6 /
versionName 1.0.5. At the V4E stage, the candidate was not installed and the
physical Temi baseline remained versionCode 5 / versionName 1.0.4 until the
later V4F/V4H acceptance work. That paragraph records the historical V4E
pre-acceptance state; the later physical result is recorded below.

The literal current documentation-inclusive HEAD should be read from the
repository's current GitHub `main` branch, rather than hardcoded in this
current-status document. Any future Android source or runtime change requires
fresh re-acceptance before it can replace the accepted implementation/runtime
baseline.

## Verified build baseline

The current source and G3A acceptance record establish this build baseline:

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

A1 and later V4E/G3A evidence is **VERIFIED** at build level:

- `294/294` JVM tests passed on the current accepted source.
- The earlier A1 result of `288/288` is retained as historical evidence.
- `assembleDebug` passed.
- The signed `assembleDemo` build passed.
- Demo artifact preflight passed.
- A fresh-clone rebuild passed, including the public-source build path.

The public CI workflow covers JDK 21, JVM tests, and `assembleDebug`. It does
not provide Demo signing, Temi device, private backend, or full AI6 evidence.

## Historical Demo artifact contract

The earlier A1 accepted Demo variant had this packaged contract. It is
historical and does not describe the current installed physical version:

| Field | Value |
| --- | --- |
| Package | `com.robotemi.agent` |
| `versionCode` | `3` |
| `versionName` | `1.0.2` |
| Demo `debuggable` | `false` |
| Media v1.1 enabled | `true` |
| Media attach deadline | `10000 ms` |
| Expected Demo signer SHA-256 | `4DA8461B45B02FADCB042F63151FEE05D56EBD5105EB721D7D62E30B88513A7F` |

## Current accepted Android baseline

The current accepted Android baseline was introduced by V4E and physically
accepted by V4F/V4H. Its packaged contract is:

| Field | Value |
| --- | --- |
| Package | `com.robotemi.agent` |
| `versionCode` | `6` |
| `versionName` | `1.0.5` |
| Project-owned raw resources | `elderly_hand_exercise.mp4`, `elderly_leg_exercise.mp4` |
| Media v1.1 enabled | `true` in Demo |
| Media attach deadline | `10000 ms` in Demo |
| Temi top-safe-area policy | `max(0.09 * windowHeight, 72dp)`, then max with system/cutout insets |
| Installed APK SHA-256 | `0F386BE227ED964CA25507A15589E113259B15DDC7C9166B59B6B2640EAECEA4` |
| Expected Demo signer SHA-256 | `4DA8461B45B02FADCB042F63151FEE05D56EBD5105EB721D7D62E30B88513A7F` |
| Physical exercise playback | `VERIFIED_DEVICE` via V4H on installed versionCode 6 / versionName 1.0.5 |

The two exercise resources are `TRACKED_PROJECT_ASSET` files. The command
allowlist remains limited to `elderly_hand_exercise` and
`elderly_leg_exercise`; arbitrary media paths and URLs remain prohibited.

V4D identified a Temi-owned `SYSTEM_ALERT_WINDOW` from
`com.roboteam.teamy.usa` covering the physical region `[0,0][1920,98]`.
The region overlapped the exercise controls, while MainActivity and the
lower MQTT settings control continued to receive input. V4E adds the
Temi-specific top-safe-area calculation to `SystemUiSafeAreaPolicy` and
applies it to `appContent` top padding. The declarative layout and media
playback implementation remain unchanged.

At the V4E stage, this was source/build evidence only. V4F physically
confirmed the safe placement of the exercise controls, and V4H later
completed the physical hand and leg coordinate-tap playback acceptance.

## Historical V4E verification record

The V4E candidate was verified with the supported JDK 21 and Gradle 8.13
wrapper before physical acceptance:

- `:app:testDebugUnitTest`: `294/294` passed, including six new top-policy
  tests.
- `:app:assembleDebug`: passed.
- `:app:assembleDemo`: passed, including the authorized signer check.
- Demo artifact preflight: passed for `com.robotemi.agent`, versionCode 6 /
  versionName 1.0.5, `debuggable=false`, and Media v1.1 enabled.
- Demo APK SHA-256: `759B06EB35B876C60FDC11BD58B16915C59F9E6E6B03492F4F1256490CABB252`.

The signer digest is a public artifact-verification identity; the private
signing inputs remain out of band. An APK hash identifies one concrete built
artifact and is not a permanent source property. Source HEAD, version,
signer identity, and packaged policy establish the recorded provenance. This
repository does not currently claim bit-for-bit reproducible APK builds.

## V4H physical acceptance closure

The installed accepted physical candidate is `com.robotemi.agent`, versionCode
`6`, versionName `1.0.5`. Physical acceptance requires `MainActivity` to be
resumed and its window focused. After one MainActivity launch, a bounded
foreground monitor produced `47` samples across at least `27584 ms`;
`MainActivity` remained foreground and no autonomous `StandbyActivity`
takeover was observed.

The accepted physical provenance is:

| Field | Accepted value |
| --- | --- |
| Package | `com.robotemi.agent` |
| Version | `versionCode 6` / `versionName 1.0.5` |
| Installed APK SHA-256 | `0F386BE227ED964CA25507A15589E113259B15DDC7C9166B59B6B2640EAECEA4` |
| Demo signer SHA-256 | `4DA8461B45B02FADCB042F63151FEE05D56EBD5105EB721D7D62E30B88513A7F` |
| Accepted forward upgrade | `versionCode 5 / 1.0.4 -> versionCode 6 / 1.0.5` |
| Upgrade operation | One normal `adb install -r`; `userId`, `dataDir`, `firstInstallTime`, and signer were preserved. |

With that foreground precondition satisfied, the following physical checks
passed:

| Check | Result |
| --- | --- |
| MQTT coordinate control | `PASS`; one coordinate tap opened the MQTT settings control and a center tap closed it, with `MainActivity` remaining resumed/focused. |
| Hand coordinate tap and playback | `PASS`; one tap at `[874,156]` on HAND `[793,120][955,192]`; the hand video was visible, advancing, and completed naturally. |
| Leg coordinate tap and playback | `PASS`; one tap at `[1036,156]` on LEG `[955,120][1117,192]`; the leg video was visible, advancing, and completed naturally. |

V4F's physical top-safe-area result is
`TOP_SAFE_AREA_PHYSICAL_ACCEPTANCE = PASS`: the accepted HAND and LEG bounds
are outside the historical Temi-owned top region `[0,0][1920,98]`. The
implemented policy remains `max(0.09 * windowHeight, 72dp)`, combined with
the system-bar and display-cutout inset.

`StandbyActivity` can be the foreground owner on Temi, so diagnosis and
operator readiness must check the resumed activity, top activity, and focused
window before coordinate acceptance. A prior V4G readiness snapshot found
`StandbyActivity` resumed/focused while `MainActivity` was paused/hidden. V4H
did not observe an autonomous takeover after its launch during the bounded
monitor; this record does not claim that `StandbyActivity` always
automatically takes foreground.

Final defect classification:

- `ANDROID_MEDIA_SOURCE_BUG = NO`
- `ANDROID_TOUCH_SOURCE_BUG = NO`
- `ANDROID_SOURCE_FIX_REQUIRED = NO`

This is bounded physical local UI/media evidence. It does not promote the
external AI6, broker, or full-stack `play_media` path to `VERIFIED_E2E`.

## V4E physical baseline (historical)

At the start of V4E, the task-provided physical Temi baseline was
`com.robotemi.agent`, versionCode 5 / versionName 1.0.4. V4E performs no APK
installation, uninstall, data clear, reboot, runtime media test, MQTT change,
or AI6 restart. At that stage, physical acceptance had not yet run. This is the
pre-acceptance record; it is superseded by the V4H result above without
rewriting the historical observation.

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
following claims remain **NOT_VERIFIED** by this baseline: full Android/AI6
compatibility, final full-stack AI6 acceptance, a final canonical `noop` round
trip, care end-to-end behavior, resident-identity end-to-end behavior, and
physical completion of all command actions.

## Bounded Android-to-AI6 evidence

The following owner-provided evidence is recorded at bounded integration
granularity. It is not a claim that every Android command or robot action is
end-to-end accepted:

- AI6 canonical root: `/home/yiting/TemiAgent` (external owner path).
- AI6 branch: `main`.
- AI6 accepted HEAD: `12aff3bfdfe526c17a25a2681aea2afad7112b33`.
- `scripts/demo start`: `PASS`.
- `scripts/demo status`: `DEMO_READY`.
- `scripts/demo doctor`: `PASS`.
- Accepted bounded path: Android MQTT connectivity, WebSocket client
  creation/connection, Temi-to-AI6 WebSocket connectivity, camera frame input,
  H.264 output, WebSocket binary send, AI6 H.264 ingress, non-empty
  `VisionBuffer`, and viewer frames.

`FULL_ANDROID_AI6_COMPATIBILITY = NOT_VERIFIED`. Complete command-by-command
acceptance, physical completion of every robot action, Resident Identity E2E,
Care Report E2E, disabled-feature compatibility, and universal deployment
compatibility remain outside the accepted bounded evidence.

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

## Known handover gaps and owner decisions

The following items remain open decisions for the project owner:

- Full Android/AI6 acceptance scope is not defined beyond the bounded evidence.
- No Git tags or releases exist; release/tag policy is not finalized.
- No `LICENSE` file exists; the repository license decision is open.
- GitHub `main` branch protection is currently disabled.
- Signing successor custody, backup/recovery, and accepted APK archive ownership
  remain out of band.
- Resident Identity, Care Report, and legacy global MQTT actions remain disabled
  unless the owner approves a compatible build and acceptance scope.

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
