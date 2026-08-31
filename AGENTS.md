# AGENTS.md

This is the developer handoff for the public TemiAgent Android repository.
Keep it focused on the Android project; do not treat external services as
part of the Gradle build.

## Project identity

- Android project root: this directory.
- Android module: app/.
- Application ID: com.robotemi.agent.
- Repository release candidate: 1.0.5, versionCode 6.
- Current accepted physical Temi baseline: 1.0.5, versionCode 6.
- Historical physical Temi baseline before V4F: 1.0.4, versionCode 5.
- Main entry point: app/src/main/java/com/robotemi/agent/MainActivity.java.
- Temi SDK dependency: com.robotemi:sdk:1.134.1.

README.md is the public setup guide. docs/publication-boundary.md records the
publication-set decision. The performance note under docs/ is historical
engineering evidence, not a substitute for current device acceptance.

## Documentation authority

When documentation or handover notes appear to conflict, consult the sources
in this order:

1. Current source, configuration, and tests.
2. [docs/CURRENT_STATUS.md](docs/CURRENT_STATUS.md).
3. [docs/VERIFIED_FEATURES.md](docs/VERIFIED_FEATURES.md).
4. [docs/contracts/](docs/contracts/).
5. [docs/operations/](docs/operations/).
6. Historical notes, including the performance record.

Historical evidence provides context only. It must not override current source
or current-status documentation.

## Build invariants

- The Gradle Wrapper is the source of truth for local Gradle execution.
- The project uses Android Gradle Plugin 8.13.2, Gradle 8.13, compileSdk 34,
  minSdk 23, targetSdk 30, and Java 8 source/target compatibility.
- JDK 21 is the verified and recommended Gradle runtime. JDK 25 is outside the
  verified compatibility baseline.
- Media v1.1 is enabled only by the tracked demo build type. Do not add a
  local.properties or IDE toggle for that feature.
- The project-produced exercise videos are tracked public resources. Missing
  resources must still report unavailable media without crashing the app.
- Demo signing is explicit and fail-closed. It reads only the ignored
  signing.local.properties file and an absolute keystore path outside this
  project. Never commit, print, or post signing values.

## Local configuration

Copy local.properties.example to the project root as local.properties and set
the local Android SDK path. Leave the optional WebSocket endpoint unset for a
normal public build; configure it only when an authorized deployment supplies
the endpoint.
Configure MQTT host, port, robot ID, and broker values through the app's
private runtime settings. Do not add machine paths, credentials, or private
endpoints to tracked files.

## Runtime invariants

- MainActivity owns UI, Temi callbacks, voice handoff, camera resources, and
  device-side command dispatch.
- mqtt.MqttLifecycleService owns the long-lived MQTT connection. Activity
  lifecycle changes must not disconnect the service-owned client.
- Incoming command envelopes and actions are validated before robot hardware is
  touched. Duplicate commands use the bounded local ledger and must not repeat
  hardware side effects.
- Unsafe process-death states fail closed. Only explicitly safe retry cases may
  be retried.
- TTS and media results are callback-grounded. Do not report completion merely
  because a dispatch call returned.
- Motion validation remains allowlisted, and media playback remains limited to
  the allowlisted exercise identifiers when local resources are available.
- The custom voice gate must continue to reject unsolicited Temi system wake
  events unless the app explicitly opened the ASR handoff.

These invariants describe existing behavior. A documentation or publication
cleanup must not refactor MQTT, robot control, TTS, media, identity, care
contracts, camera, WebSocket, or command schemas.

## Important code locations

- MainActivity.java: voice ownership, UI state, Temi callbacks, command
  dispatch, camera/WebSocket lifecycle.
- AgentStateMachine.java: interaction states, timeout, and interruption.
- mqtt/: broker lifecycle, topic contracts, validation, and durable delivery.
- camera/: YUV copy, H.264 encoding, and camera ownership.
- app/src/test/: JVM contract and lifecycle tests. Instrumentation tests are
  not currently included.

## Build and test

Run from the project root:

    .\gradlew.bat :app:assembleDebug --no-daemon --console=plain --max-workers=1
    .\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain --max-workers=1

The debug APK is app/build/outputs/apk/debug/app-debug.apk and is DEVELOPMENT
ONLY. A successful desktop build or JVM test run does not establish Temi device
acceptance. The canonical accepted deployment artifact is the signed,
non-debuggable Demo variant; see docs/operations/BUILD_AND_TEST.md and
docs/operations/SIGNING_HANDOVER.md.
The public GitHub Actions workflow runs the same JVM test and debug-build tasks
on Ubuntu with JDK 21.

The optional tools/verify_demo_artifact.ps1 helper expects a clean Git working
tree, a generated Demo build, and local signing material. Its repository root
is the current project root; an upstream history ancestor is optional and must
be supplied explicitly when a release process needs that check.

## Device validation

Device work is separate from documentation and desktop build checks. Before
touching a real Temi, read
[docs/operations/ADB_AND_INSTALL.md](docs/operations/ADB_AND_INSTALL.md).

Development:

- Use `assembleDebug` and the JVM tests for local development and compile
  checks.
- Treat `app-debug.apk` as DEVELOPMENT ONLY.

Deployment acceptance:

- Use an authorized, signed, non-debuggable Demo artifact.
- Confirm ownership of one exact serial `<TEMI_IP>:<ADB_PORT>` and use
  `adb -s <SERIAL>` for every package or shell command.
- Follow the runbook’s forward-upgrade, signer, preservation, launch, and
  bounded-diagnostics checks.

Do not publish a real ADB endpoint or machine hostname. Do not use broad ADB
server ownership changes when another operator may be connected. Confirm
endpoint ownership before any install or runtime test.

## Publication boundary

The Android publication includes app/, gradle/, the Gradle wrapper, root build
configuration, tests, source resources, tools/, and public/developer docs.
Project-produced exercise videos are included in the public publication as
tracked resources. Private deployment configuration and generated artifacts
remain excluded.

Backend and bridge implementations are outside this repository and are not
published here.

Never publish local.properties, signing.local.properties, keystores,
certificates, SDK paths, APK/AAB files, build directories, Gradle caches, logs,
captures, recordings, or temporary reports.

## Workspace safety

Work from this project root and verify local paths before editing. Do not
assume a parent SDK checkout, linked worktree, remote URL, branch name, or
historical root document exists. Keep changes narrow, preserve unrelated
working-tree state, and do not perform unrequested repository history or
remote operations as part of an Android publication cleanup.
