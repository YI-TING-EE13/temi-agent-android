# ADB and Install

This is the canonical operator procedure for inspecting, launching, and
forward-upgrading `com.robotemi.agent` on one Temi target. It does not replace
the robot owner’s physical safety procedure.

## Safety model

Always select one authorized Temi serial explicitly. In this runbook,
`<SERIAL>` means the local substitution `<TEMI_IP>:<ADB_PORT>`.

Every package or shell command must use the selected serial:

```text
adb -s <SERIAL> ...
```

If an emulator, another robot, or another Android target appears in
`adb devices -l`, stop and identify the correct target before continuing. Do
not use an unqualified `adb shell`, `adb install`, or `adb logcat` command for
an operation that could affect a device.

The endpoint placeholders in this document are notation only. Do not replace
them with a real Temi address in tracked documentation, issues, or pull
requests. Confirm endpoint ownership before connecting. Do not use
`adb kill-server` as routine recovery and do not perform broad process kills.

## Connect and inspect

Run the inventory command first, then connect the authorized target:

```text
adb devices -l
adb connect <TEMI_IP>:<ADB_PORT>
adb devices -l
adb -s <SERIAL> get-state
```

Continue only when the selected serial reports the expected online state.
`offline`, `unauthorized`, a missing serial, or an unexpected product requires
the corresponding troubleshooting path before installation.

Read-only package inspection:

```text
adb -s <SERIAL> shell pm path com.robotemi.agent
adb -s <SERIAL> shell dumpsys package com.robotemi.agent
```

The package dump is the record source for the installed package identity,
version fields, signing information exposed by the device, `userId`,
`dataDir`, and `firstInstallTime`. Keep the record in the authorized release
notes; do not publish private device identifiers or endpoints.

## Before installing

Before touching the package, record these values for the selected target:

- package: `com.robotemi.agent`;
- installed `versionCode` and `versionName`;
- installed signer, compared with the artifact signer;
- `userId`;
- `dataDir`; and
- `firstInstallTime`.

These values establish that the operator inspected the intended package,
selected a compatible signing identity, and has a before-state for the
forward-upgrade preservation check. Use the signed artifact’s certificate
output as the signer comparison source:

```text
apksigner verify --verbose --print-certs <SIGNED_DEMO_APK>
```

The accepted Demo signer SHA-256 is
`4DA8461B45B02FADCB042F63151FEE05D56EBD5105EB721D7D62E30B88513A7F`.
Use [BUILD_AND_TEST.md](BUILD_AND_TEST.md) to run the repository preflight
before a deployment record is accepted.

## Forward upgrade decision

Install only when the built APK’s `versionCode` is greater than the installed
package’s `versionCode`. Confirm the built version from the artifact/build
record and the installed version from `dumpsys package`.

The accepted forward-upgrade path is one normal replacement install of the
authorized signed Demo APK:

```text
adb -s <SERIAL> install -r <SIGNED_DEMO_APK>
```

`-r` is the normal replacement flag. Do not add `-d` or `--downgrade` to
overcome a version relation. Do not use `uninstall` or `pm clear` as a normal
upgrade step. Those operations change or destroy the app’s data boundary and
require a separately reviewed, explicitly authorized recovery procedure.

If the version relation is not forward, stop. Prepare a reviewed forward
version change rather than weakening the package-manager safety check.

## Demo, not debug

The accepted deployment path uses the signed, non-debuggable Demo APK. A debug
APK is for development and local compile/launch checks only. Do not install a
debug APK as the canonical acceptance or deployment artifact.

## Post-install verification

After the install returns success, repeat the package inspection and compare
the post-state with the recorded before-state:

```text
adb -s <SERIAL> shell pm path com.robotemi.agent
adb -s <SERIAL> shell dumpsys package com.robotemi.agent
```

Record and verify:

- package, `versionCode`, and `versionName`;
- signer compatibility with the authorized Demo certificate;
- `userId`, `dataDir`, and `firstInstallTime` preservation when the upgrade
  gate requires it;
- non-debuggable Demo provenance; and
- the local signed-APK SHA-256 and build/preflight record when practical.

The hash is an artifact-provenance record. It is not a claim that independent
APK builds are bit-for-bit identical.

## Launch

Launch the declared launcher activity with the selected serial:

```text
adb -s <SERIAL> shell am start -n com.robotemi.agent/.MainActivity
```

The app may still require its normal camera and microphone runtime permissions.
Handle those prompts through the approved device procedure; do not publish
device-specific permission state.

## Minimal post-launch smoke

The minimal Android smoke proves only that the package can start and that no
immediate fatal exception is visible. It does not require a live AI6 or MQTT
operation merely to prove Android launch.

```text
adb -s <SERIAL> shell pidof com.robotemi.agent
adb -s <SERIAL> shell dumpsys activity activities | findstr /I "com.robotemi.agent/.MainActivity"
adb -s <SERIAL> logcat -d -t 300 -v threadtime | findstr /I "FATAL EXCEPTION AndroidRuntime com.robotemi.agent"
```

Treat a blank filtered log result as “no matching fatal line in this bounded
capture,” not as proof of full runtime acceptance. The app’s MQTT service and
Temi callbacks have separate operational checks.

## Normal shutdown and targeted restart

Leaving or recreating `MainActivity` does not own the MQTT connection. The
foreground sticky `MqttLifecycleService` is intentionally separate from the
Activity task. Do not stop the package merely because the Activity changed
state.

If an authorized operator must stop or restart only this Android package,
first confirm that the robot is stationary and no safety-critical local action
is active. A package stop is not a physical robot emergency stop.

Targeted package stop:

```text
adb -s <SERIAL> shell am force-stop com.robotemi.agent
adb -s <SERIAL> shell pidof com.robotemi.agent
```

Targeted package restart:

```text
adb -s <SERIAL> shell am force-stop com.robotemi.agent
adb -s <SERIAL> shell am start -n com.robotemi.agent/.MainActivity
```

These commands affect only the selected app package. They do not restart the
broker, backend, Hermes, Bridge, LM Studio, AI6, or the Temi operating system.
Do not add data-clearing or uninstall flags to a restart.
