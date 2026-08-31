# Android Canonical Command Contract

## Scope and labels

This document specifies the command request and result behavior implemented by
the Android app. The generic request contract is schema 1.0. The media
runtime also implements a separate schema 1.1 command shape. The source of
truth is CanonicalCommandValidator, CanonicalCommandIngress, CommandLedger,
CanonicalCommandRuntime, MainActivity, MqttLifecycleService, and the related
unit tests.

- SOURCE_CONTRACT: implemented in Android source.
- BUILD_TIME_OPTION: selected by the Gradle variant or a local build property.
- RUNTIME_OPTION: selected by device runtime settings.
- DEVICE_SIDE_EFFECT: can cause speech, motion, wakeup, or media playback.
- EXTERNAL_DEPENDENCY: requires Temi SDK, MQTT, or a local media resource.
- NOT_E2E_VERIFIED: source/test-derived behavior not claimed as live robot or
  backend acceptance.

The contract does not define AI6 implementation behavior. Owner-provided
bounded Android-to-AI6 evidence is recorded in
[HANDOVER_READINESS.md](../handover/HANDOVER_READINESS.md); full
Android/AI6 compatibility remains `NOT_VERIFIED`.

## Generic request schema 1.0

Every canonical request must be a JSON object containing these envelope
fields:

~~~json
{
  "schema_version": "1.0",
  "command_id": "<command identifier>",
  "event_id": "<event identifier>",
  "robot_id": "<configured robot identifier>",
  "actions": [
    {
      "action_id": "<action identifier>",
      "type": "speak",
      "text": "<speech text>"
    }
  ]
}
~~~

The placeholders are documentation notation only.

| Field | Required source rule | Failure reason |
| --- | --- | --- |
| schema_version | String exactly 1.0 | unsupported_schema_version |
| command_id | String, trimmed, non-empty | missing_command_id |
| event_id | String, trimmed, non-empty | missing_event_id |
| robot_id | String, trimmed, non-empty and equal to the configured endpoint robot identifier | missing_robot_id or robot_id_mismatch |
| actions | Non-empty JSON array with at most five elements | missing_actions or too_many_actions |

The validator parses JSON with Gson. A malformed JSON string produces
malformed_json; a valid JSON value that is not an object produces
command_not_object. An array element that is not an object produces
action_not_object.

The validator requires the fields above but does not reject unknown envelope
or action properties. Unknown properties are not part of the documented
extension surface and must not be used to introduce a new command behavior.

## Action contract

Each action requires an action_id that is a trimmed, non-empty string. The
type must be one of speak, ask_clarification, turn, navigate, stop, noop, or
play_media. Actions execute sequentially in array order.

| Type | Required fields | Optional fields and defaults | Android execution and side effect |
| --- | --- | --- | --- |
| speak | text: trimmed, non-empty, at most 500 Java characters | language defaults to zh-TW when omitted; continue_listening defaults to false | TTS through the service-owned canonical runtime and Temi Robot.speak. Completion is based on the TTS callback. |
| ask_clarification | text: trimmed, non-empty, at most 500 Java characters | language defaults to zh-TW when omitted; continue_listening defaults to true | TTS through the canonical runtime. A true continue_listening value hands control back to the accepted ASR path after speech. |
| turn | direction: left or right; degrees: integer 15, 30, 45, 60, or 90 | none | Calls Robot.turnBy with left as positive and right as negative degrees at speed 0.6. The Android result is dispatched, not physical-arrival confirmation. |
| navigate | target: home_base, kitchen, living_room, or meeting_room | none | Calls Robot.goTo. The Android result is dispatched, not navigation-arrival confirmation. |
| stop | none | none | Cancels Temi TTS requests, stops movement, and hides the subtitle. The local action result is completed after the local stop operation. |
| noop | reason: trimmed, non-empty | none | Records a no-op without a hardware side effect. The local action result is completed. |
| play_media | media_id: elderly_hand_exercise or elderly_leg_exercise | none | Resolves one of the two tracked project-owned raw resources and uses the media controller. A missing packaged resource still fails with media_unavailable:<media_id>. |

The validator trims string fields before validation. It measures speech length
with Java String.length, not Unicode code points. The optional language field
must be a JSON string when present; the validator does not apply a language
allowlist. An empty string is retained as the supplied language value. The
canonical action model defaults the language only when the field is absent or
not returned as a string by the optional-string parser; a non-string language
field is rejected with invalid_action_language.

The validator rejects:

| Condition | Failure reason |
| --- | --- |
| Missing or blank action_id | missing_action_id |
| Unsupported type | unsupported_action_type |
| Missing or blank speech text | missing_action_text |
| Speech text longer than 500 Java characters | action_text_too_long |
| Non-boolean continue_listening | invalid_continue_listening |
| Missing or disallowed turn direction | invalid_turn_direction |
| Missing, non-integral, or disallowed turn degrees | invalid_turn_degrees |
| Missing or disallowed navigation target | navigation_target_not_allowed |
| Missing media_id | missing_media_id |
| Blank or non-string media_id | invalid_media_id |
| Media ID outside the allowlist | media_id_not_allowed |
| Missing or blank noop reason | missing_noop_reason |

The generic command validator does not accept arbitrary media paths, URLs,
content URIs, or resource names. It accepts media IDs only.

## Ingress and validation sequence

The MQTT service applies the message-size, feature, and retained-message gates
before routing a command. For a canonical command request, the Android app
then:

1. validates schema, correlation, robot identity, and every action;
2. computes the raw-payload digest and consults the durable command ledger;
3. suppresses a pending duplicate, replays a cached terminal result, or
   rejects a payload conflict;
4. records a first delivery before dispatching the action;
5. executes one action at a time and terminalizes the command result;
6. persists the terminal result before MQTT publication; and
7. retains the result in the outbox until a compatible connection publishes it.

The service directly consumes a canonical request only when it contains
exactly one speak action. Other valid canonical commands are observed by the
Activity command queue. A service or Activity path that cannot consume a
message does not bypass validation; the Activity applies the same generic
validator before physical execution.

When command_id and event_id are available in a validation failure, the
Activity can publish a failed correlated result. Invalid JSON or missing
correlation cannot produce a fully correlated result. A robot mismatch is
rejected before any action dispatch.

## Result and correlation contract

The generic result is published to the configured per-robot command result
topic with QoS 1 and retained=false. A normal Activity result has this shape:

~~~json
{
  "schema_version": "1.0",
  "command_id": "<request command_id>",
  "event_id": "<request event_id>",
  "robot_id": "<active robot identifier>",
  "status": "success",
  "finished_at_ms": 0,
  "results": [
    {
      "action_id": "<request action_id>",
      "type": "navigate",
      "status": "dispatched"
    }
  ]
}
~~~

The zero timestamp above is a shape placeholder, not a protocol default.
Android writes the current wall-clock milliseconds when it terminalizes the
result. The generic request has no issued_at, expires_at, timestamp, sequence,
or protocol TTL field.

Action result statuses are completed, failed, cancelled, and dispatched.
Overall Activity statuses are success, failed, partial_success, and
cancelled. The Activity derives the overall status from all action results:
all cancelled gives cancelled; all failed gives failed; a mixture of failed
or cancelled with another outcome gives partial_success; otherwise the result
is success.

The service's single-action TTS result uses completed or failed action status
and success or failed overall status. TTS callback error is represented by
tts_error. A callback that does not arrive before the local 30-second
canonical TTS timeout produces tts_callback_timeout. The runtime ignores
duplicate or stale callbacks after terminalization.

Validation and ledger failure paths can add a top-level error such as
command_id_payload_conflict, command_registry_capacity_exhausted, or
command_store_unavailable. A normal completed Activity result does not require
that top-level field.

The durable ledger stores command_id, event_id as request correlation,
robot_id, a payload digest, bounded action summaries, state, terminal state,
result state, and cached result payload. It does not persist speech or
resident text.

## Duplicate, replay, and recovery semantics

The command ledger uses up to 1,024 records. For an existing command_id:

- a different raw-payload digest is PAYLOAD_CONFLICT;
- a terminal or result-pending record is DUPLICATE_CACHED_RESULT;
- a non-terminal record is DUPLICATE_PENDING.

The ledger persists a terminal result before publication. Result state changes
from PENDING to DELIVERED only after a successful non-retained MQTT publish.
Reconnect flushes pending results one at a time when endpoint ownership allows
it.

Process-death recovery is local ledger recovery, not protocol freshness:

- a pending result is replayable from its cached payload;
- a RECEIVED noop-only or stop-only command is SAFE_RETRY;
- a RECEIVED command with another action is UNSAFE_RETRY;
- a stale RECEIVED command expires after the local five-minute pending age;
- an EXECUTING command is EXECUTION_UNKNOWN.

The recovery path does not replay speech or ambiguous physical execution.
Unsafe, unknown, or expired records receive a persisted failed result with the
source-defined recovery error. A stop recovery also cancels local TTS and
movement. No generic command field supplies a remote TTL.

Retained MQTT delivery never starts a canonical or legacy device-side action.
Cached result replay and retained-message rejection are separate rules.

## Media schema 1.1

The media runtime recognizes a message as media v1.1 when the payload declares
schema_version 1.1 or message_type video.command. The service parses this
shape before generic schema 1.0 validation. The feature is build-time enabled
only by the demo variant; debug and release default it off.

The media command has an exact top-level key set:

~~~text
schema_version, message_type, command_id, request_id, event_id, robot_id,
resident_id, action, execution_class, target_playback_session_id, video_id,
parameters, source, timestamp
~~~

The parser requires schema 1.1, message_type video.command,
command_id=request_id, the expected robot_id, a non-empty resident_id, and a
non-empty timestamp. Allowed actions are play_video, pause_video,
resume_video, and stop_video. play_video requires execution_class
serialized_execution and a JSON-null target playback session. The control
actions require execution_class active_playback_control and a non-empty target
playback session. The video ID allowlist is elderly_hand_exercise and
elderly_leg_exercise. parameters must be an empty object. source must be
hermes_temi_bridge, temi_app_manual, or remote_operator.

The media result is schema 1.1 with message_type video.command_result and the
following 21 fields:

~~~text
schema_version, message_type, command_id, request_id, event_id, robot_id,
command_action, video_id, status, terminal, playback_session_id,
target_playback_session_id, active_playback_session_id,
cancelled_by_command_id, playback_state, cancel_reason, actor,
result_delivery, error_code, error_message, timestamp
~~~

Media statuses are accepted, started, completed, failed, succeeded, cancelled,
and rejected. accepted and started are non-terminal; the other statuses are
terminal. The result preserves command_id, request_id, event_id, robot_id,
command action, and video ID. It records session linkage, playback state,
cancellation linkage, actor, delivery classification, and nullable error
fields according to the factory methods in MediaV11Result.

The media runtime allows one active playback session, waits for a transient
Activity binding up to the configured attach deadline, and uses a 30-second
dispatch deadline. A process restart reconciles ambiguous playback as
app_process_restart and does not replay the physical play command. Media
terminal results use the same non-retained command result topic and an
endpoint-bound durable outbox.

## Verification boundary

CanonicalCommandValidatorTest, CommandLedgerTest, CanonicalCommandRuntimeTest,
MqttLifecycleServiceIngressTest, the media parser/result/conformance tests,
and the media service/coordinator tests corroborate the schema, allowlists,
limits, result state transitions, retained ingress rejection, duplicate
handling, callback timeout, process-death recovery, and no-replay behavior.

This document is source-derived and does not claim live MQTT, backend, AI6, or
physical robot end-to-end acceptance. The bounded camera-stream evidence in the
handover register does not promote this command contract or any individual
command to full Android/AI6 acceptance.
