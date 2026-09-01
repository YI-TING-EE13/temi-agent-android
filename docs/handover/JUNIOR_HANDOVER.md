# TemiAgent Android Handover

This page is the starting point for a new undergraduate or graduate lab
maintainer who knows basic Git and Android but does not yet know this project’s
operational boundaries.

## What this repository is

This repository contains the Android application that runs on Temi. It owns
the Android UI, Temi SDK integration, local command validation and ledger,
MQTT connection and result publication, optional camera/WebSocket streaming,
and the build variants described by the source.

The repository does not contain or operate the following external systems:

- AI6;
- Hermes;
- Bridge;
- LM Studio;
- the MQTT broker; or
- the backend services that produce or consume external commands.

The Android source ends at the MQTT, WebSocket, Temi SDK, and local-resource
boundaries. Do not infer an external service procedure from an Android class.

## Start here

Read the following documents in order. The links are relative to this
handover page:

1. [Handover readiness](HANDOVER_READINESS.md)
2. [Academic-lab development workflow](DEVELOPMENT_WORKFLOW.md)
3. [Current status](../CURRENT_STATUS.md)
4. [Repository map](../REPOSITORY_MAP.md)
5. [Verified features](../VERIFIED_FEATURES.md)
6. [Android architecture](../architecture/ANDROID_ARCHITECTURE.md)
7. [MQTT interface](../contracts/MQTT_INTERFACE.md)
8. [Command contract](../contracts/COMMAND_CONTRACT.md)
9. [Configuration contract](../contracts/CONFIGURATION_CONTRACT.md)
10. [Build and test](../operations/BUILD_AND_TEST.md)
11. [ADB and install](../operations/ADB_AND_INSTALL.md)
12. [Troubleshooting](../operations/TROUBLESHOOTING.md)
13. [Release checklist](../operations/RELEASE_CHECKLIST.md)

Read [Demo signing handover](../operations/SIGNING_HANDOVER.md) before
creating or distributing a Demo artifact.

## First-day setup

Start with a public clone and a development-only build. Run from the cloned
repository root on the maintainer workstation:

```text
git clone https://github.com/YI-TING-EE13/temi-agent-android.git
cd temi-agent-android
Copy-Item .\local.properties.example .\local.properties
```

Set only `sdk.dir=<ANDROID_SDK_PATH>` in the local file for the first-day
workflow. The copied template leaves `ws.server.urls` unset, so the generated
`WS_SERVER_URLS` value is empty and no private WebSocket endpoint is required.
Use JDK 21, then run the JVM tests and the debug build:

```text
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain --max-workers=1
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain --max-workers=1
```

Linux or macOS maintainers can use `./gradlew` with the same task arguments.
The first-day workflow does not require signing assets, a private WebSocket
endpoint, an MQTT endpoint, an AI6 host, or a live Temi.

The debug APK is for development only. Demo signing and device installation
are separate gates.

## Activity foreground readiness

Activity-owned physical checks require `MainActivity` to be resumed and its
window to be focused. Before diagnosing exercise media, camera capture, or
WebSocket streaming, check the resumed activity, top activity, and focused
window with the serial-scoped commands in
[ADB_AND_INSTALL.md](../operations/ADB_AND_INSTALL.md). `StandbyActivity` can
be the current foreground owner. Check that state during operator readiness;
do not claim that `StandbyActivity` always automatically takes foreground.
V4H observed no autonomous takeover during its bounded monitor.

## Before touching a real Temi

Read [ADB_AND_INSTALL.md](../operations/ADB_AND_INSTALL.md) completely. Confirm
the exact authorized serial `<TEMI_IP>:<ADB_PORT>` and use `adb -s <SERIAL>` for
every package or shell operation. Stop if an emulator, another robot, or an
unexpected Android target is present.

Before an upgrade, record the installed package, version, signer, `userId`,
`dataDir`, and `firstInstallTime`. Use only the signed Demo APK and the normal
forward `adb -s <SERIAL> install -r <SIGNED_DEMO_APK>` path. Never blindly use
`uninstall`, `pm clear`, `-d`, or `--downgrade`.

If the robot may be moving or speaking, use the approved physical operator
stop procedure before stopping or restarting the Android package. ADB package
commands are not an emergency-stop procedure.

## Before creating a Demo APK

Obtain explicit authorization from the project signing custodian and read
[SIGNING_HANDOVER.md](../operations/SIGNING_HANDOVER.md). Keep the keystore and
all signing values outside the repository. Demo is the canonical signed
acceptance/deployment variant; debug is not.

## Runtime model

The current Android boundary is:

```text
Android app
  <-> MQTT and WebSocket interfaces
  <-> external broker, backend, and stream services
```

`MqttLifecycleService` owns the long-lived Android MQTT connection. The
Activity observes that service and owns the screen, Temi listeners, camera,
WebSocket lifecycle, and Activity-side command work. MQTT endpoint values are
device-local runtime settings. Do not document AI6 implementation commands or
assume that an Android-side command result proves backend behavior.

## MQTT runtime setup

The current app exposes three MQTT endpoint fields in the `MQTT settings` panel:

| App field | Value to obtain from the deployment owner |
| --- | --- |
| Broker host | `<MQTT_HOST>` without a scheme or path |
| Broker port | `<MQTT_PORT>` from 1 through 65535 |
| Robot ID | `<ROBOT_ID>` matching the device assignment |

On an authorized device, confirm `MainActivity` is resumed and focused, open
`MQTT settings`, enter the three values, and tap `Apply`. The app validates and
stores one endpoint in app-private runtime settings and asks the
`MqttLifecycleService` broker to reconnect. A valid configuration is reflected
by the app's MQTT status; broker-side reachability and authentication remain
external checks. `Disable` removes the active endpoint. Pending command, media,
or care results can block an endpoint change or disable operation until the
outbox is delivered or an explicitly authorized operator discards it.

MQTT host, port, and robot ID are device-local values. They are not tracked in
Git and normally do not require an APK rebuild. Android backup and restore are
disabled, so a new device or robot requires explicit runtime configuration.
For a public first-day build, leave MQTT disabled rather than entering example
placeholders. Verify an authorized deployment with its owner-provided endpoint
and record only redacted status/evidence.

Escalate a broker, MQTT runtime, or Android-to-AI6 connectivity issue to the
MQTT or AI6 runtime owner. Escalate an app settings, UI, or Activity readiness
issue to the Android/LAB606 maintainer. Do not invent a private endpoint or
restart an external service from this repository's runbook.

## Feature state

Use [VERIFIED_FEATURES.md](../VERIFIED_FEATURES.md) for the current evidence
matrix. The currently accepted Demo baseline explicitly has these build flags
disabled:

- resident identity: disabled;
- care reporting: disabled; and
- legacy global MQTT actions: disabled.

Their absence in that Demo build is expected. Enabling any of those paths is a
new configuration and compatibility decision, not a device-side toggle to
apply casually.

## Typical workflow

Use the [academic-lab development workflow](DEVELOPMENT_WORKFLOW.md) for the
full Issue, branch, review, evidence, and escalation rules. The short form is:

```text
PROJECT-01 direction
  -> GitHub Issue and change classification
  -> feature, fix, experiment, or documentation branch
  -> ANDROID-01 implementation owner: analysis, implementation, tests,
     documentation/evidence, review packet, commit, and push
  -> PROJECT-01 / GITHUB-01 PR creation and management
  -> Android CI and required validation
  -> project-level review
  -> merge decision
  -> signed Demo and device acceptance when required
  -> current-status/evidence update and Issue closure
```

`PROJECT-01` provides direction and system-level review. `ANDROID-01` owns
routine Android decomposition, implementation, tests, documentation/evidence,
review-packet contents, and review fixes. `PROJECT-01` / `GITHUB-01` own PR
creation and management and the merge decision. Keep physical and
external-service evidence separate from local source, unit, and build
evidence.

## What not to commit

Follow the repository `.gitignore` and
[CONFIGURATION_CONTRACT.md](../contracts/CONFIGURATION_CONTRACT.md). Do not
commit populated `local.properties` or `signing.local.properties`, keystores,
passwords, aliases, private endpoints, real Temi addresses, AI6 hosts,
private WebSocket URLs, private media, APKs, build outputs, or personal data.

## When to ask before acting

Ask the project owner or the relevant boundary owner before changing:

- signing configuration or signing custody;
- `applicationId` or package identity;
- version downgrades, uninstall, or data clearing;
- the Temi SDK dependency;
- command schema or action allowlists;
- MQTT topics, endpoint selection, QoS, or retained-message behavior;
- legacy global MQTT actions;
- resident identity or care flags in an accepted build; or
- the Android/AI6 contract.

Also ask before restarting an external broker, backend, Hermes, Bridge, LM
Studio, or AI6 service. This Android repository does not own those processes.

## External compatibility boundary

Owner-provided bounded Android-to-AI6 evidence covers MQTT/WebSocket
connectivity and the camera/H.264 stream path; the evidence is summarized in
[HANDOVER_READINESS.md](HANDOVER_READINESS.md). The Android documentation does
not define backend commands. Full Android/AI6 compatibility and
command-by-command acceptance remain `NOT_VERIFIED`. Do not invent a backend
payload or claim full-stack acceptance from an Android-only test.
