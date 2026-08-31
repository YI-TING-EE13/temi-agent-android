# Repository Map

This map describes the **CURRENT** standalone Android repository at the
documentation-inclusive GitHub `main` recorded in
[CURRENT_STATUS.md](./CURRENT_STATUS.md). The accepted implementation/runtime
baseline is recorded separately in that current-status document.
Classifications are deliberately limited to the task vocabulary: `CURRENT_SOURCE`,
`CURRENT_TEST`, `CURRENT_CONFIG`, `CURRENT_TOOL`, `CURRENT_DOC`,
`HISTORICAL_DOC`, `TRACKED_PROJECT_ASSET`, `GENERATED_NOT_TRACKED`, `PRIVATE_NOT_TRACKED`, and
`EXTERNAL_DEPENDENCY`.

## Repository boundary

| Path or boundary item | Classification | Responsibility or interpretation |
| --- | --- | --- |
| `/` | `CURRENT_CONFIG` | Standalone Android project root. |
| `README.md` | `CURRENT_DOC` | Public setup, build, runtime boundary, and limitation guide. |
| `AGENTS.md` | `CURRENT_DOC` | Generalized developer handoff and safety invariants. |
| `app/` | `CURRENT_SOURCE` | Android application module, resources, and JVM tests. |
| `app/src/main/` | `CURRENT_SOURCE` | Packaged Android source and resources. |
| `app/src/test/` | `CURRENT_TEST` | Current JVM contract, lifecycle, parser, and policy tests. |
| `gradle/` | `CURRENT_CONFIG` | Gradle wrapper properties and wrapper JAR. |
| `.github/workflows/` | `CURRENT_CONFIG` | Public JDK 21 test and debug-build workflow. |
| `docs/` | `CURRENT_DOC` | Public documentation, including this current-truth set. |
| `tools/` | `CURRENT_TOOL` | Demo artifact verification and YUV benchmark helpers. |
| `build.gradle` | `CURRENT_CONFIG` | Root plugin and repository configuration. |
| `app/build.gradle` | `CURRENT_CONFIG` | Android application, variants, build fields, signing gate, and dependencies. |
| `settings.gradle` | `CURRENT_CONFIG` | Root project name and `app` module inclusion. |
| `gradle.properties` | `CURRENT_CONFIG` | Root Gradle properties. |
| `gradlew`, `gradlew.bat` | `CURRENT_CONFIG` | Version-controlled Gradle wrapper launchers. |
| `local.properties.example` | `CURRENT_CONFIG` | Non-secret local SDK/WebSocket configuration template; the copied first-day default leaves WebSocket endpoints unset. |
| `signing.local.properties.example` | `CURRENT_CONFIG` | Placeholder-only Demo signing input template. |
| `.gitignore` | `CURRENT_CONFIG` | Excludes machine-local configuration, signing material, and generated outputs; project-owned exercise media remains tracked. |

The current tree contains no source directory for an AI6 backend, Hermes Agent,
Bridge, LM Studio, or an MQTT broker implementation. Those systems are
`EXTERNAL_DEPENDENCY` boundaries. The Android `mqtt/` package contains the
device-side client, lifecycle owner, topic policy, and connection abstraction;
it is not a broker implementation.

## Android source tree

The following package tree is derived from the current files under
`app/src/main/java/com/robotemi/agent/`.

| Current package or entry point | Classification | Responsibility and representative current files |
| --- | --- | --- |
| [`MainActivity.java`](../app/src/main/java/com/robotemi/agent/MainActivity.java) | `CURRENT_SOURCE` | Temi callbacks, UI, voice handoff, camera/WebSocket wiring, observer-side MQTT routing, and canonical action dispatch. |
| `SystemUiSafeAreaPolicy.java` | `CURRENT_SOURCE` | Full-screen and safe-area policy. |
| `agent/` | `CURRENT_SOURCE` | Interaction state machine; currently [`AgentStateMachine.java`](../app/src/main/java/com/robotemi/agent/agent/AgentStateMachine.java). |
| `command/` | `CURRENT_SOURCE` | Canonical command validation/runtime, durable ledger, recovery policy, TTS tracking, and media tracking: `CanonicalCommandValidator.java`, `CanonicalCommandRuntime.java`, `CommandLedger.java`, `CommandRecoveryPolicy.java`, `SharedPreferencesCommandLedger.java`, `CanonicalTtsTracker.java`, and `CanonicalMediaTracker.java`. |
| `mqtt/` | `CURRENT_SOURCE` | Endpoint selection/settings, one active broker owner, Paho connection, reconnect policy, topic set/policy, bounded ingress, diagnostics, service lifecycle, and canonical ingress: `MqttLifecycleService.java`, `SingleActiveMqttBroker.java`, `MqttManager.java`, `MqttConnection.java`, `MqttReconnectPolicy.java`, `MqttTopicSet.java`, `MqttIngressPolicy.java`, `MqttIngressLimits.java`, `CanonicalCommandIngress.java`, `CanonicalCommandDiagnostics.java`, `MqttEndpoint.java`, `MqttEndpointSelection.java`, `MqttEndpointSwitchPolicy.java`, `MqttClientIdentity.java`, `CanonicalSpeechPort.java`, `SharedPreferencesMqttRuntimeSettings.java`, `MqttTopics.java`, and `InboundMqttLogSummary.java`. |
| `camera/` | `CURRENT_SOURCE` | CameraX ownership, YUV 4:2:0 copying, and H.264 encoding: `CameraManager.java`, `Yuv420PlaneCopier.java`, and `H264Encoder.java`. |
| `network/` | `CURRENT_SOURCE` | External WebSocket client and reconnect lifecycle: `WebSocketClient.java`. |
| `media/v11/` | `CURRENT_SOURCE` | Strict Media v1.1 parser, coordinator, service runtime, playback binding/controller, persistence, migration, result model, state machine, and optional resource resolver. |
| `identity/` | `CURRENT_SOURCE` | Resident Identity parsing, state, TTL/lifecycle, gating, and UI mapping. |
| `care/report/` | `CURRENT_SOURCE` | Care Report parsing, state, routing, presentation, interaction persistence, and UI binding. |
| `app/src/main/res/` | `CURRENT_SOURCE` | Current packaged layout, values, drawable, backup/data-extraction XML, and tracked exercise video resources. |

The source tree contains the canonical action allowlist (`speak`,
`ask_clarification`, `turn`, `navigate`, `stop`, `noop`, and `play_media`) and
the separate strict Media v1.1 protocol. Their source presence is not by
itself device or end-to-end acceptance.

## Test tree

`app/src/test/` is the current JVM test source set. It contains these current
packages and fixture groups:

| Current test area | Classification | Evidence focus |
| --- | --- | --- |
| `com.robotemi.agent` | `CURRENT_TEST` | Launcher manifest and system UI contracts. |
| `com.robotemi.agent.command` | `CURRENT_TEST` | Canonical validation, command ledger, recovery, TTS, and media tracking. |
| `com.robotemi.agent.mqtt` | `CURRENT_TEST` | Topic policy, retained/disabled ingress, payload and buffer bounds, service lifecycle, broker ownership, and reconnect behavior. |
| `com.robotemi.agent.camera` | `CURRENT_TEST` | YUV plane copy correctness and bounds. |
| `com.robotemi.agent.network` | `CURRENT_TEST` | WebSocket lifecycle and reconnect ownership. |
| `com.robotemi.agent.media.v11` | `CURRENT_TEST` | Media parser, state machine, coordinator, playback callbacks, migration, result conformance, and thread safety. |
| `com.robotemi.agent.identity` | `CURRENT_TEST` | Identity parsing, ordering, TTL, gate, and lifecycle behavior. |
| `com.robotemi.agent.care.report` | `CURRENT_TEST` | Care Report parsing, authorization, routing, presentation, interaction, persistence, and fixtures. |
| `app/src/test/resources/` | `CURRENT_TEST` | Resident Identity and Care Report JSON fixtures. |

The current accepted result is `294/294` JVM tests passed. That is
**VERIFIED_UNIT** evidence, not a physical Temi or full-stack AI6 result. The
earlier A1 result of `288/288` remains historical evidence only.

## Documentation and tools

| Current item | Classification | Boundary |
| --- | --- | --- |
| [`docs/CURRENT_STATUS.md`](./CURRENT_STATUS.md) | `CURRENT_DOC` | Accepted repository, build, Demo, device, MQTT-side evidence, and gaps. |
| [`docs/REPOSITORY_MAP.md`](./REPOSITORY_MAP.md) | `CURRENT_DOC` | This source-oriented map. |
| [`docs/VERIFIED_FEATURES.md`](./VERIFIED_FEATURES.md) | `CURRENT_DOC` | Evidence-level feature matrix. |
| [`docs/architecture/`](./architecture/) | `CURRENT_DOC` | Android component ownership, lifecycle, and Temi SDK usage maps. |
| [`docs/contracts/`](./contracts/) | `CURRENT_DOC` | Android MQTT, command, and configuration contracts. |
| [`docs/operations/`](./operations/) | `CURRENT_DOC` | Build, install, troubleshooting, release, and signing runbooks. |
| [`docs/handover/HANDOVER_READINESS.md`](./handover/HANDOVER_READINESS.md) | `CURRENT_DOC` | One-page current-state, ownership, prerequisite, evidence, and owner-decision register. |
| [`docs/handover/JUNIOR_HANDOVER.md`](./handover/JUNIOR_HANDOVER.md) | `CURRENT_DOC` | Junior maintainer landing page and documentation reading order. |
| `docs/publication-boundary.md` | `CURRENT_DOC` | Publication-set boundary and exclusions. |
| `docs/performance/yuv-copy-optimization-2026-08-09.md` | `HISTORICAL_DOC` | Historical performance evidence; not current device acceptance. |
| `tools/verify_demo_artifact.ps1` | `CURRENT_TOOL` | Local Demo package, signer, policy, and provenance preflight. |
| `tools/performance/` | `CURRENT_TOOL` | Reproducible YUV copy benchmark helper and source. |
| `.github/workflows/android-ci.yml` | `CURRENT_CONFIG` | Public JDK 21 unit-test and debug-build CI only. |

## Local-only and generated boundary

| Boundary item | Classification | Current rule |
| --- | --- | --- |
| Populated `local.properties` and `signing.local.properties` | `PRIVATE_NOT_TRACKED` | Machine-local settings and private Demo signing inputs; never publish. |
| Keystores, certificates, passwords, aliases, SDK paths, and private endpoints | `PRIVATE_NOT_TRACKED` | Operator-controlled material outside the public source boundary. |
| Project-owned exercise video assets | `TRACKED_PROJECT_ASSET` | `elderly_hand_exercise.mp4` and `elderly_leg_exercise.mp4` are tracked public resources and must be present in a fresh clone. |
| Gradle/Android build outputs, APK/AAB files, logs, reports, captures, and recordings | `GENERATED_NOT_TRACKED` | Generated or local evidence artifacts; not source provenance. |

## External dependencies

| External system or dependency | Classification | Android repository relationship |
| --- | --- | --- |
| Temi SDK `com.robotemi:sdk:1.134.1` | `EXTERNAL_DEPENDENCY` | Consumed from Maven; not vendored in this repository. |
| AI6 backend | `EXTERNAL_DEPENDENCY` | Provides an external command/event contract; implementation is not included. |
| Hermes Agent | `EXTERNAL_DEPENDENCY` | External agent/runtime boundary; no source directory here. |
| Bridge | `EXTERNAL_DEPENDENCY` | External bridge service; no source directory here. |
| LM Studio or another local model runtime | `EXTERNAL_DEPENDENCY` | External inference service; not part of the Android Gradle project. |
| MQTT broker implementation | `EXTERNAL_DEPENDENCY` | External broker/service; Android includes only its MQTT client and owner. |
| Deployment WebSocket service | `EXTERNAL_DEPENDENCY` | Required by camera streaming; endpoint values are machine-local. |

## Evidence vocabulary

`CURRENT_SOURCE` and `CURRENT_TEST` describe what is present now. `CURRENT_DOC`
and `CURRENT_CONFIG` describe the published/current support files.
`HISTORICAL_DOC` is context only. `TRACKED_PROJECT_ASSET` identifies an
authorized project-owned file included in the public repository. Its presence
does not imply `VERIFIED_DEVICE` playback. `GENERATED_NOT_TRACKED` and
`PRIVATE_NOT_TRACKED` are deliberately outside publication. `EXTERNAL_DEPENDENCY`
means acceptance requires a separately controlled system. Presence in any map
row must not be read as **VERIFIED_DEVICE** or **VERIFIED_E2E** behavior.
