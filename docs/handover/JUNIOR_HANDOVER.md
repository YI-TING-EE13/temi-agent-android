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

1. [Current status](../CURRENT_STATUS.md)
2. [Repository map](../REPOSITORY_MAP.md)
3. [Verified features](../VERIFIED_FEATURES.md)
4. [Android architecture](../architecture/ANDROID_ARCHITECTURE.md)
5. [MQTT interface](../contracts/MQTT_INTERFACE.md)
6. [Command contract](../contracts/COMMAND_CONTRACT.md)
7. [Configuration contract](../contracts/CONFIGURATION_CONTRACT.md)
8. [Build and test](../operations/BUILD_AND_TEST.md)
9. [ADB and install](../operations/ADB_AND_INSTALL.md)
10. [Troubleshooting](../operations/TROUBLESHOOTING.md)
11. [Release checklist](../operations/RELEASE_CHECKLIST.md)

Read [Demo signing handover](../operations/SIGNING_HANDOVER.md) before
creating or distributing a Demo artifact.

## First-day setup

Start with a public clone and a development-only build. Run from the cloned
repository root on the maintainer workstation:

```text
git clone <PUBLIC_REPOSITORY_URL>
cd temi-agent-android
Copy-Item .\local.properties.example .\local.properties
```

Set `sdk.dir=<ANDROID_SDK_PATH>` in the local file. Use JDK 21, then run the
JVM tests and the debug build:

```text
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain --max-workers=1
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain --max-workers=1
```

Linux or macOS maintainers can use `./gradlew` with the same task arguments.
The first-day workflow does not require signing assets, a private WebSocket
endpoint, an MQTT endpoint, an AI6 host, or a live Temi.

The debug APK is for development only. Demo signing and device installation
are separate gates.

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

Use the project’s normal change path:

```text
feature branch
  -> source/test change
  -> local tests
  -> pull request
  -> CI
  -> review
  -> merge
  -> Demo build and device acceptance when required
```

Keep source, tests, documentation, and private runtime assets in their
declared scopes. Record physical or external-service evidence separately from
local build evidence.

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

## External final contract

AI6 final operational compatibility is tracked separately and must be synced
after the AI6 freeze. The Android documentation does not define final backend
commands. Do not invent a backend payload or claim full-stack acceptance from
an Android-only test.
