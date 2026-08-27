# Android MQTT Interface Contract

## Scope and evidence

This document describes the MQTT interface implemented by the Android app. The
contract is derived from MqttTopicSet, MqttIngressPolicy,
MqttLifecycleService, MqttManager, the endpoint and outbox classes, and their
tests. It describes Android behavior only. It does not define broker,
backend, Hermes, Bridge, or AI6 behavior.

Labels used in this document:

- SOURCE_CONTRACT: behavior implemented in the Android source.
- BUILD_TIME_OPTION: behavior selected by the Gradle build configuration.
- RUNTIME_OPTION: behavior selected by persisted device settings.
- DEVICE_SIDE_EFFECT: an ingress path can make the robot speak, move, wake,
  or play media.
- EXTERNAL_DEPENDENCY: behavior depends on the MQTT broker or another
  service.
- NOT_E2E_VERIFIED: source-derived behavior that this documentation task
  does not claim to have verified against a live broker or robot.

The Android-to-backend compatibility review remains
AI6_COMPATIBILITY_PENDING_FINAL_REVIEW.

## Endpoint and connection scope

The app accepts one active MQTT endpoint at a time. An endpoint consists of a
host, a port, and a robot identifier. The source rejects an endpoint with a
scheme, slash, at-sign, comma, or path-like double-dot sequence; it restricts
the port to 1 through 65535 and the robot identifier to
[A-Za-z0-9][A-Za-z0-9._-]{0,63}. The app normalizes the host to lower case
and derives an endpoint fingerprint from the host, port, and robot identifier.

The Android app constructs per-robot topics from the configured robot
identifier:

~~~text
temi/<robotId>/cmd/request
temi/<robotId>/cmd/result
temi/<robotId>/resident/identity/result
temi/<robotId>/care/report
temi/<robotId>/care/report/interaction/result
~~~

The placeholders above are notation, not deployment values. This document
does not publish a host, IP address, port assignment, credential, or broker
endpoint.

MqttLifecycleService owns the long-lived broker connection. The service is a
foreground, sticky service and is not stopped with the Activity task. The
Activity binds as an observer and does not own the MQTT connection lifecycle.
The single-active broker rejects stale callbacks from a replaced connection.

## Topic matrix

| Topic | Android direction | Subscription state | QoS | Retain policy | Android behavior |
| --- | --- | --- | --- | --- | --- |
| temi/<robotId>/cmd/request | broker to app | Always subscribed after a valid endpoint is applied | 1 | Inbound retained delivery rejected | Validates canonical 1.0 commands or declared media 1.1 commands before a device-side effect. |
| temi/<robotId>/cmd/result | app to broker | Publish-only | 1 | Android publishes non-retained | Publishes terminal command results from the durable ledger/outbox. |
| temi/<robotId>/resident/identity/result | broker to app | Subscribed when resident.identity.enabled=true or care.report.enabled=true | 1 | Transport may deliver retained; identity parsing/state rules still apply | Parses a strict resident identity result and updates the process-local identity state. |
| temi/<robotId>/care/report | broker to app | Subscribed only when care.report.enabled=true | 1 | Retained report rejected by the care state holder | Parses and displays a report only for the current authorized resident identity. |
| temi/<robotId>/care/report/interaction/result | app to broker | Publish-only when care reporting is enabled | 1 | Android publishes non-retained | Publishes metadata-only viewed or acknowledged interaction results from the endpoint-bound outbox. |
| temi/action/speak | broker to app | Subscribed only when legacy.mqtt.actions.enabled=true; default disabled | 1 | Inbound retained delivery rejected | Legacy TTS ingress. The Activity handles the raw legacy payload only after the service policy gate. |
| temi/action/navigate | broker to app | Subscribed only when legacy.mqtt.actions.enabled=true; default disabled | 1 | Inbound retained delivery rejected | Legacy navigation ingress. The Activity issues Robot.goTo and reports no arrival observation. |
| temi/action/wakeup | broker to app | Subscribed only when legacy.mqtt.actions.enabled=true; default disabled | 1 | Inbound retained delivery rejected | Legacy wakeup ingress. The Activity opens the Temi ASR path. |
| temi/event/asr | app to broker | Publish-only when the MQTT connection is available | 1 | Android publishes non-retained | Legacy ASR event emitted after the custom ASR acceptance gate. It is not an Android command subscription. |

The three legacy topics are global topic constants, not per-robot topic
variants. The Android source does not subscribe to the two result topics in
the topic matrix; publication is explicit and owned by the app.

### Feature implications

MqttTopicSet always includes the command request topic. Enabling the care
report feature also enables the resident identity subscription because care
authorization requires identity state. The identity feature alone does not
enable care reports. The legacy action set remains excluded unless the
explicit build flag is true.

resident.identity.enabled, care.report.enabled, and
legacy.mqtt.actions.enabled are build-time flags read from local machine
properties. They are not MQTT messages and changing them requires a rebuild
and reinstall of the affected variant.

## Ingress gates

The service applies the gates in this order:

1. It measures the UTF-8 payload size. Payloads above 64 KiB are rejected
   before parsing and before the retained-message decision.
2. It rejects disabled legacy topics.
3. It rejects retained delivery on every side-effecting topic: canonical
   command requests and the three legacy action topics.
4. It routes canonical or media command requests to the service runtime and
   forwards other accepted messages to the Activity observer.

The retained-message gate protects device-side effects from replayed broker
state. Identity messages can pass the generic MQTT gate, but the identity
parser and state holder still validate the message. Care reports are rejected
by the care state holder when their retained flag is true.

The service holds unconsumed messages while no Activity observer is attached.
The detached FIFO is bounded to 256 messages and 1 MiB of UTF-8 payload bytes.
The service evicts the oldest buffered messages to stay within both bounds.
Media 1.1 commands are consumed by the service runtime even while the
Activity is detached.

## MQTT session and reconnect behavior

MqttManager uses Paho MQTT v3 with in-memory client persistence. The source
sets:

- cleanSession=false;
- a 10-second connection timeout;
- a 30-second keep-alive interval;
- automaticReconnect=false;
- QoS 1 for subscriptions and publications.

The Android app owns reconnect scheduling. The reconnect delay starts at 1
second, doubles per bounded attempt, and caps at 30 seconds. The connection
re-subscribes to the current topic set after reconnect. The single-active
broker keeps only one endpoint connection and rejects stale callbacks from an
old connection.

Paho MemoryPersistence is not a durable client-side message store. Android
durability comes from the command ledger and the media/care outboxes stored in
the app's private SharedPreferences. The app records a terminal result before
attempting publication. A failed or disconnected publication remains pending
and is retried after a compatible reconnect. A pending outbox blocks switching
to an unrelated endpoint or disabling the endpoint; the endpoint owner
fingerprint must match before a pending result can flush.

These rules provide source-level durable result delivery and duplicate
suppression. They do not establish broker persistence, exactly-once delivery,
protocol freshness, or end-to-end delivery to a backend.

## Publication and result rules

Android MQTT publications use QoS 1 and retained=false. The default
connection publish path rejects a request to publish a retained message. The
command result topic carries:

- canonical command results with schema 1.0; and
- media 1.1 command results with schema 1.1 when the demo media feature is
  enabled.

The command ledger recognizes duplicate payloads by command_id and payload
digest. It can replay a cached terminal result, suppress a pending duplicate,
or reject a payload conflict. Duplicate and replay behavior is an
idempotency/durable-delivery rule; it is not a protocol TTL or a freshness
check. The generic canonical command schema has no issued_at, expires_at,
sequence, or protocol timestamp field.

The care interaction outbox stores metadata only and publishes to the
per-robot interaction result topic. It does not persist report bodies or
resident speech/text.

## Limits and diagnostics

| Resource | Source limit |
| --- | --- |
| Inbound MQTT payload | 64 KiB measured as UTF-8 bytes |
| Detached Activity message buffer | 256 messages |
| Detached Activity buffer payload bytes | 1 MiB |
| Durable command ledger records | 1,024 records |
| Care interaction coordinator records | 128 records |
| Media 1.1 command records | 256 records |

Default MQTT and canonical diagnostics redact endpoint values, payloads,
speech, credentials, and full identifiers. Documentation and logs must retain
the same boundary.

## Verification boundary

MqttTopicSetTest, MqttLifecycleServiceIngressTest, MqttManagerReconnectTest,
SingleActiveMqttBrokerTest, and the endpoint/switch-policy tests corroborate
the topic flags, QoS/session settings, retained ingress gates, payload and
buffer limits, legacy isolation, duplicate handling, and endpoint ownership
rules in the source tree.

This document is source-derived. It does not claim live broker, backend, AI6,
or robot acceptance. AI6_COMPATIBILITY_PENDING_FINAL_REVIEW.
