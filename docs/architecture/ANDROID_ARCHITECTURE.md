# Android Architecture

## Scope and evidence

This document records the current Android component boundaries derived from
MainActivity, MqttLifecycleService, the command, MQTT, media, identity, care,
camera, and WebSocket packages, AndroidManifest.xml, and their tests.

- SOURCE_CONTRACT: implemented source behavior.
- BUILD_TIME_OPTION: behavior selected when Gradle creates BuildConfig.
- RUNTIME_OPTION: behavior selected by device settings or lifecycle state.
- DEVICE_SIDE_EFFECT: speech, motion, wakeup, or media playback can occur.
- EXTERNAL_DEPENDENCY: behavior depends on Temi SDK, MQTT, WebSocket, or a
  packaged media resource.
- NOT_E2E_VERIFIED: source/test evidence that is not a claim of live robot,
  broker, backend, or AI6 acceptance.

The app is an Android-side agent. Hermes, the Bridge, LM Studio, AI6 services,
the MQTT broker, and backend storage are external systems. No component in
the Android app owns their implementation.

## Component diagram

~~~text
External systems
  MQTT broker <-> per-robot MQTT topics
  Camera WebSocket service <-> configured WebSocket URL
  Backend, Hermes, Bridge, LM Studio, and AI6 remain outside this app
       |                                  |
       v                                  v
Android app process
  MqttLifecycleService
    - foreground MQTT owner
    - ingress gates and detached buffer
    - service-owned canonical TTS runtime
    - media v1.1 runtime and result outbox
       |
       +--> MainActivity observer/binder
              - UI and lifecycle
              - Temi callbacks and voice state
              - canonical non-speak command queue
              - identity and care UI
              - CameraX -> H264 -> WebSocket clients
              - immediate navigation, turn, and stop dispatch
       |
       +--> pure policy and persistence components
              - topic and endpoint policies
              - canonical validator and ingress
              - command ledger and recovery policy
              - media parser/coordinator/result
              - identity and care parsers/state holders
~~~

The diagram separates the Android app process from external services. The
arrows identify transport or callback boundaries; they do not assert a
backend implementation.

## Ownership and lifecycle

| Component | Responsibility | Lifecycle | Inputs | Outputs | Failure boundary |
| --- | --- | --- | --- | --- | --- |
| MainActivity | Owns screen state, Temi listener registration, custom voice flow, camera, WebSocket clients, identity/care UI, and non-service canonical actions | `onCreate` constructs the UI, controller, WebSocket clients, and camera manager; `onStart` registers Temi listeners; `onRobotReady` plus permission approval invokes `startAllServices`; Activity-owned listeners, camera, and WebSocket connections stop in `onStop`, and WebSocket clients are shut down in `onDestroy` | User/UI events, Temi callbacks, MQTT messages forwarded by the service, build flags | UI updates, Temi SDK calls, camera frames, command results, ASR events | Activity recreation must not own or tear down the MQTT service; detached observer delivery is bounded. |
| MqttLifecycleService | Owns one long-lived MQTT broker connection, topic set, ingress size/retained/feature gates, detached message buffer, service TTS path, and result publication | Foreground sticky service; exported=false and stopWithTask=false; starts/binds from MainActivity | Runtime endpoint, MQTT callbacks, canonical/media payloads, TTS callbacks | Topic subscriptions, command/media result publications, observer callbacks, durable outbox state | Broker failure leaves pending results; service shutdown removes listeners and closes the broker. |
| MqttTopicSet and ingress policies | Construct per-robot topics and classify side-effecting/feature-gated ingress | Stateless or endpoint-scoped | Robot ID, build flags, topic and retained flag | Topic list and reject reasons | Invalid endpoint or disabled feature fails closed. |
| CanonicalCommandValidator and ingress | Validate generic schema 1.0, action allowlists, correlation, and robot match before dispatch | Stateless per request | JSON payload and expected robot ID | Canonical command or validation reason | Invalid input does not reach a device-side command executor. |
| CommandLedger and recovery policy | Persist idempotency, bounded action summaries, terminal result payloads, result delivery state, and local process-death recovery | App-private SharedPreferences; synchronous persistence | Command ID, raw-payload digest, execution/result transitions, current time | First/duplicate/conflict decisions, pending outbox, recovery classification | Store failure prevents execution; ambiguous physical execution is not replayed. |
| CanonicalCommandRuntime | Own one pending TTS correlation, callback timeout, terminalization order, and observer-safe completion | Process-owned; survives Activity observer changes but not process restart | Valid speak request, TTS callbacks, dispatch errors, timeout clock | TTS port calls and terminal command result | Missing callback fails with tts_callback_timeout; stale callbacks are ignored. |
| MediaV11ServiceRuntime and coordinator | Parse, serialize, bind, control, reconcile, and durably publish media v1.1 results | Process-owned runtime with transient Activity playback binding | Media v1.1 command, playback callbacks, attach/dispatch deadlines | Player operations, accepted/started/terminal media results, outbox state | Binding timeout, invalid session, missing resource, publish failure, or process restart produces a terminal error/reconciliation result without physical replay. |
| AgentStateMachine | Coordinates IDLE, WAKEUP_TRIGGERED, ASR_LISTENING, THINKING, WAITING, and EXECUTING | Owned by MainActivity while the Activity is alive | Wakeup, ASR, command, timeout, touch interruption | State transitions and state listener callbacks | Invalid or interrupted transitions return the local flow to IDLE and cancel active local work. |
| Temi callback adapters | Receive robot-ready, ASR, wakeup, NLP, and TTS callbacks | Registered in `onStart` and removed in `onStop`; `onRobotReady` may trigger `startAllServices` after permission checks | Temi SDK callbacks | MainActivity state changes, ASR publication, TTS result terminalization | Unsolicited wake/ASR callbacks are suppressed by the custom acceptance gate. |
| CameraManager and H264Encoder | Capture CameraX YUV_420_888 frames, encode AVC/H.264, and deliver binary access units | Created with Activity services; stopped on Activity stop | Camera permission, camera frames, encoder state | Timestamp-prefixed H.264 packets | Frame closure, encoder exception, or disconnect drops/restarts local stream work; it does not create MQTT commands. |
| WebSocketClient | Maintain each configured camera stream connection and send binary packets | Objects are constructed in `onCreate`; connections begin in Activity `startAllServices` after Temi readiness and permission approval; clients disconnect in `onStop` and shut down in `onDestroy` | BuildConfig URL and H.264 packets | Binary WebSocket frames and connection state | Disconnected sends return false/drop; reconnect uses bounded jittered delay. |
| ResidentIdentityStateHolder | Maintain current process-local authorized identity with ordering and TTL rules | Created for identity or care feature; not persisted across process death | Strict identity result payload and retained flag | Current identity for UI and care authorization | Invalid, stale, conflicting, or expired identity clears identity-dependent UI. |
| CareReportStateHolder and interaction coordinator | Validate resident-scoped care reports and publish metadata-only interactions | Created only when care reporting is enabled; process-local report receipt plus durable interaction outbox | Identity state, care report payloads, viewed/acknowledged UI actions | Report UI state and interaction result publications | Retained, wrong-resident, duplicate/conflicting, invalid, or capacity-exhausted inputs are rejected or clear state. |

### MainActivity lifecycle timing

`MainActivity.onCreate` inflates the layout, creates the local media controller,
loads device-local MQTT settings, starts and binds `MqttLifecycleService`,
constructs configured WebSocket clients, and creates the camera manager. It does
not start the Activity-owned camera or WebSocket connections at that point.

`MainActivity.onStart` registers Temi listeners and calls `Robot.onStart` with
the Activity metadata. `onResume` and a focused-window callback restore the
fullscreen surfaces. When the Temi readiness callback reports ready and the
camera/microphone permissions are available, `startAllServices` starts the
Activity-owned voice, WebSocket, and camera paths and asks the service-owned
broker to connect. `onStop` removes Temi listeners, stops the voice path,
disconnects WebSocket clients, and shuts down the camera. `onDestroy` shuts down
the WebSocket clients and unbinds the MQTT service; it does not own the
long-lived MQTT service shutdown.

Physical acceptance of Activity-owned UI, media, camera, or WebSocket behavior
requires `MainActivity` to be resumed and its window focused. `StandbyActivity`
can be the foreground owner, so operators must check the resumed activity, top
activity, and focused window before diagnosis or coordinate acceptance.

## Command and result flow

The Android command path is sequential and ledger-backed:

~~~text
MQTT cmd/request
  -> payload byte limit
  -> disabled-legacy and retained side-effect gates
  -> Media v1.1 parser, when declared and enabled
     or CanonicalCommandValidator for schema 1.0
  -> CommandLedger duplicate/conflict decision
  -> service TTS path for exactly one speak action
     or MainActivity canonical queue for other valid commands
  -> Temi SDK/media operation
  -> callback, dispatch, cancellation, or timeout result
  -> ledger persistence before non-retained MQTT cmd/result publish
  -> pending outbox retry after a compatible reconnect
~~~

The service consumes exactly one canonical speak action directly so the
Activity can be recreated without duplicating that TTS dispatch. Valid
multi-action commands and non-speak actions reach the Activity observer, which
executes actions in array order. The Activity does not report physical
arrival for navigation or turn; those operations report dispatched after the
local SDK call.

The command ledger recognizes a duplicate payload, a pending duplicate, or a
payload conflict by command ID and raw-payload digest. The ledger stores
bounded action summaries and correlation data, not speech or resident text.
The five-minute RECEIVED recovery age is a local process-death rule, not a
protocol TTL.

## Agent state machine

MainActivity coordinates these states:

~~~text
IDLE
  -> WAKEUP_TRIGGERED -> ASR_LISTENING -> THINKING -> WAITING
  -> EXECUTING
  -> IDLE

Global touch interruption:
  any active state -> IDLE
  and cancel local media, TTS, movement, and subtitle
~~~

MainActivity starts the custom hotword path in IDLE and stops it in other
states. A WAITING watchdog expires after 60 seconds and returns to IDLE with a
local timeout utterance. The custom ASR gate accepts only an Android-opened
Temi ASR handoff. An unsolicited Temi wake callback is suppressed instead of
opening the conversation.

## Temi operations and physical boundaries

MainActivity calls Temi SDK operations for TTS, navigation, turn, stop,
wakeup, conversation completion, and wakeup configuration. MqttLifecycleService
also calls Temi TTS for its service-owned single-speak path. TTS results require
a Temi callback or the local runtime timeout; navigation and turn results are
dispatch acknowledgements, not arrival or pose observations.

The stop operation cancels all Temi TTS requests, stops movement, and hides
the subtitle. Activity recreation, MQTT reconnect, duplicate suppression, and
ledger recovery do not replay ambiguous speech or movement. A recovered stop
can reapply the local stop behavior.

## Media boundary

Generic schema 1.0 play_media accepts only the two fixed media IDs and
resolves one of the two tracked project-owned raw resources. A missing resource
still returns media_unavailable:<media_id> as defensive handling.

Media schema 1.1 is handled by MediaV11ServiceRuntime. The runtime has one
active playback session, serialized play operations, active-session controls,
a transient Activity binding deadline, a dispatch deadline, and durable
terminal result publication. Process restart reconciles ambiguous playback
with app_process_restart and does not replay the physical play request.

## Camera and WebSocket boundary

CameraManager binds CameraX Preview and ImageAnalysis to the Activity
lifecycle. ImageAnalysis keeps only the latest frame and targets 1280x720.
H264Encoder uses a single encoder executor and hardware AVC MediaCodec.
Encoded packets contain an eight-byte big-endian wall-clock millisecond prefix
followed by H.264 data; codec configuration precedes keyframes when available.

MainActivity creates WebSocketClient instances from BuildConfig
WS_SERVER_URLS. WebSocketClient sends binary packets only while connected.
The camera stream has no inbound command role in the Android source.

## Identity and care boundary

Resident identity results use a strict schema 1.0 parser, process-local TTL and
ordering state, and a known-resident partition. Care reports use a strict
schema 1.0 parser, require the current authorized identity, reject retained
delivery, and clear state on a resident mismatch or identity loss. Care
interactions contain metadata and are persisted in an endpoint-bound outbox;
report bodies are not persisted.

These features are conditional build-time paths. Care reporting implies the
identity subscription and identity lifecycle, but identity alone does not
enable care reporting.

## External boundary and verification

The Android app boundary ends at:

- MQTT topic subscription/publication;
- configured WebSocket frame delivery;
- Temi SDK calls and callbacks;
- local packaged resources and app-private persistence.

The Android source does not contain Hermes, Bridge, LM Studio, or AI6
implementation logic. This architecture map does not establish external
service behavior or compatibility.

The component and lifecycle tests corroborate service ownership, bounded
buffering, validation before execution, durable result ordering, media binding
and restart behavior, state transitions, and the camera/WebSocket lifecycle.
Owner-provided bounded Android-to-AI6 evidence covers the camera-stream path and
is recorded in [HANDOVER_READINESS.md](../handover/HANDOVER_READINESS.md). No
statement in this document establishes full Android/AI6 compatibility;
`FULL_ANDROID_AI6_COMPATIBILITY = NOT_VERIFIED`.
