package com.robotemi.agent.identity;

import java.util.Objects;

/** Immutable validated canonical resident identity result. */
public final class ResidentIdentityResult {
    public final String schemaVersion;
    public final String eventId;
    public final String residentId;
    public final String displayName;
    public final String identityStatus;
    public final double confidence;
    public final String source;
    public final String reason;
    public final String timestamp;
    public final long timestampMillis;
    public final long timestampEpochSecond;
    public final int timestampNano;

    ResidentIdentityResult(
            String schemaVersion,
            String eventId,
            String residentId,
            String displayName,
            String identityStatus,
            double confidence,
            String source,
            String reason,
            String timestamp,
            long timestampMillis,
            long timestampEpochSecond,
            int timestampNano
    ) {
        this.schemaVersion = schemaVersion;
        this.eventId = eventId;
        this.residentId = residentId;
        this.displayName = displayName;
        this.identityStatus = identityStatus;
        this.confidence = confidence;
        this.source = source;
        this.reason = reason;
        this.timestamp = timestamp;
        this.timestampMillis = timestampMillis;
        this.timestampEpochSecond = timestampEpochSecond;
        this.timestampNano = timestampNano;
    }

    boolean sameCanonicalContent(ResidentIdentityResult other) {
        return eventId.equals(other.eventId) && sameObservationContent(other);
    }

    boolean sameObservationContent(ResidentIdentityResult other) {
        return schemaVersion.equals(other.schemaVersion)
                && Objects.equals(residentId, other.residentId)
                && displayName.equals(other.displayName)
                && identityStatus.equals(other.identityStatus)
                && Double.compare(confidence, other.confidence) == 0
                && source.equals(other.source)
                && reason.equals(other.reason)
                && timestamp.equals(other.timestamp);
    }

    int compareTimestamp(ResidentIdentityResult other) {
        int seconds = Long.compare(timestampEpochSecond, other.timestampEpochSecond);
        return seconds != 0 ? seconds : Integer.compare(timestampNano, other.timestampNano);
    }
}
