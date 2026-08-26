package com.robotemi.agent.mqtt;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MqttEndpointSwitchPolicyTest {
    private final MqttEndpoint first =
            MqttEndpoint.create("first.example", 1883, "temi-01");
    private final MqttEndpoint second =
            MqttEndpoint.create("second.example", 1883, "temi-01");

    @Test
    public void emptyOutboxAllowsEndpointChange() {
        assertTrue(MqttEndpointSwitchPolicy.canActivate(first, second, 0, null));
    }

    @Test
    public void pendingOutboxBlocksCrossEndpointChange() {
        assertFalse(MqttEndpointSwitchPolicy.canActivate(
                first, second, 1, first.fingerprint()));
        assertFalse(MqttEndpointSwitchPolicy.canActivate(first, second, 1, null));
    }

    @Test
    public void pendingOutboxAllowsSameEndpointReconnectAndFlush() {
        assertTrue(MqttEndpointSwitchPolicy.canActivate(
                first, first, 1, first.fingerprint()));
        assertTrue(MqttEndpointSwitchPolicy.canFlush(
                first, 1, first.fingerprint()));
        assertFalse(MqttEndpointSwitchPolicy.canFlush(
                second, 1, first.fingerprint()));
    }

    @Test
    public void pendingOutboxBlocksDisable() {
        assertFalse(MqttEndpointSwitchPolicy.canDisable(1));
        assertTrue(MqttEndpointSwitchPolicy.canDisable(0));
    }
}
