# Verified Features

This matrix records the strongest evidence available for the accepted Android
baseline. A source or unit result is not promoted to device or end-to-end
acceptance. No row claims `VERIFIED_E2E` because the final AI6 contract and
full-stack round-trip evidence are still **NOT_VERIFIED**.

Statuses in the matrix are limited to:
`VERIFIED_SOURCE`, `VERIFIED_UNIT`, `VERIFIED_BUILD`, `VERIFIED_DEVICE`,
`VERIFIED_E2E`, `NOT_VERIFIED`, `DISABLED`, `EXPERIMENTAL`, and
`EXTERNAL_DEPENDENCY`.

| Feature | Status | Evidence level | Important limitation |
| --- | --- | --- | --- |
| Android app build | `VERIFIED_BUILD` | A1 `assembleDebug` passed on the accepted public source baseline; V4E `:app:assembleDebug` passed for the 1.0.5/code6 candidate. | A desktop build is not Temi device acceptance. |
| JVM tests | `VERIFIED_UNIT` | V4E `:app:testDebugUnitTest`: `294/294` passed; the A1 fresh-clone baseline was `288/288`. | JVM contracts do not prove hardware, broker, or AI6 behavior. |
| Demo signing | `VERIFIED_BUILD` | V4E signed `assembleDemo`, `verifyDemoSigningConfig`, and Demo artifact preflight passed; expected signer digest is recorded in [CURRENT_STATUS.md](./CURRENT_STATUS.md). | Private signing assets are out of band; no bit-for-bit APK reproducibility claim. |
| Demo non-debuggable boundary | `VERIFIED_BUILD` | V4E packaged Demo preflight reported `debuggable=false`. | Debug builds remain development builds and are not the accepted Demo artifact. |
| Fresh-clone rebuild | `VERIFIED_BUILD` | A1 fresh clone rebuilt tests, debug APK, signed Demo APK, and Demo preflight successfully. | Rebuild readiness is not a reproducible-binary or full device-lifecycle claim. |
| Forward upgrade | `VERIFIED_DEVICE` | Accepted physical upgrade from `versionCode 2` to `3` using one normal `adb install -r`; identity/data directory/install time were preserved. | One bounded accepted upgrade path; uninstall/reinstall, rollback, and broad lifecycle coverage are not established here. |
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
| Canonical `play_media` | `VERIFIED_SOURCE` | Validator allowlist, canonical media tracking, fixed project-owned resources, and `MainActivity` playback path are present; Media v1.1 parser/runtime tests cover the separate media contract. | Physical playback still depends on a Temi device binding and is not currently verified. |
| Project-owned exercise media | `VERIFIED_BUILD` | Both tracked MP4s remain present in the V4E debug and Demo APKs; AAPT confirms both fixed raw IDs, Demo artifact preflight passes with Media v1.1 enabled, and `294/294` JVM tests pass. | Physical playback is not `VERIFIED_DEVICE`; V4F is required for device acceptance. |
| Temi top safe-area policy | `VERIFIED_BUILD` | V4E adds a centralized Temi top inset policy of `max(0.09 * windowHeight, 72dp)`, combines it with system-bar and cutout insets, applies it to `appContent`, and passes six deterministic policy tests plus debug and Demo builds. | V4D observed a 98 px Temi-owned top overlay; physical coordinate-tap acceptance remains pending V4F. |
| Camera capture | `VERIFIED_SOURCE` | `CameraManager`, `H264Encoder`, `Yuv420PlaneCopier`, and YUV unit tests are present. | Current B1 evidence does not accept physical camera capture or sustained thermal streaming. |
| WebSocket streaming | `EXTERNAL_DEPENDENCY` | `WebSocketClient` and lifecycle unit tests are present. | Requires an operator-configured external WebSocket service; current camera-to-service E2E is `NOT_VERIFIED`. |
| Resident Identity | `DISABLED` | Accepted Demo has `RESIDENT_IDENTITY_ENABLED=false`; parser/state/gate source and JVM tests remain in the tree. | External identity producer and resident-specific device E2E are not verified. |
| Care Report | `DISABLED` | Accepted Demo has `CARE_REPORT_ENABLED=false`; parser/state/routing/persistence source and JVM tests remain in the tree. | External care contract and care UI/device E2E are not verified. |
| Android backup exclusion | `VERIFIED_SOURCE` | Manifest sets `allowBackup=false`; backup and data-extraction rules exclude app domains. | No operating-system restore/transfer runtime test is claimed. |
| Final Android/AI6 full-stack contract | `NOT_VERIFIED` | The accepted evidence stops at Android-side lifecycle/connection readiness. | Final AI6 contract is `PENDING_AI6_FINAL_CONTRACT`; backend lifecycle is an `EXTERNAL_DEPENDENCY`. |

## Interpretation

`VERIFIED_SOURCE` means the current source contains the stated boundary.
`VERIFIED_UNIT` means the stated behavior is covered by JVM tests.
`VERIFIED_BUILD` means a build or packaged-artifact check passed.
`VERIFIED_DEVICE` means the stated narrow behavior was observed on a physical
Temi or Android target. `VERIFIED_E2E` is reserved for a complete accepted
multi-system path and is intentionally unused here.

`NOT_VERIFIED` is an explicit evidence result, not a statement that the source
cannot work. `DISABLED` describes the accepted Demo build state. `EXPERIMENTAL`
describes an optional path without current deployment acceptance.
`EXTERNAL_DEPENDENCY` identifies behavior owned by a system outside this
repository. Historical evidence is not silently promoted into any of these
statuses.
