# Troubleshooting

Use the smallest diagnostic scope that can distinguish the failure. Preserve
the installed app data and pending result state while investigating. Do not
publish real Temi addresses, broker values, credentials, device identifiers,
private media details, or signing inputs in an issue or pull request.

## Build fails

Start with the toolchain and checkout, then narrow the failure:

1. Check `java -version`. The supported build baseline is JDK 21. If another
   JDK is active, select the approved JDK 21 environment and rerun the same
   wrapper task. JDK 25 is outside the verified baseline.
2. Check `local.properties`. Copy `local.properties.example` and set
   `sdk.dir=<ANDROID_SDK_PATH>`. The path must identify an installed Android
   SDK that contains the required platform and build tools.
3. Check dependency resolution. A normal public build needs access to the
   configured public repositories, but it does not need private WebSocket,
   MQTT, AI6, or backend values. Fix the network or repository availability
   reported by Gradle; do not add secrets to tracked configuration.
4. Check the wrapper with `.\gradlew.bat --version` on Windows or
   `./gradlew --version` on Linux/macOS. Use the checked-in wrapper and the
   supported Gradle 8.13 distribution; do not replace it with a system Gradle
   version as a workaround.
5. If `assembleDemo` fails at `verifyDemoSigningConfig`, go to [Demo signing
   fails](#demo-signing-fails). Do not switch Demo to debug signing.

Run the failing task again only after correcting the matching cause. Keep the
original failure output private if it contains local paths or environment
details.

## Temi top controls do not respond

V4D identified a Temi-owned `SYSTEM_ALERT_WINDOW` from
`com.roboteam.teamy.usa` covering `[0,0][1920,98]`. The region overlapped the
exercise controls, even though Android reported the controls as visible,
enabled, and clickable. A lower MQTT settings control responded to a normal
coordinate tap, and keyboard focus reached the hand button. These observations
distinguish top-edge touch occlusion from a general ADB or playback failure.

V4H later accepted the 1.0.5/code6 hand and leg coordinate taps and local video
playback after `MainActivity` was confirmed resumed and its window focused.
Treat that foreground state as a prerequisite for physical acceptance.
`StandbyActivity` can be the foreground owner on Temi, so check the resumed
activity, top activity, and focused window during diagnosis and operator
readiness. V4H observed no autonomous takeover during its bounded monitor and
does not establish that `StandbyActivity` always automatically takes
foreground.

For a bounded diagnosis, keep the exact operator-owned serial on every command
and inspect the current stack without changing device state:

```text
adb -s <SERIAL> shell dumpsys window windows
adb -s <SERIAL> shell dumpsys input
adb -s <SERIAL> shell uiautomator dump /dev/tty
```

Confirm the focused `MainActivity`, the visible Temi top window, its touchable
region, and the current exercise-button bounds. Do not disable or modify the
Temi overlay, clear package data, reboot, or publish a command as a workaround.
The V4E 1.0.5 candidate applies a centralized Temi top-safe-area policy to
`appContent`; V4F physically confirmed HAND `[793,120][955,192]` and LEG
`[955,120][1117,192]` outside the historical `[0,0][1920,98]` overlay. V4H
recorded PASS for both coordinate-tap playback paths. If `MainActivity` is not
resumed and focused, stop the coordinate acceptance attempt and restore that
readiness condition before classifying a source or touch-dispatch defect.

## Demo signing fails

`app/build.gradle` deliberately validates Demo signing before
`assembleDemo`, `bundleDemo`, and Demo packaging tasks. Interpret the emitted
code as follows:

| Code | Meaning | Safe next action |
| --- | --- | --- |
| `CONFIG_MISSING` | The ignored `signing.local.properties` file was not loaded. | Obtain authorization and create the file from `signing.local.properties.example`. |
| `PROPERTY_MISSING` | One or more of the four required private values is absent or blank. | Ask the signing custodian to verify the private configuration without exposing its values. |
| `KEYSTORE_PATH_NOT_ABSOLUTE` | The configured store path is not absolute. | Use the authorized external keystore path as an absolute `<KEYSTORE_PATH>`. |
| `KEYSTORE_UNAVAILABLE` | The configured store file is missing or unreadable. | Check access to the authorized external file; do not copy it into the repository. |
| `KEYSTORE_LOAD_FAILED` | The keystore could not be opened with a supported format and supplied private inputs. | Stop and ask the custodian to check format, store password, or file integrity. |
| `ALIAS_UNAVAILABLE` | The requested private alias is not in the keystore. | Verify the alias with the custodian; do not invent a new alias or key. |
| `KEY_UNAVAILABLE` | The key entry could not be read with the supplied private input. | Ask the custodian to verify key access and key password out of band. |
| `CERTIFICATE_UNAVAILABLE` | The selected alias does not expose a readable certificate. | Stop and have the custodian inspect the authorized keystore. |
| `SIGNER_MISMATCH` | The certificate does not match the accepted Demo identity. | Stop the deployment and compare the public fingerprint with the project owner. |

The accepted public Demo certificate SHA-256 is
`4DA8461B45B02FADCB042F63151FEE05D56EBD5105EB721D7D62E30B88513A7F`.
The fingerprint is safe to compare publicly; the keystore, passwords, alias,
and external custody location are not.

Never generate a replacement key to update an already deployed package merely
because the existing credentials are inconvenient. A new signing identity
requires a deliberate project-owner decision and a designed migration path.

## ADB cannot connect

Use `adb devices -l` and an explicit `<SERIAL>` at every step. Diagnose the
failure class before changing anything:

| Failure class | Checks | Next action |
| --- | --- | --- |
| Host unreachable | Confirm the Temi is powered, on the expected network, and owned by the operator. An approved workstation may use `Test-NetConnection <TEMI_IP> -Port <ADB_PORT>`. | Correct the physical/network path or stop for the device owner. |
| TCP unavailable | Confirm `<ADB_PORT>` is the authorized ADB port and that the device-side ADB service is enabled by the approved procedure. | Restore the device-side ADB condition; do not change broker or backend services. |
| ADB offline | Run `adb -s <SERIAL> get-state` and inspect `adb devices -l`. | Reconnect only the verified target with `adb disconnect <SERIAL>` followed by `adb connect <TEMI_IP>:<ADB_PORT>`. |
| Unauthorized | The device has not authorized this workstation or its authorization changed. | Complete the on-device authorization procedure with the owner present, then recheck `adb devices -l`. |
| Wrong target | The selected serial, model, or package does not identify the intended Temi. | Stop, inspect with `adb -s <SERIAL> shell getprop ro.product.model`, and select the owned target explicitly. |
| Emulator confusion | An emulator or another Android device is online. | Never use an ambiguous ADB command; keep `-s <SERIAL>` on all package and shell operations. |

Do not recommend `adb kill-server` as the first response. Do not disconnect
other devices or kill broad processes. If a targeted disconnect is necessary,
confirm the exact serial first.

## `INSTALL_FAILED_VERSION_DOWNGRADE`

Read the installed `versionCode` from
`adb -s <SERIAL> shell dumpsys package com.robotemi.agent` and the built
`versionCode` from the artifact/build record. The normal operator condition is

```text
built versionCode > installed versionCode
```

Do not add `-d` or `--downgrade` automatically. A downgrade can invalidate the
forward-upgrade evidence and change the data-preservation decision. If the
version is wrong, create a reviewed forward version bump and rebuild the
authorized Demo artifact.

## `INSTALL_FAILED_UPDATE_INCOMPATIBLE`

This error normally indicates an incompatible package identity or signer.
Stop before uninstalling anything. Compare the installed package and signer
record with the signed Demo artifact:

```text
adb -s <SERIAL> shell pm path com.robotemi.agent
adb -s <SERIAL> shell dumpsys package com.robotemi.agent
apksigner verify --verbose --print-certs <SIGNED_DEMO_APK>
```

Confirm package `com.robotemi.agent` and the accepted Demo certificate before
asking the project owner to decide the next step. Do not recommend
`uninstall` as the default fix. Do not use `pm clear` to bypass a signer or
package-identity problem.

## App launches then crashes

Capture a bounded, targeted view. Do not clear the global log buffer as a
first step:

```text
adb -s <SERIAL> shell pidof com.robotemi.agent
adb -s <SERIAL> shell dumpsys activity activities | findstr /I "com.robotemi.agent/.MainActivity"
adb -s <SERIAL> logcat -d -t 300 -v threadtime | findstr /I "FATAL EXCEPTION AndroidRuntime com.robotemi.agent"
```

If a clean capture is explicitly required, obtain owner approval and document
that the selected environment owns the log buffer before clearing it. A
bounded capture with no matching line means only that no matching fatal line
was visible in that capture; it does not prove runtime acceptance.

Check the installed package version and signer before changing the artifact.
If the crash follows a package restart, preserve app data and pending MQTT
results while collecting the evidence.

## MQTT `RECONNECTING`

Treat MQTT as an Android-to-external-service boundary. Follow this order:

1. **App and service alive?** Check the package process with
   `adb -s <SERIAL> shell pidof com.robotemi.agent` and inspect the app status.
   `MqttLifecycleService` is a foreground, sticky service owned by the Android
   app; `MainActivity` observes it and does not own the broker lifecycle.
2. **Endpoint configured?** Inspect the app’s MQTT settings and confirm one
   valid device-local endpoint: `<MQTT_HOST>`, `<MQTT_PORT>`, and
   `<ROBOT_ID>`. Do not paste the values into public logs. Invalid or disabled
   configuration should remain disconnected rather than fail open.
3. **Connection attempt?** Distinguish `CONNECTING` or `RECONNECTING` from
   `DISCONNECTED`, `DEGRADED`, and `CONNECTED` in the app’s status and bounded
   diagnostics. The Android reconnect policy starts at one second and caps at
   30 seconds.
4. **TCP and broker reachable from the robot path?** Test the network path
   from the Temi’s environment to the authorized broker with the network
   owner. A workstation path such as LAB606 to the broker is not required if
   the product path is Temi to broker.
5. **CONNACK received?** Ask the broker owner for the connection-side result.
   Separate TCP failure, authentication/broker rejection, and Android client
   errors. Do not infer a broker failure from a local Activity status alone.
6. **Android runtime or external dependency?** Check whether the service is
   alive and whether the endpoint/topic configuration is valid before asking
   the external broker, backend, Hermes, Bridge, or AI6 owner to investigate.

The Android client uses one active endpoint, QoS 1, non-retained
publications, and bounded reconnect. Pending result outboxes remain local and
must not be discarded merely because the connection is temporarily down.

A targeted package restart is available in
[ADB_AND_INSTALL.md](ADB_AND_INSTALL.md). It restarts only the Android app; it
does not restart the broker or any external service.

## Command does not execute

Check the request against the Android contract before changing the device:

- request topic is `temi/<ROBOT_ID>/cmd/request` for the configured robot;
- the configured endpoint has exactly one active robot identifier;
- inbound command delivery is not retained;
- UTF-8 payload size is at most 64 KiB;
- generic commands use schema `1.0`, while the separate Media v1.1 path is
  enabled only by the selected build variant;
- `command_id`, `event_id`, and `robot_id` are non-empty, and `robot_id`
  matches the configured endpoint;
- `actions` is non-empty and within the five-action limit;
- each action type and its fields satisfy the allowlists; and
- the relevant build flags are enabled only when the deployment explicitly
  requires them.

Use the source-derived [COMMAND_CONTRACT.md](../contracts/COMMAND_CONTRACT.md)
and [MQTT_INTERFACE.md](../contracts/MQTT_INTERFACE.md) for the exact topic,
schema, action, result, retained-message, and size rules. Do not invent a
backend command or send arbitrary media paths, URLs, content URIs, or resource
names. Navigation and turn results are dispatch acknowledgements, not physical
arrival or pose confirmation.

## Duplicate or replayed request

The Android command ledger uses `command_id` plus the raw-payload digest. An
existing command can be classified as a cached terminal result, a pending
duplicate, or a payload conflict. The ledger persists terminal results before
non-retained publication and retries compatible pending results after
reconnect.

This idempotency and durable-result behavior is not a protocol TTL or freshness
check. The generic command schema has no `issued_at`, `expires_at`, sequence,
or protocol timestamp field. Do not resend an uncertain irreversible command
with a new ID just to force execution. First inspect the result and ledger
state with the service owner; a new ID can create a second physical side
effect.

## Media unavailable

The allowlisted exercise media resources are tracked project-owned assets. A
missing `elderly_hand_exercise` or `elderly_leg_exercise` resource produces a
`media_unavailable:<media_id>` result; it does not authorize a URL, arbitrary
file path, content URI, or bypass.

Check that the fresh clone contains both raw-resource paths, that the
authorized Demo APK contains both `res/raw` entries, and that the command uses
the fixed media ID. Do not replace either project-owned video or add an
arbitrary media source solely to make a test pass.

## Resident Identity and Care

The accepted Demo baseline has both `resident.identity.enabled` and
`care.report.enabled` set to `false`. The absence of resident identity or care
UI and messages is therefore expected in that build. A new build must enable
these paths explicitly and use the corresponding source contract before an
operator treats them as available. Care reporting also requires identity
state; enabling an unrelated runtime message does not enable the feature.

## Safe recovery boundary

Leave package data and pending outboxes intact while diagnosing. If an
artifact must be replaced, use a reviewed forward version and a compatible
signer. Do not use a downgrade flag, uninstall, or `pm clear` as routine
recovery. If the robot may be moving or speaking, use the approved physical
operator stop procedure before stopping or restarting the Android package.

The Android repository does not own AI6, Hermes, Bridge, LM Studio, broker, or
backend processes. Do not restart or reconfigure those services from an
Android-only incident without their owner and a separate runbook.
