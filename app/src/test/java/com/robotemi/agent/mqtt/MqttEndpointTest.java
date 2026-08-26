package com.robotemi.agent.mqtt;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class MqttEndpointTest {
    @Test
    public void normalizesCredentialFreeIdentity() {
        MqttEndpoint endpoint = MqttEndpoint.create(" Broker.Example. ", 1883, "temi-01");
        assertEquals("broker.example", endpoint.host());
        assertEquals("tcp://broker.example:1883", endpoint.brokerUrl());
        assertEquals(64, endpoint.fingerprint().length());
        assertEquals(endpoint, MqttEndpoint.create("broker.example", 1883, "temi-01"));
    }

    @Test
    public void fingerprintChangesWithHostPortOrRobot() {
        MqttEndpoint base = MqttEndpoint.create("broker.example", 1883, "temi-01");
        assertNotEquals(base.fingerprint(),
                MqttEndpoint.create("other.example", 1883, "temi-01").fingerprint());
        assertNotEquals(base.fingerprint(),
                MqttEndpoint.create("broker.example", 1884, "temi-01").fingerprint());
        assertNotEquals(base.fingerprint(),
                MqttEndpoint.create("broker.example", 1883, "temi-02").fingerprint());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsCredentialsInHost() {
        MqttEndpoint.create("user@broker.example", 1883, "temi-01");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsBrokerList() {
        MqttEndpoint.create("one.example,two.example", 1883, "temi-01");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidPort() {
        MqttEndpoint.create("broker.example", 0, "temi-01");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidRobotId() {
        MqttEndpoint.create("broker.example", 1883, "temi/01");
    }
}
