package com.robotemi.agent.media.v11;

/**
 * Resolves optional exercise media without a compile-time dependency on the
 * deployment's private raw resources.
 */
public final class ExerciseMediaResourceResolver {
    public interface ResourceLookup {
        int getIdentifier(String resourceName);
    }

    public static final class MediaUnavailableException extends IllegalStateException {
        public MediaUnavailableException(String mediaId) {
            super("media_unavailable:" + mediaId);
        }
    }

    private ExerciseMediaResourceResolver() {}

    public static int resolve(String mediaId, ResourceLookup resourceLookup) {
        if (resourceLookup == null) {
            throw new IllegalArgumentException("resource_lookup_missing");
        }
        String resourceName = resourceNameFor(mediaId);
        int resourceId = resourceLookup.getIdentifier(resourceName);
        if (resourceId == 0) {
            throw new MediaUnavailableException(mediaId);
        }
        return resourceId;
    }

    static String resourceNameFor(String mediaId) {
        switch (mediaId) {
            case "elderly_hand_exercise":
            case "elderly_leg_exercise":
                return mediaId;
            default:
                throw new IllegalArgumentException("Unsupported media_id: " + mediaId);
        }
    }
}
