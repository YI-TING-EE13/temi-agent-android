package com.robotemi.agent.care.report;

import java.util.ArrayList;
import java.util.List;

/** Durable metadata-only boundary for care interaction delivery. */
public interface CareInteractionPersistence {
    Snapshot load() throws StoreException;
    void save(Snapshot snapshot) throws StoreException;

    final class Snapshot {
        public List<OutboxRecord> outbox = new ArrayList<>();

        Snapshot copy() {
            Snapshot copy = new Snapshot();
            for (OutboxRecord record : outbox) copy.outbox.add(record.copy());
            return copy;
        }
    }

    final class OutboxRecord {
        public String requestId;
        public String payload;
        public String payloadDigest;
        public String reportId;
        public String residentId;
        public String action;
        public String endpointFingerprint;
        public long enqueuedAtMs;

        OutboxRecord copy() {
            OutboxRecord copy = new OutboxRecord();
            copy.requestId = requestId;
            copy.payload = payload;
            copy.payloadDigest = payloadDigest;
            copy.reportId = reportId;
            copy.residentId = residentId;
            copy.action = action;
            copy.endpointFingerprint = endpointFingerprint;
            copy.enqueuedAtMs = enqueuedAtMs;
            return copy;
        }
    }

    final class StoreException extends Exception {
        StoreException(String message) { super(message); }
        StoreException(String message, Throwable cause) { super(message, cause); }
    }
}
