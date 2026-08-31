# Temi SDK Usage Map

## Scope and evidence

This map lists the Temi SDK types and calls that the Android source actually
uses. It is derived from MainActivity, MqttLifecycleService,
CanonicalSpeechPort, AndroidManifest.xml, and app/build.gradle. It does not
infer capabilities from historical documentation or from external services.

- SOURCE_CONTRACT: an import, manifest declaration, or call present in source.
- BUILD_TIME_OPTION: selected by the Gradle variant or local properties.
- DEVICE_SIDE_EFFECT: the call can affect the robot or its conversation UI.
- EXTERNAL_DEPENDENCY: the call depends on the installed Temi SDK/device.
- NOT_E2E_VERIFIED: source evidence that does not claim live-device
  acceptance.

The app declares com.robotemi:sdk:1.134.1. This document records that current
dependency; it does not recommend or perform an SDK upgrade.

## Usage map

| Source location | SDK class/interface/method | Purpose | Callback or result dependency | Physical side effect |
| --- | --- | --- | --- | --- |
| MainActivity implements clause | OnRobotReadyListener; Robot.AsrListener; Robot.WakeupWordListener; Robot.TtsListener; Robot.NlpListener | Receives Temi readiness, ASR, wakeup, TTS, and NLP callbacks | onRobotReady, onAsrResult, onWakeupWord, onTtsStatusChanged, and onNlpCompleted drive Activity state and command handling | Callback receipt alone has no physical side effect; callbacks can trigger later actions. |
| MainActivity.onCreate | Robot.getInstance() | Obtains the process-wide Temi Robot object | Readiness callback and registered listeners provide lifecycle entry | No immediate side effect. |
| MainActivity.onStart and onStop | Robot.addOnRobotReadyListener / removeOnRobotReadyListener | Registers and removes the Activity readiness listener | onRobotReady(boolean) controls permission checks and service startup | No direct side effect. |
| MainActivity.onStart and onStop | Robot.addAsrListener / removeAsrListener | Registers and removes the ASR listener | onAsrResult(String, SttLanguage) is accepted only while the custom ASR gate is open | No direct side effect until accepted ASR is published and state advances. |
| MainActivity.onStart and onStop | Robot.addWakeupWordListener / removeWakeupWordListener | Registers and removes the wakeup listener | onWakeupWord(String, int) is filtered by acceptingTemiAsr | An unsolicited callback is suppressed; an accepted wakeup advances the local voice flow. |
| MainActivity.onStart and onStop | Robot.addNlpListener / removeNlpListener | Registers and removes the NLP listener | onNlpCompleted(NlpResult) is logged as ignored default NLU and finishes the conversation | Robot.finishConversation is called by the Activity. |
| MainActivity.onStart and onStop | Robot.addTtsListener / removeTtsListener | Registers and removes the Activity TTS listener | onTtsStatusChanged(TtsRequest) resolves legacy and Activity-owned canonical speech | TTS requests can speak; callback status determines completed or failed result. |
| MainActivity.onStart | Robot.onStart(activityInfo) | Notifies the SDK that the Activity has started | SDK readiness callback follows the lifecycle call | No documented direct side effect beyond SDK lifecycle initialization. |
| MqttLifecycleService.onCreate and onDestroy | Robot.getInstance(); Robot.addTtsListener / removeTtsListener | Gives the long-lived service its own TTS callback path | Service canonical TTS correlation resolves by TtsRequest ID and terminal status | Service-owned canonical speak can continue across Activity recreation. |
| MainActivity and MqttLifecycleService speech helpers | TtsRequest.create(text, false, TtsRequest.Language) | Creates Temi speech requests for canonical and local app speech | TtsRequest.Status.COMPLETED or ERROR is consumed by the callback path | Creates a speech request; Robot.speak performs dispatch. |
| MainActivity canonical and legacy speech paths | Robot.speak(TtsRequest) | Dispatches a TTS request | onTtsStatusChanged reports completion or error; service timeout reports tts_callback_timeout if no terminal callback arrives | Robot speaks. |
| MqttLifecycleService canonical speech path | Robot.speak(TtsRequest) through CanonicalSpeechPort | Dispatches the exactly-one-speak service command | CanonicalCommandRuntime owns the request correlation and terminalizes the MQTT result | Robot speaks. |
| MainActivity command cancellation and interruption paths | Robot.cancelAllTtsRequests() | Cancels active Temi speech during stop, touch interruption, recovery, or unsolicited voice suppression | The command/media runtime records cancellation or failure according to the active path | Stops outstanding speech requests. |
| MainActivity legacy and canonical navigation paths | Robot.goTo(String) | Dispatches an allowlisted navigation target or legacy target | The app reports dispatched after the SDK call; no arrival callback is consumed in the command contract | Robot navigation begins. |
| MainActivity canonical turn paths | Robot.turnBy(int, float) | Dispatches a signed turn: left positive, right negative, speed 0.6 | The app reports dispatched; no pose or completion callback is used | Robot turns. |
| MainActivity stop and interruption paths | Robot.stopMovement() | Stops active movement | The local stop action completes after the call; no arrival state is inferred | Robot movement stops. |
| MainActivity.restoreFullscreenSurfaces | Robot.hideTopBar() | Hides the Temi top bar while restoring the Activity's fullscreen surfaces | No command result callback is required | Changes the robot UI overlay, not movement. |
| MainActivity accepted wakeup path | Robot.wakeup(Collections.singletonList(SttLanguage.ZH_TW)) | Opens Temi wakeup/ASR with the configured speech language | onAsrResult follows only while the Activity has accepted the handoff | Starts the Temi listening path. |
| MainActivity voice setup | Robot.toggleWakeup(true) | Enables Temi wakeup during custom voice setup | The Activity checks the resulting SDK state separately | Enables the Temi wakeup capability. |
| MainActivity voice setup | Robot.setAsrLanguages(Collections.singletonList(SttLanguage.ZH_TW)) | Sets the configured ASR language list | The callback reports SttLanguage | Changes the language used by the Temi ASR path. |
| MainActivity voice setup | Robot.isWakeupDisabled() | Reads the SDK wakeup-disabled state for diagnostics | No callback required | No side effect; read-only SDK state. |
| MainActivity NLP callback | Robot.finishConversation() | Ends a Temi conversation after ignored NLP or before an accepted custom ASR handoff | No result callback required by the Android contract | Ends the current Temi conversation layer. |

The source maps canonical language strings ZH_TW, ZH_CN, EN_US, and JA_JP to
the corresponding TtsRequest.Language values. Any other runtime language
value falls back to TtsRequest.Language.ZH_TW in the source helper.

## Manifest integration

AndroidManifest.xml declares these Temi metadata entries:

| Manifest metadata | Source value | Purpose |
| --- | --- | --- |
| com.robotemi.sdk.metadata.SKILL | app name resource | Identifies the Temi skill. |
| com.robotemi.sdk.metadata.KIOSK | true | Requests kiosk mode. |
| com.robotemi.sdk.metadata.UI_MODE | 1 | Selects the source-defined UI mode. |
| com.robotemi.sdk.metadata.OVERRIDE_NLU | true | Gives the app the source-defined NLU override. |
| com.robotemi.sdk.metadata.OVERRIDE_CONVERSATION_LAYER | true | Gives the app the source-defined conversation-layer override. |

The manifest also exposes MainActivity as the launcher activity and keeps
MqttLifecycleService non-exported. The metadata does not define MQTT or
backend behavior.

## Result and lifecycle boundaries

Temi TTS is callback-grounded: the Android app does not mark canonical speech
complete merely because Robot.speak returned. The service-owned runtime
terminalizes the result on COMPLETED, ERROR, dispatch failure, or its
30-second callback timeout. Activity-owned speech uses the same callback
status model.

Robot.goTo and Robot.turnBy are dispatch operations in the Android command
contract. The source does not consume a Temi arrival or pose result to upgrade
those actions to physical completion. Robot.stopMovement and
Robot.cancelAllTtsRequests are local interruption operations. Robot.wakeup
and ASR callbacks are protected by the custom acceptance gate.

Temi SDK calls are made only by the Android app. CameraX, MediaCodec, OkHttp,
and Paho implement camera, encoding, WebSocket, and MQTT paths separately;
they are not Temi SDK calls. No Hermes, Bridge, LM Studio, or AI6 implementation
is included in this map.

## Verification boundary

The Android source and unit tests cover listener registration, callback
terminalization, service-owned TTS correlation, command dispatch, cancellation,
and lifecycle cleanup. The map does not claim Temi hardware, voice, ASR,
navigation, turn, TTS, or physical stop end-to-end acceptance. Bounded
Android-to-AI6 camera-stream evidence is recorded in
[HANDOVER_READINESS.md](../handover/HANDOVER_READINESS.md); full
Android/AI6 compatibility remains `NOT_VERIFIED`.
