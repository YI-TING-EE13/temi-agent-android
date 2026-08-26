package com.robotemi.agent.mqtt;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MqttReconnectPolicyTest {
    @Test
    public void reconnectDelayIsBoundedExponentialBackoff() {
        assertEquals(1_000L, MqttReconnectPolicy.delayMs(0));
        assertEquals(2_000L, MqttReconnectPolicy.delayMs(1));
        assertEquals(4_000L, MqttReconnectPolicy.delayMs(2));
        assertEquals(30_000L, MqttReconnectPolicy.delayMs(99));
        assertTrue(MqttReconnectPolicy.delayMs(4) < MqttReconnectPolicy.delayMs(5));
        assertEquals(30_000L, MqttReconnectPolicy.delayMs(5));
    }
}
