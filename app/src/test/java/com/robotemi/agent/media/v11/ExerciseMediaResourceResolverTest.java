package com.robotemi.agent.media.v11;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class ExerciseMediaResourceResolverTest {
    @Test
    public void missingHandExerciseIsReportedAsUnavailable() {
        assertMissing("elderly_hand_exercise");
    }

    @Test
    public void missingLegExerciseIsReportedAsUnavailable() {
        assertMissing("elderly_leg_exercise");
    }

    @Test
    public void availableExerciseResolvesToRuntimeResourceId() {
        int resourceId = ExerciseMediaResourceResolver.resolve(
                "elderly_hand_exercise",
                resourceName -> "elderly_hand_exercise".equals(resourceName) ? 42 : 0);

        assertEquals(42, resourceId);
    }

    private static void assertMissing(String mediaId) {
        try {
            ExerciseMediaResourceResolver.resolve(mediaId, resourceName -> 0);
            fail("Expected missing media to be reported: " + mediaId);
        } catch (ExerciseMediaResourceResolver.MediaUnavailableException error) {
            assertEquals("media_unavailable:" + mediaId, error.getMessage());
        }
    }
}
