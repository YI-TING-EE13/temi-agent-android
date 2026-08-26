package com.robotemi.agent.mqtt;

import androidx.annotation.NonNull;

import com.robotemi.sdk.TtsRequest;

/** Narrow adapter over the supported Robot SDK SPEAK request operation. */
interface CanonicalSpeechPort {
    void speak(@NonNull TtsRequest request);
}
