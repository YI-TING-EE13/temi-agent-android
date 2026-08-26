package com.robotemi.agent.mqtt;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MqttTopicSetTest {
    @Test
    public void defaultTopicSetExcludesAllGlobalLegacyActions() {
        MqttTopicSet topics = new MqttTopicSet("temi-01");

        assertFalse(topics.legacyActionsEnabled());
        assertFalse(contains(topics.subscribedTopics(), MqttTopicSet.ACTION_SPEAK));
        assertFalse(contains(topics.subscribedTopics(), MqttTopicSet.ACTION_NAVIGATE));
        assertFalse(contains(topics.subscribedTopics(), MqttTopicSet.ACTION_WAKEUP));
    }

    @Test
    public void enabledTopicSetIncludesAllGlobalLegacyActions() {
        MqttTopicSet topics = new MqttTopicSet("temi-01", false, false, true);

        assertTrue(topics.legacyActionsEnabled());
        assertTrue(contains(topics.subscribedTopics(), MqttTopicSet.ACTION_SPEAK));
        assertTrue(contains(topics.subscribedTopics(), MqttTopicSet.ACTION_NAVIGATE));
        assertTrue(contains(topics.subscribedTopics(), MqttTopicSet.ACTION_WAKEUP));
    }

    @Test
    public void canonicalCommandSubscriptionIsPreservedWhenLegacyActionsAreDisabled() {
        MqttTopicSet topics = new MqttTopicSet("temi-01", false, false, false);

        assertTrue(contains(topics.subscribedTopics(), topics.commandRequest()));
        assertFalse(contains(topics.subscribedTopics(), MqttTopicSet.ACTION_SPEAK));
    }

    @Test
    public void identityAndCareSubscriptionsRemainFeatureControlled() {
        MqttTopicSet topics = new MqttTopicSet("temi-01", true, true, false);

        assertTrue(contains(topics.subscribedTopics(), topics.residentIdentityResult()));
        assertTrue(contains(topics.subscribedTopics(), topics.careReport()));
        assertFalse(contains(topics.subscribedTopics(), MqttTopicSet.ACTION_NAVIGATE));
    }

    private static boolean contains(String[] values, String target) {
        for (String value : values) {
            if (target.equals(value)) return true;
        }
        return false;
    }
}
