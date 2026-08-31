# Android Handover Readiness

This register is the current landing page for a successor maintainer. It
records the accepted Android baseline, the first-day path, evidence boundaries,
external ownership, and decisions that remain with the project owner. Detailed
procedures remain in the linked architecture, contract, and operations
documents.

## 1. Accepted baseline

| Item | Current accepted value |
| --- | --- |
| Repository | `YI-TING-EE13/temi-agent-android` |
| Public clone | `https://github.com/YI-TING-EE13/temi-agent-android.git` |
| Canonical branch | `main` |
| G3B audit baseline | `b22cce606074e1843bcd4770517482336522942e` (audit baseline, not a permanent future documentation HEAD) |
| Package | `com.robotemi.agent` |
| Android version | `versionCode 6` / `versionName 1.0.5` |
| Current JVM result | `294/294` tests passed |
| Demo role | Signed, non-debuggable acceptance/deployment variant; signing access remains owner-controlled. |
| Accepted installed APK SHA-256 | `0F386BE227ED964CA25507A15589E113259B15DDC7C9166B59B6B2640EAECEA4` |
| Expected Demo signer SHA-256 | `4DA8461B45B02FADCB042F63151FEE05D56EBD5105EB721D7D62E30B88513A7F` |
| Project-owned exercise media | `elderly_hand_exercise.mp4` and `elderly_leg_exercise.mp4`, both `VERIFIED_DEVICE` |
| Temi top safe area | `VERIFIED_DEVICE`; `TOP_SAFE_AREA_PHYSICAL_ACCEPTANCE = PASS` |

The accepted forward upgrade was `versionCode 5 / 1.0.4` to `versionCode 6 /
1.0.5` using one normal `adb install -r`. The accepted record preserved
`userId`, `dataDir`, `firstInstallTime`, and signer identity. The historical
`versionCode 2` to `3` upgrade remains context only.

## 2. First-day path

Use JDK 21, Android SDK Platform 34, and the checked-in Gradle Wrapper (Gradle
8.13). From a maintainer workstation:

```text
git clone https://github.com/YI-TING-EE13/temi-agent-android.git
cd temi-agent-android
Copy-Item .\local.properties.example .\local.properties
```

Edit the ignored `local.properties` and set only:

```text
sdk.dir=<ANDROID_SDK_PATH>
```

The tracked template leaves `ws.server.urls` unset. A first-day copy therefore
generates `WS_SERVER_URLS` as an empty string and creates no WebSocket client;
no private endpoint is required for the public build. Keep the identity, care,
and legacy MQTT flags disabled.

Run the source checks from the repository root:

```text
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain --max-workers=1
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain --max-workers=1
```

The expected first-day result is `294/294` JVM tests passed and a successful
debug build. The debug APK is development-only. Read
[BUILD_AND_TEST.md](../operations/BUILD_AND_TEST.md) for the detailed build,
artifact, and signing gates.

## 3. Current verified evidence

- The current Android source/build baseline is `VERIFIED_BUILD`; the current
  JVM result is `294/294`.
- An authorized operator reproduced the signed Demo and passed artifact
  preflight for package `com.robotemi.agent`, versionCode 6 / versionName 1.0.5,
  the expected signer, `debuggable=false`, and Media v1.1 enabled.
- Both tracked exercise resources are packaged. V4H recorded `PASS` for one
  hand coordinate tap and one leg coordinate tap; each local video was visible,
  advanced, and completed naturally.
- The top-safe-area policy is `max(0.09 * windowHeight, 72dp)`, combined with
  system-bar and display-cutout insets. V4D identified the historical
  Temi-owned `com.roboteam.teamy.usa` input region `[0,0][1920,98]`. V4F
  confirmed HAND `[793,120][955,192]` and LEG `[955,120][1117,192]` outside
  that region.
- V4H kept `MainActivity` foreground for at least `27584 ms` across `47`
  samples. No autonomous `StandbyActivity` takeover was observed during that
  bounded monitor. MQTT settings coordinate control also passed.
- Bounded owner-provided Android-to-AI6 evidence was recorded against an
  externally owned AI6 runtime; its canonical machine-local root remains out
  of band. External owner-provided provenance records branch `main`, accepted
  HEAD `12aff3bfdfe526c17a25a2681aea2afad7112b33`.
- Canonical AI6 lifecycle start gate: `PASS`.
- Canonical AI6 runtime status: `DEMO_READY`.
- Canonical AI6 doctor/health gate: `PASS`.
- The bounded path covers MQTT connectivity, WebSocket client
  creation/connection, Temi-to-AI6 connectivity, camera frame input, H.264
  output, binary send, AI6 H.264 ingress, non-empty `VisionBuffer`, and viewer
  frames. This evidence is not universal compatibility.

Physical acceptance of Activity-owned media, camera, and WebSocket paths
requires `MainActivity` to be resumed and its window focused. Before diagnosis,
check the resumed activity, top activity, and focused window. `StandbyActivity`
can be the foreground owner; the evidence does not claim that it always
automatically takes foreground. Use the serial-scoped checks in
[ADB_AND_INSTALL.md](../operations/ADB_AND_INSTALL.md).

The final local defect classification for the exercise-media incident is:

```text
ANDROID_MEDIA_SOURCE_BUG = NO
ANDROID_TOUCH_SOURCE_BUG = NO
ANDROID_SOURCE_FIX_REQUIRED = NO
```

## 4. External ownership boundaries

| Boundary | Repository/runtime owner | Successor action |
| --- | --- | --- |
| Android source | Android/LAB606 maintainer | Change source only with source, test, and acceptance evidence. |
| Temi/ADB | Temi/device operator; assignment remains `OWNER_ASSIGNMENT_REQUIRED` | Confirm one authorized serial and follow the serial-scoped runbook before device work. |
| AI6 backend | AI6 runtime owner; assignment remains `OWNER_ASSIGNMENT_REQUIRED` | Use the owner-provided lifecycle and bounded evidence; do not invent backend commands. |
| MQTT broker/runtime | MQTT or AI6 runtime owner; assignment remains `OWNER_ASSIGNMENT_REQUIRED` | Supply device-local host/port/robot ID and diagnose broker reachability at that boundary. |
| Hermes | Hermes runtime owner; assignment remains `OWNER_ASSIGNMENT_REQUIRED` | Treat Hermes as external; do not infer its behavior from Android source. |
| Bridge | Bridge runtime owner; assignment remains `OWNER_ASSIGNMENT_REQUIRED` | Treat Bridge as external and request its separate runbook when needed. |
| LM Studio | Local-model runtime owner; assignment remains `OWNER_ASSIGNMENT_REQUIRED` | Treat model serving as external; do not add its endpoint or credentials here. |
| WebSocket adapter/viewer | Streaming adapter/viewer owner; assignment remains `OWNER_ASSIGNMENT_REQUIRED` | Supply deployment-specific WebSocket endpoints out of band and verify the bounded stream path. |
| Demo signing | Project signing custodian; successor assignment is `OWNER_DECISION_REQUIRED` | Request access through the approved private channel; never copy signing material into Git. |
| GitHub/release governance | Project owner/release maintainer; assignment remains `OWNER_ASSIGNMENT_REQUIRED` | Decide branch protection, tags, releases, license, and artifact publication policy. |

## 5. Runtime prerequisites

- A public clone, JDK 21, Android SDK Platform 34, and network access to the
  configured public Maven repositories are required for first-day build/test.
- A physical Temi check additionally requires an authorized signed Demo APK,
  one owned serial, approved camera/microphone permission handling, and a
  stationary robot with no safety-critical local action active.
- Activity-owned physical checks require resumed/focused `MainActivity`. Confirm
  the current top activity and focused window if `StandbyActivity` is visible.
- MQTT is optional for first-day source setup. When a deployment requires it,
  configure `Broker host`, `Broker port`, and `Robot ID` in the app's `MQTT
  settings` panel, then tap `Apply`. Use `<MQTT_HOST>`, `<MQTT_PORT>`, and
  `<ROBOT_ID>` in records; do not publish private values.
- MQTT runtime values are stored in app-private device settings, are not
  tracked in Git, and normally do not require an APK rebuild. Android backup and
  restore are disabled, so a new device/robot must be configured explicitly.

## 6. Disabled features

The accepted Demo keeps the following build-time features disabled:

- Resident Identity: `DISABLED`.
- Care Report: `DISABLED`.
- Legacy global MQTT actions: `DISABLED`.

Source and JVM tests for the optional paths remain present. Enabling a path is a
new compatibility and acceptance decision; it is not a routine device toggle.

## 7. Known limitations

- `FULL_ANDROID_AI6_COMPATIBILITY = NOT_VERIFIED`. Complete
  command-by-command acceptance, physical completion of every robot action,
  full Resident Identity/Care Report E2E, disabled-feature compatibility, and
  universal deployment compatibility are not established.
- The bounded camera/WebSocket evidence does not establish sustained thermal
  streaming or every external adapter/viewer deployment.
- Android navigation reports dispatch rather than physical arrival; physical
  turn completion and physical stop acceptance are not established here.
- Independent builds are not claimed to produce bit-for-bit identical APKs.
- The current target SDK is 30 and Java/Kotlin source compatibility is Java 8;
  modernization is outside G3B.

## 8. Owner decisions still open

The successor must not decide these items unilaterally:

- `RELEASE_TAG_POLICY`: no Git tags or releases currently exist.
- `LICENSE`: no `LICENSE` file exists; do not add one as part of G3B.
- `BRANCH_PROTECTION`: GitHub `main` protection is currently disabled; enabling
  it is an owner decision.
- `SIGNING_SUCCESSOR`: assign primary and backup signing custody.
- `SIGNING_BACKUP_RECOVERY`: define protected backup and disaster recovery.
- `ACCEPTED_APK_ARCHIVE`: define the authoritative signed-APK archive.
- `FULL_ANDROID_AI6_ACCEPTANCE_SCOPE`: define any broader E2E gate.
- Optional feature enablement: keep Resident Identity, Care Report, and legacy
  MQTT actions disabled until separately approved.

## 9. Safe-to-defer technical debt

The following items do not block this documentation/configuration handover and
require their own scope and acceptance if pursued:

- target SDK 30 and Java 8 source compatibility modernization;
- signed-Demo and physical Temi checks outside the current public CI workflow;
- release/tag, branch-protection, license, and accepted-artifact governance;
- broader navigation, turn, stop, voice, and full Android-to-AI6 acceptance;
- universal WebSocket deployment and sustained camera-stream acceptance.

## 10. Final handover checklist

- [x] Current main baseline, package, version, signer, and installed APK hash
      are recorded.
- [x] Public clone and first-day JDK/SDK/build/test path are executable.
- [x] The copied public template leaves `WS_SERVER_URLS` empty.
- [x] Project-owned hand and leg media are `VERIFIED_DEVICE`.
- [x] Top safe-area policy and V4F physical bounds are `VERIFIED_DEVICE`.
- [x] MainActivity foreground/focus readiness and the StandbyActivity boundary
      are documented.
- [x] MQTT runtime fields and role-based escalation are documented.
- [x] Bounded Android-to-AI6 evidence is separated from full compatibility.
- [x] Disabled features and unresolved owner decisions are explicit.
- [ ] Project owner assigns the open governance and external-runtime roles.
- [ ] Project owner decides the open items in section 8.

Detailed procedures: [CURRENT_STATUS.md](../CURRENT_STATUS.md),
[VERIFIED_FEATURES.md](../VERIFIED_FEATURES.md),
[CONFIGURATION_CONTRACT.md](../contracts/CONFIGURATION_CONTRACT.md),
[BUILD_AND_TEST.md](../operations/BUILD_AND_TEST.md),
[ADB_AND_INSTALL.md](../operations/ADB_AND_INSTALL.md),
[SIGNING_HANDOVER.md](../operations/SIGNING_HANDOVER.md), and
[RELEASE_CHECKLIST.md](../operations/RELEASE_CHECKLIST.md).
