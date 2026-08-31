# TemiAgent Android App

## Overview

This repository contains the Android application running on the Temi robot.

Backend AI-agent services, bridge services, local model inference, and
care-memory runtime are maintained separately and are not included in this
repository.

TemiAgent is the device-side application. It integrates with the Temi SDK,
owns the Android lifecycle around robot interaction, and exchanges commands,
events, and camera data with externally deployed services through defined
interfaces.

## Features

- Temi SDK integration and robot-side Android lifecycle.
- Voice interaction using Android SpeechRecognizer, Temi ASR handoff, and TTS.
- MQTT command and event messaging with reconnect, validation, idempotency, and
  durable result delivery.
- Source-supported Resident Identity and Care Report paths; both are disabled
  in the accepted Demo baseline.
- Callback-grounded playback of the two tracked project-owned exercise videos.
- CameraX YUV capture, H.264 encoding, and WebSocket video delivery.
- Safe handling for invalid, duplicate, cancelled, or interrupted commands.

## Architecture boundary

    Temi Android App
            |
            | MQTT / WebSocket / defined external interfaces
            v
    External backend services

The external services are interface dependencies only. Final Android/AI6
compatibility is not established by this repository. Owner-provided bounded
Android-to-AI6 evidence is recorded in the handover register; a broader
command-by-command acceptance scope remains an owner decision. The services
are deployed and configured separately from this Android project.

## Requirements

- Android SDK Platform 34.
- JDK 21, the verified and recommended Gradle runtime for this project.
- The included Gradle Wrapper, using Gradle 8.13 and Android Gradle Plugin
  8.13.2.
- Network access for the Temi SDK and other Maven dependencies.
- A Temi robot for device runtime validation.

The project keeps Java/Kotlin source and target compatibility at Java 8. JDK 21
is the verified build runtime for local and CI execution. The audit-host JDK 25
build failed, so JDK 25 is outside the verified compatibility baseline.

## Configuration

After cloning, run commands from the cloned Android project root. A first-day
public checkout is:

    git clone https://github.com/YI-TING-EE13/temi-agent-android.git
    cd temi-agent-android

Then create the ignored local configuration:

1. Copy local.properties.example to local.properties.
2. Set sdk.dir to the Android SDK directory on the current machine.
3. Leave ws.server.urls unset for a normal public build. Set it only when an
   authorized deployment provides WebSocket endpoints; the template contains no
   active endpoint.
4. Leave resident.identity.enabled and care.report.enabled disabled unless the
   corresponding external contracts are available.
5. Leave legacy.mqtt.actions.enabled=false unless a controlled LAB deployment
   explicitly requires the three global legacy action topics. Missing or
   unrecognized values remain disabled.
6. Configure the MQTT host, port, robot ID, and any private broker values
   through the app's runtime settings. Do not put them in tracked files.

local.properties is ignored and machine-local. It must not be committed or
copied into a public repository.

## Local Data and Backup

MQTT endpoint settings and durable command/care delivery state are device-local.
Android cloud backup/restore and device-to-device transfer are intentionally
disabled and excluded. Operators must configure a new robot/device explicitly.
This policy does not claim that local data is encrypted at rest.

### Signing

Normal debug builds use the standard Android debug signing configuration. The
demo variant uses the explicit demoSigning configuration. To build it, copy
signing.local.properties.example to signing.local.properties and fill the four
values using a local keystore stored outside this project.

Normal debug builds remain debuggable for development. Signed Demo artifacts are
explicitly non-debuggable, and tools/verify_demo_artifact.ps1 verifies this
property from the packaged APK. See
[BUILD_AND_TEST.md](docs/operations/BUILD_AND_TEST.md) for the Demo build and
preflight path and [SIGNING_HANDOVER.md](docs/operations/SIGNING_HANDOVER.md)
for signing custody.

The populated signing file, passwords, aliases, and keystore are local-only.
Never commit them or print them in logs or documentation.

## Project-owned Exercise Media

The project-produced exercise videos are part of this public repository as
tracked Android raw resources:

    app/src/main/res/raw/elderly_hand_exercise.mp4
    app/src/main/res/raw/elderly_leg_exercise.mp4

Fresh clones contain both files, and the fixed media IDs remain
`elderly_hand_exercise` and `elderly_leg_exercise`. The resolver retains
defensive missing-resource handling: if a generated APK lacks a resource, the
request reports `media_unavailable:<media_id>` instead of crashing. Physical
Temi coordinate-tap playback is `VERIFIED_DEVICE` for the installed 1.0.5/code6
candidate: V4H recorded visible, advancing, naturally completed hand and leg
videos. Physical acceptance requires `MainActivity` to be resumed and its
window focused; `StandbyActivity` can be the foreground owner and should be
checked during diagnosis and operator readiness.

### Demo build

Demo is the canonical signed acceptance/deployment variant. The debug APK is
for development only and is not the accepted Temi deployment artifact.

## Build

Windows, from the Android project root:

    .\gradlew.bat :app:assembleDebug --no-daemon --console=plain --max-workers=1

The shorter equivalent is:

    .\gradlew.bat :app:assembleDebug

macOS/Linux:

    ./gradlew :app:assembleDebug --no-daemon --console=plain --max-workers=1

Optional local checks:

    .\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain --max-workers=1
    .\gradlew.bat :app:lintDebug --no-daemon --console=plain --max-workers=1

The public GitHub Actions workflow runs the unit-test and debug-build tasks on
Ubuntu with JDK 21. The workflow does not require signing material, a Temi
robot, or private services; the tracked exercise resources are included by the
normal checkout and debug build.

The debug APK is written to:

    app/build/outputs/apk/debug/app-debug.apk

`app-debug.apk` = DEVELOPMENT ONLY. Use the signed Demo artifact for Temi
acceptance or deployment.

tools/verify_demo_artifact.ps1 validates a clean, signed Demo artifact. Run it
with RepoRoot set to this project root. Its optional RequiredAncestor argument
is only for a release process that intentionally requires a known history
baseline.

## Install and run

Device validation requires a Temi robot and an endpoint owned by the operator.
The canonical accepted deployment uses the signed, non-debuggable Demo
variant. Follow [ADB_AND_INSTALL.md](docs/operations/ADB_AND_INSTALL.md) for
serial-scoped inspection, forward upgrade, launch, and post-install checks.
That runbook uses placeholders and keeps device acceptance separate from
desktop build and unit-test evidence.

## Repository scope

Included:

- The Temi Android application, its tests, and source resources.
- The Android Gradle build, wrapper, configuration templates, verification
  tools, and engineering documentation.

Not included:

- Backend AI services.
- Bridge services and Hermes Agent runtime.
- LM Studio or other local model inference runtime.
- Care-memory services.
- Backend credentials, local signing material, local SDK paths, and generated
  APK/AAB/build outputs.

External Hermes/Bridge reference services and backend services are outside this
repository and are not published here.

## Documentation map

- [Current status](docs/CURRENT_STATUS.md): accepted evidence, build/Demo
  contract, current gaps, and evidence vocabulary.
- [Repository map](docs/REPOSITORY_MAP.md): source, test, configuration,
  documentation, and external-dependency map.
- [Verified features](docs/VERIFIED_FEATURES.md): evidence-level feature
  matrix.
- [Android architecture](docs/architecture/ANDROID_ARCHITECTURE.md): current
  component ownership and lifecycle boundaries.
- [Temi SDK usage map](docs/architecture/TEMI_SDK_USAGE_MAP.md): SDK types,
  calls, callbacks, and physical-side-effect boundaries.
- [MQTT interface](docs/contracts/MQTT_INTERFACE.md): Android topic,
  connection, ingress, and publication contract.
- [Command contract](docs/contracts/COMMAND_CONTRACT.md): canonical command
  and result schema, validation, and recovery behavior.
- [Configuration contract](docs/contracts/CONFIGURATION_CONTRACT.md): tracked,
  local, device, private, and external configuration ownership.
- [Build and test](docs/operations/BUILD_AND_TEST.md): supported toolchain,
  tests, variants, signing gate, and artifact preflight.
- [ADB and install](docs/operations/ADB_AND_INSTALL.md): safe inspection,
  forward upgrade, launch, and targeted restart.
- [Troubleshooting](docs/operations/TROUBLESHOOTING.md): failure-first
  diagnosis and recovery boundaries.
- [Release checklist](docs/operations/RELEASE_CHECKLIST.md): candidate and
  publication gate.
- [Signing handover](docs/operations/SIGNING_HANDOVER.md): private signing
  custody and public verification identity.
- [Handover readiness](docs/handover/HANDOVER_READINESS.md): current-state
  register, ownership boundaries, prerequisites, and open decisions.
- [Junior handover](docs/handover/JUNIOR_HANDOVER.md): maintainer landing page
  and reading order.
- [Publication boundary](docs/publication-boundary.md): public-set
  classification and exclusions.
- [Historical performance note](docs/performance/yuv-copy-optimization-2026-08-09.md):
  HISTORICAL YUV-copy evidence.
- [.github/workflows/android-ci.yml](.github/workflows/android-ci.yml): public
  JDK 21 test and debug-build CI.
- [tools/](tools/): local build and artifact verification helpers.

## Known limitations

- A Temi robot and the external backend interfaces are required for full
  runtime behavior; a desktop build is not device acceptance.
- Android SpeechRecognizer is a pragmatic wake-word implementation and is less
  reliable for very short phrases than a dedicated keyword spotter.
- Media playback accepts only the allowlisted exercise identifiers; arbitrary
  URLs, filesystem paths, and content URIs are rejected.
- The two project-owned exercise videos are packaged in the V4E 1.0.5/code6
  release candidate, and V4F/V4H accepted the top-safe-area placement plus
  hand/leg coordinate-tap playback on the installed candidate. The full
  canonical broker/AI6/Temi playback path remains outside that bounded local
  `VERIFIED_DEVICE` evidence.
- Robot navigation arrival and physical turn completion are not observed by the
  Android result contract.
- Camera streaming depends on a configured external WebSocket service.
- Resident Identity and Care Report source paths are opt-in; both are disabled
  in the accepted Demo baseline and lack current external/device E2E
  acceptance.
- Owner-provided bounded Android-to-AI6 evidence covers the documented
  connectivity and camera-stream path. Full Android/AI6 compatibility and
  command-by-command acceptance remain `NOT_VERIFIED`; the broader scope is an
  owner decision.
- The target SDK remains 30 and Java/Kotlin source compatibility remains Java 8;
  this cleanup does not modernize the toolchain.

## Source provenance

The Android source, tests, and source resources are part of the TemiAgent
application. The exercise videos are tracked project-owned resources and are
published with the Android application. The Temi SDK is consumed as the
external Maven dependency
com.robotemi:sdk:1.134.1; it is not vendored here.

Review upstream and third-party license obligations before publishing a new
repository. No upstream SDK history or unrelated SDK samples are required by
the Android Gradle build.

## Temi SDK attribution

This project integrates with the temi SDK provided by temi USA Inc. The temi
SDK is distributed separately under its applicable license:

https://github.com/robotemi/sdk
