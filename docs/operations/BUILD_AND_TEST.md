# Build and Test

This runbook describes the supported local build and JVM-test path for the
Android repository. Run commands from the repository root on the maintainer
workstation. The repository does not require a container for the current
build path.

## Supported baseline

Use the following toolchain for a reproducible, source-supported build:

| Tool or setting | Supported value |
| --- | --- |
| JDK | 21 |
| Gradle wrapper | 8.13 |
| Android Gradle Plugin | 8.13.2 |
| Kotlin | 1.9.20 |
| `compileSdk` | 34 |
| `minSdk` | 23 |
| `targetSdk` | 30 |

The project uses the checked-in Gradle wrapper. Use JDK 21 for local and CI
work. JDK 25 is outside the verified baseline and must not be described as a
supported workaround for a build failure.

Check the active tools before diagnosing Gradle output:

```text
java -version
./gradlew --version
```

On Windows PowerShell, use `.\gradlew.bat --version` instead of
`./gradlew`.

## Fresh clone

Use a public repository URL and a new checkout directory. Do not place a
private endpoint, credential, or local filesystem path in this document.

```text
git clone <PUBLIC_REPOSITORY_URL>
cd temi-agent-android
```

Confirm the checkout and branch before changing files:

```text
git branch --show-current
git status --short --untracked-files=all
```

## `local.properties`

Create the machine-local Android SDK configuration from the tracked template.
The populated file is local-only and must not be committed.

Windows PowerShell:

```text
Copy-Item .\local.properties.example .\local.properties
```

Linux or macOS:

```text
cp local.properties.example local.properties
```

Set the minimum required value in `local.properties`:

```text
sdk.dir=<ANDROID_SDK_PATH>
```

Normal public compilation does not require a private WebSocket endpoint. Keep
optional WebSocket, resident identity, care, and legacy-action settings at
their safe defaults unless the deployment owner has supplied the corresponding
contract and configuration.

## Debug build

The debug variant is the development build. It is useful for local compile
and launch checks, but it is not the canonical accepted deployment artifact.

Windows PowerShell:

```text
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain --max-workers=1
```

Linux or macOS:

```text
./gradlew :app:assembleDebug --no-daemon --console=plain --max-workers=1
```

The expected local artifact path is:

```text
app/build/outputs/apk/debug/app-debug.apk
```

A successful desktop build proves that Gradle compiled the selected variant.
It does not prove Temi hardware behavior, broker reachability, camera or
voice operation, or AI6 compatibility.

## JVM tests

Run the source-level JVM tests with the debug test task:

Windows PowerShell:

```text
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain --max-workers=1
```

Linux or macOS:

```text
./gradlew :app:testDebugUnitTest --no-daemon --console=plain --max-workers=1
```

The accepted baseline recorded 288/288 JVM tests passing. The number of tests
may change as the source changes; record the actual result from the current
checkout. These tests are not a substitute for physical Temi or end-to-end
backend acceptance.

## Demo build

Demo is the canonical signed acceptance/deployment variant. Build Demo only
when the operator has authorization to use the project signing identity, an
authorized external keystore, and JDK 21.

The private inputs belong in the ignored `signing.local.properties` file and
in a keystore stored outside the repository. Follow
[SIGNING_HANDOVER.md](SIGNING_HANDOVER.md) for custody and setup. Do not paste
the populated file, passwords, aliases, or keystore path into a commit, issue,
pull request, or log.

Windows PowerShell:

```text
.\gradlew.bat :app:assembleDemo --no-daemon --console=plain --max-workers=1
```

Linux or macOS:

```text
./gradlew :app:assembleDemo --no-daemon --console=plain --max-workers=1
```

`assembleDemo` depends on `verifyDemoSigningConfig`. The signing task is
fail-closed: missing, unreadable, incomplete, incompatible, or wrong-signer
inputs stop the build. The public expected Demo certificate fingerprint is

```text
4DA8461B45B02FADCB042F63151FEE05D56EBD5105EB721D7D62E30B88513A7F
```

The expected Demo artifact path is:

```text
app/build/outputs/apk/demo/app-demo.apk
```

## Artifact preflight

Run the repository preflight against the signed Demo artifact from a clean,
intended checkout. The script selects `aapt` and `apksigner` from
`<ANDROID_SDK_PATH>` or the configured Android SDK environment.

Windows PowerShell:

```text
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\verify_demo_artifact.ps1 `
  -RepoRoot (Get-Location).Path `
  -ApkPath .\app\build\outputs\apk\demo\app-demo.apk `
  -SdkRoot <ANDROID_SDK_PATH>
```

The optional `-RequiredAncestor <KNOWN_BASELINE_SHA>` argument can require
the checked-out source to descend from a reviewed full commit SHA. Use it only
when the release record defines that ancestry requirement.

`verify_demo_artifact.ps1` fails if the repository is not clean or the APK is
missing. It then checks the generated Demo configuration, package identity,
non-debuggable APK state, the expected certificate fingerprint, Media v1.1
enabled state, and the 10,000 ms Media v1.1 attach deadline. A preflight pass
is artifact and source-provenance evidence; it is not a claim that APK files
are bit-for-bit reproducible across builds.

## What build evidence does not prove

- A desktop build is not physical Temi acceptance.
- A JVM unit-test pass is not MQTT, Temi, camera, voice, or AI6 end-to-end
  acceptance.
- A successful Demo build is not proof of AI6 or backend compatibility.
- A signed APK is not proof of navigation arrival, turn completion, speech
  completion on hardware, or a live broker path.
