# Android Configuration Contract

## Scope and labels

This document classifies configuration consumed by the Android app. The
classification is derived from app/build.gradle, the tracked configuration
templates, MqttLifecycleService, MainActivity, SharedPreferences runtime
settings, media runtime classes, and the configuration tests.

- SOURCE_CONTRACT: enforced by Android source or build logic.
- BUILD_TIME_OPTION: changing the value requires a new build, and usually a
  reinstall.
- RUNTIME_OPTION: persisted or selected on the device without changing the
  APK.
- DEVICE_SIDE_EFFECT: a setting can enable a path that speaks, moves, wakes,
  or plays media.
- EXTERNAL_DEPENDENCY: a value identifies a broker, backend, or other service.
- NOT_E2E_VERIFIED: source-derived configuration behavior not claimed as
  live deployment acceptance.

This document uses placeholders only. It does not publish an MQTT endpoint,
WebSocket endpoint, SDK path, signing secret, certificate digest, password,
or private filesystem path.

## TRACKED_BUILD_CONFIG

The following build configuration is tracked and public. The Android module
uses application ID com.robotemi.agent, compile SDK 34, minimum SDK 23, target
SDK 30, Java source/target compatibility 8, Android Gradle Plugin 8.13.2,
Gradle 8.13, and Temi SDK dependency com.robotemi:sdk:1.134.1.

The app/build.gradle script reads local machine properties while generating
BuildConfig fields:

| BuildConfig field | Input | Default or variant value | Effect |
| --- | --- | --- | --- |
| WS_SERVER_URLS | local property ws.server.urls | Empty string | MainActivity creates one WebSocket client per comma-separated URL. Empty means no configured WebSocket client. |
| RESIDENT_IDENTITY_ENABLED | local property resident.identity.enabled | false | Enables resident identity UI and identity topic subscription. |
| CARE_REPORT_ENABLED | local property care.report.enabled | false | Enables care report UI, care topic subscription, and the identity prerequisite. |
| LEGACY_MQTT_ACTIONS_ENABLED | local property legacy.mqtt.actions.enabled | false | Enables the three global legacy MQTT action subscriptions. Retained legacy messages remain rejected. |
| MEDIA_V11_ENABLED | build variant | debug false; demo true; release false | Enables the schema 1.1 media runtime only in the demo variant by default. |
| MEDIA_V11_ATTACH_DEADLINE_MS | build variant | debug 0; demo 10000; release 0 | Demo waits up to 10,000 ms for the transient playback binding. A non-positive value lets the media runtime use its source default. |

The three feature flags are parsed during Gradle configuration. Changing a
feature flag does not change the running APK; rebuild and reinstall the
affected variant.

The demo variant is non-debuggable and uses the explicit demo signing
configuration. The source verifies the configured demo keystore, alias, key,
certificate, and expected signer digest before a demo build can proceed. This
document intentionally does not reproduce the expected digest or any signing
input.

Project-owned exercise media is not a tracked build configuration value. The
allowlisted media IDs are fixed in source; the corresponding raw resources are
classified under TRACKED_PROJECT_ASSET and are part of the intended public
repository.

## IGNORED_MACHINE_LOCAL_CONFIG

The following machine-local files and currently covered key/keystore formats
are intentionally ignored and must not be committed with populated values:

- local.properties;
- signing.local.properties;
- generated Gradle/build directories;
- *.jks, *.keystore, *.p12, *.pfx, *.pem, and *.key;
- APK/AAB outputs, logs, captures, and recordings.

local.properties.example is a tracked template. It contains:

~~~properties
# sdk.dir=<ANDROID_SDK_PATH>
# Optional deployment-specific WebSocket video endpoints.
# Leave unset for normal public development builds.
# ws.server.urls=ws://<BACKEND_HOST>:8080
resident.identity.enabled=false
care.report.enabled=false
legacy.mqtt.actions.enabled=false
~~~

The values above are placeholders or disabled defaults. `sdk.dir` is consumed
by Gradle and points to the machine's Android SDK. An omitted `ws.server.urls`
leaves `WS_SERVER_URLS` empty, so the first-day copied template creates no
WebSocket client. A deployment may uncomment the property and supply a
comma-separated endpoint list. The identity, care, and legacy flags are false
by default.

The template does not contain MQTT host, port, robot ID, broker username, or
broker password. Those values belong to the private device runtime settings
and external deployment configuration. The source never requires a real
endpoint in tracked documentation.

signing.local.properties.example documents four ignored private keys:

~~~properties
demo.signing.store.file=<ABSOLUTE_KEYSTORE_PATH>
demo.signing.store.password=<KEYSTORE_PASSWORD>
demo.signing.key.alias=<KEY_ALIAS>
demo.signing.key.password=<KEY_PASSWORD>
~~~

The examples are not credentials and are not buildable until a private
operator supplies approved values. The signing file path must be absolute and
readable; the build fails closed when the file or its verified signer is
missing.

## DEVICE_RUNTIME_CONFIG

MqttLifecycleService and MainActivity share the mqtt_runtime
SharedPreferences store. The runtime endpoint selection contains:

| Runtime value | Source rule |
| --- | --- |
| Endpoint count | Zero disables MQTT; exactly one is valid; any other count is invalid and fails closed. |
| MQTT host | Normalized to lower case and validated as a host value without a scheme, path, slash, at-sign, comma, or path-like double-dot sequence. |
| MQTT port | Integer from 1 through 65535. |
| Robot ID | Matches [A-Za-z0-9][A-Za-z0-9._-]{0,63}. |
| Outbox owner fingerprint | Derived from host, port, and robot ID; binds pending results to the endpoint that owns them. |

The runtime endpoint does not require a rebuild. Saving a valid selection
causes the single-active broker to apply the endpoint and reconnect when the
service is active. A pending command, media, or care interaction outbox
blocks switching to an unrelated endpoint or disabling MQTT. The requested
endpoint must match the pending outbox owner fingerprint before Android can
flush the pending result.

MQTT credentials, if required by the private deployment, are not specified by
the Android source-derived contract in this repository and must not be
written into tracked files or this document.

The runtime endpoint and outbox state are stored in app-private
SharedPreferences. This persistence is distinct from BuildConfig and from
the broker's session storage. A runtime endpoint change is an operational
device action, not a source change.

## TRACKED_PROJECT_ASSET

The following project-produced assets are tracked source resources and are
included in the intended public repository:

- app/src/main/res/raw/elderly_hand_exercise.mp4;
- app/src/main/res/raw/elderly_leg_exercise.mp4.

Fresh clones must contain both resources. The generic `play_media` command
accepts their fixed media IDs only. If a generated or installed build is
missing a resource, Android retains the defensive
`media_unavailable:<media_id>` result; Android does not accept an arbitrary
path, URL, or content URI through the command contract.

## PRIVATE_OR_GENERATED_ASSET

The following assets remain outside the public repository:

- the private demo keystore and certificate material;
- any generated APK/AAB or capture artifacts.

Private or generated assets remain outside the tracked publication set even
when a build variant can reference them.

## EXTERNAL_SERVICE_CONFIG

The Android app depends on external service values that are deployment-owned:

| Service | Android input | Source-derived boundary |
| --- | --- | --- |
| MQTT broker | Runtime host and port, plus private deployment authentication if required | The app constructs per-robot topics and uses one active endpoint. This document does not select or publish a broker. |
| Backend camera stream | Build-time ws.server.urls | MainActivity sends encoded camera frames to configured WebSocket clients. The Android source does not define backend command behavior on that connection. |
| Temi platform | Installed robot and com.robotemi:sdk:1.134.1 | Robot callbacks and motion/TTS operations depend on the installed Temi SDK and device. |

The source-derived Android contract does not define Hermes, Bridge, LM Studio,
AI6, MQTT broker ACLs, backend routing, backend storage, or the full
Android/AI6 contract. Owner-provided bounded Android-to-AI6 evidence is
recorded in [HANDOVER_READINESS.md](../handover/HANDOVER_READINESS.md);
`FULL_ANDROID_AI6_COMPATIBILITY = NOT_VERIFIED`.

## Rebuild versus runtime change

| Change | Rebuild required | Runtime effect |
| --- | --- | --- |
| WebSocket URL list | Yes | New Activity instances use the generated BuildConfig list. |
| Resident identity, care report, or legacy MQTT feature flag | Yes | MQTT topic subscriptions and UI ownership change in the installed APK. |
| Media v1.1 enabled/deadline | Yes | The selected variant's media runtime behavior changes. |
| MQTT host, port, or robot ID | No | Device runtime settings apply the one active endpoint and its topic set. |
| MQTT outbox owner state | No | Existing pending results remain bound to their recorded endpoint. |
| Project-owned exercise video | Yes, when packaging or changing the resource in an APK | The corresponding fixed media ID resolves from the tracked raw resource; a missing packaged resource remains a defensive failure. |
| Demo signing inputs | Yes | The build signs the demo artifact or fails closed. |
| Android SDK path | No source rebuild by itself; required for Gradle execution | Gradle resolves the local SDK through sdk.dir. |

## Verification boundary

The build and configuration behavior is corroborated by the source and by
the MQTT topic, endpoint, media, and service tests. This document does not
claim that a private machine-local configuration is present, valid, or
accepted by a live broker, backend, Temi device, or the full AI6 implementation.
The bounded Android-to-AI6 evidence is recorded in the handover register.
