# Verified Features

This matrix records the strongest evidence available for the accepted Android
baseline. A source or unit result is not promoted to device or end-to-end
acceptance. `VERIFIED_E2E` is used only for the specifically bounded
Temi-to-AI6 camera-stream path described below; it does not mean universal
Android/AI6 compatibility.

Statuses in the matrix are limited to:
`VERIFIED_SOURCE`, `VERIFIED_UNIT`, `VERIFIED_BUILD`, `VERIFIED_DEVICE`,
`VERIFIED_E2E`, `NOT_VERIFIED`, `DISABLED`, `EXPERIMENTAL`, and
`EXTERNAL_DEPENDENCY`.

| Feature | Status | Evidence level | Important limitation |
| --- | --- | --- | --- |
| Android app build | `VERIFIED_BUILD` | A1 `assembleDebug` passed on the accepted public source baseline; V4E `:app:assembleDebug` passed for the 1.0.5/code6 candidate. | A desktop build is not Temi device acceptance. |
| JVM tests | `VERIFIED_UNIT` | Current accepted result: `:app:testDebugUnitTest` `294/294` passed. The earlier A1 fresh-clone result was `288/288`. | JVM contracts do not prove hardware, broker, or AI6 behavior. |
| Demo signing | `VERIFIED_BUILD` | V4E signed `assembleDemo`, `verifyDemoSigningConfig`, and Demo artifact preflight passed; expected signer digest is recorded in [CURRENT_STATUS.md](./CURRENT_STATUS.md). | Private signing assets are out of band; no bit-for-bit APK reproducibility claim. |
| Demo non-debuggable boundary | `VERIFIED_BUILD` | V4E packaged Demo preflight reported `debuggable=false`. | Debug builds remain development builds and are not the accepted Demo artifact. |
| Fresh-clone rebuild | `VERIFIED_BUILD` | G3A rebuilt the current `main` source in a fresh clone: JVM tests, debug APK, signed Demo APK, and Demo preflight passed. | Rebuild readiness is not a reproducible-binary or full device-lifecycle claim. |
| Forward upgrade | `VERIFIED_DEVICE` | Current accepted physical upgrade from `versionCode 5 / 1.0.4` to `versionCode 6 / 1.0.5` used one normal `adb install -r`; `userId`, `dataDir`, `firstInstallTime`, and signer were preserved. The earlier `versionCode 2` to `3` upgrade remains historical. | One bounded accepted upgrade path; uninstall/reinstall, rollback, and broad lifecycle coverage are not established here. |
| Minimal launch | `VERIFIED_DEVICE` | `MainActivity` launch passed with no immediate fatal application exception. | Short launch smoke only; it does not establish feature completion. |
| MQTT lifecycle | `VERIFIED_DEVICE` | `MqttLifecycleService` source plus accepted service start, persisted endpoint usability after upgrade, and no observed crash loop. | The broker and backend lifecycle are `EXTERNAL_DEPENDENCY`. |
| MQTT connection/reconnect | `VERIFIED_SOURCE` | `MqttManagerReconnectTest`, `MqttReconnectPolicyTest`, and `SingleActiveMqttBrokerTest`; accepted device evidence covers connection attempts and CONNACK/`CONNECTED`. | Physical reconnect and final broker/backend contract are not verified. |
| Canonical command validation | `VERIFIED_UNIT` | `CanonicalCommandValidatorTest` covers schema, correlation, robot identity, action allowlists, and malformed inputs. | Producer compatibility and physical execution remain outside this unit evidence. |
| Retained side-effect rejection | `VERIFIED_UNIT` | `MqttIngressPolicy` and `MqttLifecycleServiceIngressTest` reject retained canonical and enabled legacy side-effecting deliveries before forwarding or execution. | No broker-side retained-message or full-stack acceptance is claimed. |
| Inbound resource bounds | `VERIFIED_UNIT` | Ingress tests cover the 64 KiB UTF-8 payload gate and the 256-message/1 MiB detached buffer with eviction and byte accounting. | This is bounded contract testing, not a Temi load or resource-stress run. |
| Replay/idempotency | `VERIFIED_UNIT` | `CommandLedgerTest`, service ingress tests, and Media v1.1 tests cover duplicate suppression, payload conflict, cached result replay, and single terminalization. | Physical process-restart and backend retry E2E are not accepted here. |
| Legacy MQTT action isolation | `VERIFIED_UNIT` | Topic/service tests verify global legacy speak/navigate/wakeup topics are disabled by default and gated when explicitly enabled. | A controlled deployment can opt in; no production legacy-topic E2E is claimed. |
| `noop` | `VERIFIED_SOURCE` | Validator allowlist and `MainActivity` immediate-action path implement a completed no-op result; ledger tests exercise no-op records. | No final canonical `noop` round trip is accepted; it is `NOT_VERIFIED` end to end. |
| `speak` | `VERIFIED_UNIT` | Validator, canonical ingress, service lifecycle, and TTS callback/terminal-result tests cover the path. | No physical Temi speech or final AI6 command round trip is claimed. |
| `ask_clarification` | `VERIFIED_SOURCE` | Validator test covers its default continue-listening semantics; canonical speech path handles the action. | No device or full-stack acceptance is recorded. |
| `turn` | `VERIFIED_SOURCE` | Validator restricts direction/degrees and `MainActivity` dispatches the signed turn request. | Dispatch is not physical completion; no current device E2E is verified. |
| `navigate` | `VERIFIED_SOURCE` | Validator restricts navigation targets and `MainActivity` dispatches the Temi request. | Arrival is not observed by the Android result contract; no current device E2E is verified. |
| `stop` | `VERIFIED_SOURCE` | Validator allowlist and source path cancel TTS, stop movement, and clear subtitle state. | No current physical stop acceptance is recorded. |
| Canonical `play_media` | `VERIFIED_SOURCE` | Validator allowlist, canonical media tracking, fixed project-owned resources, and `MainActivity` playback path are present; Media v1.1 parser/runtime tests cover the separate media contract. | The full canonical broker/AI6/Temi path is not verified; V4H `VERIFIED_DEVICE` evidence covers local `MainActivity` coordinate playback only. |
| Project-owned exercise media | `VERIFIED_DEVICE` | Both tracked MP4s remain present in the V4E debug and Demo APKs; AAPT confirms both fixed raw IDs, Demo artifact preflight passes with Media v1.1 enabled, and `294/294` JVM tests pass. V4H physically accepted one hand and one leg coordinate tap on installed versionCode 6 / versionName 1.0.5, with each video visible, advancing, and completing naturally. | Physical acceptance requires `MainActivity` to be resumed and focused; this is bounded local UI/media evidence, not full-stack AI6 or broker E2E. |
| Temi top safe-area policy | `VERIFIED_DEVICE` | V4D recorded the historical Temi-owned region `[0,0][1920,98]`. V4E adds `max(0.09 * windowHeight, 72dp)`, combines it with system-bar and cutout insets, applies it to `appContent`, and passes six deterministic policy tests plus debug and Demo builds. V4F physically confirmed HAND `[793,120][955,192]` and LEG `[955,120][1117,192]` outside that region; `TOP_SAFE_AREA_PHYSICAL_ACCEPTANCE = PASS`. | Physical coordinate acceptance requires a resumed/focused `MainActivity`. `StandbyActivity` can be the foreground owner and must be checked during diagnosis/operator readiness; V4H did not observe an autonomous takeover and does not claim that it always takes foreground. |
| Camera capture | `VERIFIED_E2E` | Bounded owner-provided Temi-to-AI6 evidence reached camera frame input and H.264 output on AI6 `main` HEAD `12aff3bfdfe526c17a25a2681aea2afad7112b33`; the AI6 demo lifecycle reported `DEMO_READY` and doctor `PASS`. | This status covers the recorded bounded stream path only; it does not establish sustained thermal operation or universal deployment compatibility. |
| WebSocket streaming | `VERIFIED_E2E` | Bounded owner-provided evidence covered Android WebSocket client creation/connection, Temi-to-AI6 connectivity, binary send, AI6 H.264 ingress, non-empty `VisionBuffer`, and viewer frames. | The endpoint is deployment-owned; this does not establish every command, every viewer deployment, or the full Android/AI6 contract. |
| Resident Identity | `DISABLED` | Accepted Demo has `RESIDENT_IDENTITY_ENABLED=false`; parser/state/gate source and JVM tests remain in the tree. | External identity producer and resident-specific device E2E are not verified. |
| Care Report | `DISABLED` | Accepted Demo has `CARE_REPORT_ENABLED=false`; parser/state/routing/persistence source and JVM tests remain in the tree. | External care contract and care UI/device E2E are not verified. |
| Android backup exclusion | `VERIFIED_SOURCE` | Manifest sets `allowBackup=false`; backup and data-extraction rules exclude app domains. | No operating-system restore/transfer runtime test is claimed. |
| Final Android/AI6 full-stack contract | `NOT_VERIFIED` | Bounded Android-to-AI6 connectivity and camera-stream evidence is recorded, but complete command-by-command acceptance is not. | `FULL_ANDROID_AI6_COMPATIBILITY = NOT_VERIFIED`; broader acceptance scope is `OWNER_DECISION_REQUIRED`. |

## Interpretation

`VERIFIED_SOURCE` means the current source contains the stated boundary.
`VERIFIED_UNIT` means the stated behavior is covered by JVM tests.
`VERIFIED_BUILD` means a build or packaged-artifact check passed.
`VERIFIED_DEVICE` means the stated narrow behavior was observed on a physical
Temi or Android target. `VERIFIED_E2E` means the named bounded multi-system
path was observed; it does not promote unrelated commands or the overall
Android/AI6 contract.

`NOT_VERIFIED` is an explicit evidence result, not a statement that the source
cannot work. `DISABLED` describes the accepted Demo build state. `EXPERIMENTAL`
describes an optional path without current deployment acceptance.
`EXTERNAL_DEPENDENCY` identifies behavior owned by a system outside this
repository. Historical evidence is not silently promoted into any of these
statuses.
