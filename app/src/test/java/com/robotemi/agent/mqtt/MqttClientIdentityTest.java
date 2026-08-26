package com.robotemi.agent.mqtt;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class MqttClientIdentityTest {
    @Test
    public void identityIsStablePerPackageAndRobotButDifferentAcrossRobots() {
        String first = MqttClientIdentity.forRobot("com.robotemi.agent", "temi-01");
        assertEquals(first, MqttClientIdentity.forRobot("com.robotemi.agent", "temi-01"));
        assertNotEquals(first, MqttClientIdentity.forRobot("com.robotemi.agent", "temi-02"));
        assertNotEquals(first, MqttClientIdentity.forRobot("com.example.mock", "temi-01"));
        assertTrue(first.startsWith("temi-android-"));
        assertTrue(first.length() <= 64);
        assertFalse(first.contains("/"));
    }
}
