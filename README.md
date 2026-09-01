# TemiAgent Android

[![Android](https://img.shields.io/badge/Android-API%2034-3DDC84?logo=android&logoColor=white)](https://developer.android.com/studio/releases/platforms#34)
[![Gradle](https://img.shields.io/badge/Gradle-8.13-02303A?logo=gradle&logoColor=white)](https://docs.gradle.org/8.13/userguide/gradle_wrapper.html)
[![JDK](https://img.shields.io/badge/JDK-21-437291?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/21/)
[![Temi SDK](https://img.shields.io/badge/Temi%20SDK-1.134.1-555555)](https://github.com/robotemi/sdk)
[![Android CI](https://github.com/YI-TING-EE13/temi-agent-android/actions/workflows/android-ci.yml/badge.svg)](https://github.com/YI-TING-EE13/temi-agent-android/actions/workflows/android-ci.yml)

Android application running on the Temi robot. The standalone repository owns
the device-side Android application, its tests, build configuration, and
public engineering documentation.

## Overview

TemiAgent integrates the Temi SDK with an Android UI and Activity lifecycle. It
validates and dispatches bounded local commands, maintains the Android-side
MQTT client lifecycle, optionally captures and streams camera video over
WebSocket, and plays the tracked project-owned exercise media.

The repository does not contain or operate the AI6 backend, Hermes, Bridge, LM
Studio, the MQTT broker, or other external backend services. Android reaches
those systems only through deployment-specific MQTT and WebSocket interfaces.
The current Android baseline is `versionName 1.0.5` and `versionCode 6`.

## Key Features

- **Temi integration**: Uses `com.robotemi:sdk:1.134.1` for Temi callbacks,
  interaction lifecycle, voice handoff, and robot-side dispatch.
- **Android interaction and voice**: `MainActivity` owns the screen, Activity
  lifecycle, Android SpeechRecognizer handoff, and callback-grounded TTS and
  media paths.
- **Command validation and delivery**: Validates canonical requests and
  allowlisted actions, suppresses conflicting duplicates, and retains bounded
  local result state for retry and publication.
- **MQTT client lifecycle**: `MqttLifecycleService` owns one Android-side MQTT
  client lifecycle with endpoint validation, reconnect handling, bounded
  ingress, and result publication. The broker remains external.
- **Project-owned exercise media**: Keeps
  `elderly_hand_exercise.mp4` and `elderly_leg_exercise.mp4` as tracked raw
  resources with fixed allowlisted media IDs.
- **Camera and WebSocket streaming**: Uses CameraX for YUV capture and H.264
  encoding, then sends video through an optional deployment-specific
  WebSocket client.
- **Optional identity and care paths**: Resident Identity and Care Report
  source and JVM-test paths are present but disabled in the accepted Demo
  baseline.

## Architecture

```text
TemiAgent Android
  ├── UI / Activity lifecycle (`MainActivity`)
  ├── Temi SDK integration and voice handoff
  ├── command validation, ledger, and callback tracking
  ├── MQTT lifecycle client and canonical ingress
  ├── CameraX capture and H.264 encoding
  ├── optional WebSocket streaming
  └── local project-owned exercise media
          │
          └── MQTT / WebSocket interfaces
                    │
                    └── external broker, AI6, and backend services
```

| Android area | Responsibility |
| --- | --- |
| `MainActivity` | UI, Temi callbacks, voice handoff, camera/WebSocket wiring, and Activity-side action dispatch. |
| `mqtt/` and `MqttLifecycleService` | Device-side endpoint selection, broker client lifecycle, reconnect policy, ingress validation, and result publication. |
| `command/` | Canonical command validation, durable ledger, recovery policy, TTS tracking, and media tracking. |
| `camera/` and `network/` | CameraX/YUV/H.264 capture and the external WebSocket client lifecycle. |
| `media/v11/` | Media v1.1 parsing, coordination, playback callbacks, persistence, migration, and result state. |
| `identity/` and `care/report/` | Optional Resident Identity and Care Report source paths. |

See [Android architecture](docs/architecture/ANDROID_ARCHITECTURE.md), the
[Temi SDK usage map](docs/architecture/TEMI_SDK_USAGE_MAP.md), and the
[repository map](docs/REPOSITORY_MAP.md) for source-derived boundaries.

## Installation

### Prerequisites

| Requirement | Current value | Scope |
| --- | --- | --- |
| Android SDK | Platform 34 | Local build and test environment. |
| Build runtime | JDK 21 | Verified and recommended Gradle runtime. |
| Gradle | Checked-in Wrapper, Gradle 8.13 | Use the repository wrapper rather than a system Gradle installation. |
| Network | Public Maven repository access | Required for dependency resolution. |
| Temi robot | Required only for physical acceptance | Not required for first-day source, JVM-test, or debug-build work. |

### Clone and Local Setup

From a development workstation:

```powershell
git clone https://github.com/YI-TING-EE13/temi-agent-android.git
cd temi-agent-android
Copy-Item .\local.properties.example .\local.properties
```

Linux and macOS maintainers can use:

```text
git clone https://github.com/YI-TING-EE13/temi-agent-android.git
cd temi-agent-android
cp local.properties.example local.properties
```

Edit the ignored `local.properties` and set the Android SDK path for the
current machine:

```text
sdk.dir=<ANDROID_SDK_PATH>
```

Leave `ws.server.urls` unset for normal public development. The template also
keeps `resident.identity.enabled`, `care.report.enabled`, and
`legacy.mqtt.actions.enabled` disabled. A first-day public workspace does not
require signing assets, a private WebSocket endpoint, an MQTT endpoint, an AI6
host, or a live Temi.

## Build and Test

Run from the repository root with JDK 21:

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain --max-workers=1
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain --max-workers=1
```

Linux and macOS maintainers can use `./gradlew` with the same task arguments.
The public [Android CI workflow](.github/workflows/android-ci.yml) runs the
JVM test and debug-build tasks on Ubuntu with JDK 21.

The debug APK is written to
`app/build/outputs/apk/debug/app-debug.apk`. A debug APK is for development
only; it is not the accepted Temi deployment artifact.

Authorized Demo builds use `:app:assembleDemo` and the explicit signing gate
described in [Build and Test](docs/operations/BUILD_AND_TEST.md). Do not add
private signing material to the public workspace to make a Demo build pass.

## Configuration

| Configuration | Owner or location | Use and boundary |
| --- | --- | --- |
| `sdk.dir` | Ignored `local.properties` | Local Android SDK path; never commit the populated file. |
| `ws.server.urls` | Ignored `local.properties` | Optional deployment-specific WebSocket endpoints; leave unset for public development. |
| `resident.identity.enabled` | Ignored `local.properties` | Optional source path; remains `false` in the accepted Demo baseline until its external contract is available. |
| `care.report.enabled` | Ignored `local.properties` | Optional source path; remains `false` in the accepted Demo baseline until its external contract is available. |
| `legacy.mqtt.actions.enabled` | Ignored `local.properties` | Disabled by default; enable only for a controlled deployment that still requires legacy global action topics. |
| MQTT broker host, port, and robot ID | App-private runtime settings | Deployment-specific device configuration; not stored in tracked files or the APK. |
| `demo.signing.*` | Ignored `signing.local.properties` | Private Demo signing inputs; use the authorized signing procedure and an external keystore. |

Do not publish real WebSocket or MQTT endpoints, Temi serials, SDK paths,
keystore paths, aliases, passwords, credentials, or personal machine paths.
See the [configuration contract](docs/contracts/CONFIGURATION_CONTRACT.md) for
ownership and precedence.

## Development Usage

Use the debug variant, JVM tests, and public configuration template for normal
development. Local builds exercise Android source and contracts without
requiring the external AI6, Hermes, Bridge, LM Studio, MQTT broker, or a
physical Temi.

Configure MQTT or WebSocket values only for an authorized deployment. The
Android repository does not define external backend startup commands or
credentials. Use the relevant external owner and the Android
[troubleshooting guide](docs/operations/TROUBLESHOOTING.md) when a boundary
needs diagnosis.

## Project Structure

```text
app/
├── src/main/java/com/robotemi/agent/
│   ├── MainActivity.java
│   ├── agent/
│   ├── command/
│   ├── mqtt/
│   ├── camera/
│   ├── network/
│   ├── media/v11/
│   ├── identity/
│   └── care/report/
├── src/main/res/
│   └── raw/
└── src/test/
docs/
├── architecture/
├── contracts/
├── handover/
└── operations/
gradle/
tools/
└── verify_demo_artifact.ps1
.github/workflows/android-ci.yml
```

`app/src/test/` contains JVM contract, lifecycle, parser, media, MQTT,
camera, and WebSocket tests. Build outputs, `.gradle/`, `local.properties`,
private signing files, and APKs are not project source.

## Demo Signing and Device Acceptance

The `demo` build type is the signed, non-debuggable acceptance/deployment
variant. Demo signing is explicit and fail-closed; the four signing inputs are
read only from the ignored `signing.local.properties` file and the keystore
must remain outside the repository. Debug signing is for development only.

Physical Temi work is a separate authorization and evidence gate. Follow
[Build and Test](docs/operations/BUILD_AND_TEST.md),
[Signing Handover](docs/operations/SIGNING_HANDOVER.md), and
[ADB and Install](docs/operations/ADB_AND_INSTALL.md) before any installation
or device check.

Activity-owned media, camera, and streaming checks require `MainActivity` to
be resumed and its window focused. `StandbyActivity` can be the foreground
owner, so operators must check the resumed activity, top activity, and focused
window during readiness and diagnosis. The repository does not claim that
`StandbyActivity` always automatically takes foreground.

## Academic-Lab Development Workflow

Read the full [academic-lab development workflow](docs/handover/DEVELOPMENT_WORKFLOW.md)
for change classes, evidence levels, cross-repository boundaries, and
escalation rules. The practical handoff is:

```text
PROJECT-01 direction
  → GitHub Issue / work definition
  → change classification
  → branch
  → ANDROID-01 / implementation owner develops
  → local verification
  → Android CI
  → push completed branch
  → PROJECT-01 creates/manages PR
  → review
  → merge decision
  → Demo/device acceptance when required
```

For the current lab operation:

```text
Codex / implementation owner:
  branch → code/docs → tests → commit → push

PROJECT-01:
  PR creation/management → review → acceptance → merge
```

Codex and the implementation owner are not the PR owner. `PROJECT-01` sets
research direction and manages the review/merge boundary; `ANDROID-01` owns
routine Android implementation and validation.

## Requirements and Limitations

- Desktop JVM tests and a debug build are source/unit/build evidence, not
  physical Temi acceptance.
- Android-only evidence does not prove external AI6, broker, Hermes, Bridge,
  LM Studio, or complete Android-to-backend behavior.
- MQTT and WebSocket endpoints are deployment-specific. The external broker,
  AI6 runtime, and backend services are outside this repository.
- Activity-owned physical checks require resumed and focused `MainActivity`.
  Navigation and turn results report dispatch, not physical arrival or turn
  completion.
- Resident Identity, Care Report, and legacy global MQTT actions remain
  disabled in the accepted Demo baseline unless a separate compatibility and
  acceptance decision enables them.
- The project uses target SDK 30 and Java 8 source/target compatibility.
  Independent builds are not claimed to produce bit-for-bit identical APKs.
- Physical operations require an authorized signed Demo, an owned device
  target, and the dedicated installation and acceptance runbooks.
- This project makes no medical, emergency, or safety guarantee.

## Evidence and Verified Features

The repository separates evidence levels:

| Level | Meaning |
| --- | --- |
| `SOURCE` | Current source, configuration, or documentation expresses the stated boundary. |
| `UNIT` | Focused local or JVM tests cover the stated behavior. |
| `BUILD` | A Gradle task or packaged-artifact check passed. |
| `DEVICE` | A narrow behavior was observed on an authorized physical Temi or Android target. |
| `E2E` | A complete bounded producer, consumer, external-runtime, and device path was exercised. |

Current evidence is summarized in the [verified feature matrix](docs/VERIFIED_FEATURES.md),
the [current-status record](docs/CURRENT_STATUS.md), and the [handover
readiness register](docs/handover/HANDOVER_READINESS.md). The current records
include:

- Project-owned hand and leg exercise media and the Temi top-safe-area policy
  at `VERIFIED_DEVICE` for the bounded accepted physical behavior.
- Bounded owner-provided camera/WebSocket stream evidence at `VERIFIED_E2E`;
  the result is deployment-specific and is not universal Android/AI6
  compatibility.
- Full Android/AI6 command-by-command compatibility as `NOT_VERIFIED`.
- Resident Identity and Care Report as `DISABLED` in the accepted Demo.

## Future Work

Possible future work, not part of the current accepted baseline, includes:

- Defining and accepting a broader Android/AI6 command and device scope.
- Completing owner decisions for release tags, licensing, branch protection,
  signing succession, and accepted-artifact archiving.
- Modernizing the target SDK and Java source compatibility.
- Expanding physical completion evidence for robot actions and optional
  features.

## Contributing

Start with [JUNIOR_HANDOVER.md](docs/handover/JUNIOR_HANDOVER.md),
[DEVELOPMENT_WORKFLOW.md](docs/handover/DEVELOPMENT_WORKFLOW.md), and
[AGENTS.md](AGENTS.md). The short path is:

```text
Issue / task → branch → implementation → validation → commit/push
  → PROJECT-01-managed PR and merge decision
```

Keep each change focused, record its evidence and limitations, and do not add
Scrum ceremonies or multiple approval layers for simple repository-local work.

## License

No project `LICENSE` file currently exists. License status is
`NO_LICENSE / DEFERRED_BY_OWNER`; do not infer or add a license as part of an
Android change.

## Acknowledgements

This project uses the Temi SDK, distributed separately by temi USA Inc. See
the [Temi SDK repository](https://github.com/robotemi/sdk) for upstream
provenance and applicable terms. This acknowledgement does not imply
endorsement.
