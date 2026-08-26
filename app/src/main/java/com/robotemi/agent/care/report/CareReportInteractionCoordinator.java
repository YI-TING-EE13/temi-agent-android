package com.robotemi.agent.care.report;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;

/** Creates canonical interactions and owns a bounded endpoint-bound durable outbox. */
public final class CareReportInteractionCoordinator {
    public static final int CAPACITY = 128;

    public interface Clock {
        long nowMs();
        String nowTimestamp();
    }

    public interface IdGenerator {
        String nextId();
    }

    public enum Disposition {
        ENQUEUED,
        DUPLICATE,
        CONFLICT,
        CAPACITY_FULL,
        STORE_FAILED,
        INVALID
    }

    public static final class Outcome {
        public final Disposition disposition;
        public final CareInteractionPersistence.OutboxRecord record;

        Outcome(
                Disposition disposition,
                CareInteractionPersistence.OutboxRecord record) {
            this.disposition = disposition;
            this.record = record;
        }

        public boolean enqueued() {
            return disposition == Disposition.ENQUEUED
                    || disposition == Disposition.DUPLICATE;
        }
    }

    private final CareInteractionPersistence persistence;
    private final Clock clock;
    private final IdGenerator idGenerator;
    private CareInteractionPersistence.Snapshot snapshot;
    private boolean storeAvailable = true;

    public CareReportInteractionCoordinator(
            CareInteractionPersistence persistence, Clock clock, IdGenerator idGenerator) {
        this.persistence = persistence;
        this.clock = clock;
        this.idGenerator = idGenerator;
        try {
            snapshot = persistence.load();
            validateSnapshot(snapshot);
        } catch (CareInteractionPersistence.StoreException | RuntimeException e) {
            snapshot = new CareInteractionPersistence.Snapshot();
            storeAvailable = false;
        }
    }

    public synchronized Outcome create(
            CareReport report, String action, String endpointFingerprint) {
        return enqueue(
                idGenerator.nextId(), report, action, endpointFingerprint,
                clock.nowTimestamp(), clock.nowMs());
    }

    synchronized Outcome enqueue(
            String requestId,
            CareReport report,
            String action,
            String endpointFingerprint,
            String timestamp,
            long enqueuedAtMs
    ) {
        if (!storeAvailable || report == null || report.residentId == null
                || requestId == null || requestId.isEmpty()
                || endpointFingerprint == null || endpointFingerprint.isEmpty()
                || (!"viewed".equals(action) && !"acknowledged".equals(action))) {
            return new Outcome(
                    storeAvailable ? Disposition.INVALID : Disposition.STORE_FAILED, null);
        }
        String payload = payload(
                requestId, report.reportId, report.residentId, action, timestamp);
        String digest = sha256(payload);
        if (!snapshot.outbox.isEmpty()
                && !endpointFingerprint.equals(
                        snapshot.outbox.get(0).endpointFingerprint)) {
            return new Outcome(Disposition.CONFLICT, null);
        }
        for (CareInteractionPersistence.OutboxRecord existing : snapshot.outbox) {
            if (requestId.equals(existing.requestId)) {
                return new Outcome(
                        digest.equals(existing.payloadDigest)
                                ? Disposition.DUPLICATE : Disposition.CONFLICT,
                        existing.copy());
            }
        }
        if (snapshot.outbox.size() >= CAPACITY) {
            return new Outcome(Disposition.CAPACITY_FULL, null);
        }
        CareInteractionPersistence.Snapshot next = snapshot.copy();
        CareInteractionPersistence.OutboxRecord record =
                new CareInteractionPersistence.OutboxRecord();
        record.requestId = requestId;
        record.payload = payload;
        record.payloadDigest = digest;
        record.reportId = report.reportId;
        record.residentId = report.residentId;
        record.action = action;
        record.endpointFingerprint = endpointFingerprint;
        record.enqueuedAtMs = enqueuedAtMs;
        next.outbox.add(record);
        if (!save(next)) return new Outcome(Disposition.STORE_FAILED, null);
        return new Outcome(Disposition.ENQUEUED, record.copy());
    }

    public synchronized List<CareInteractionPersistence.OutboxRecord> pending() {
        List<CareInteractionPersistence.OutboxRecord> result = new ArrayList<>();
        for (CareInteractionPersistence.OutboxRecord record : snapshot.outbox) {
            result.add(record.copy());
        }
        return result;
    }

    public synchronized String pendingEndpointFingerprint() {
        return snapshot.outbox.isEmpty()
                ? null : snapshot.outbox.get(0).endpointFingerprint;
    }

    public synchronized boolean isStoreAvailable() {
        return storeAvailable;
    }

    public synchronized boolean acknowledgePublished(String requestId) {
        CareInteractionPersistence.Snapshot next = snapshot.copy();
        boolean removed = false;
        Iterator<CareInteractionPersistence.OutboxRecord> iterator =
                next.outbox.iterator();
        while (iterator.hasNext()) {
            if (requestId.equals(iterator.next().requestId)) {
                iterator.remove();
                removed = true;
                break;
            }
        }
        return !removed || save(next);
    }

    public synchronized boolean discardAll() {
        CareInteractionPersistence.Snapshot next = snapshot.copy();
        next.outbox.clear();
        return save(next);
    }

    private boolean save(CareInteractionPersistence.Snapshot next) {
        if (!storeAvailable) return false;
        try {
            persistence.save(next);
            snapshot = next;
            return true;
        } catch (CareInteractionPersistence.StoreException e) {
            storeAvailable = false;
            return false;
        }
    }

    private static void validateSnapshot(CareInteractionPersistence.Snapshot snapshot) {
        if (snapshot == null || snapshot.outbox == null
                || snapshot.outbox.size() > CAPACITY) {
            throw new IllegalStateException("care_outbox_invalid");
        }
        Set<String> ids = new HashSet<>();
        String endpointFingerprint = null;
        for (CareInteractionPersistence.OutboxRecord record : snapshot.outbox) {
            if (record == null || record.requestId == null || record.payload == null
                    || record.payloadDigest == null || record.reportId == null
                    || record.residentId == null || record.action == null
                    || record.endpointFingerprint == null
                    || record.requestId.isEmpty() || record.reportId.isEmpty()
                    || record.residentId.isEmpty()
                    || (!"viewed".equals(record.action)
                            && !"acknowledged".equals(record.action))
                    || record.endpointFingerprint.isEmpty()
                    || !ids.add(record.requestId)
                    || !record.payloadDigest.equals(sha256(record.payload))
                    || !storedPayloadMatches(record)) {
                throw new IllegalStateException("care_outbox_invalid_record");
            }
            if (endpointFingerprint == null) {
                endpointFingerprint = record.endpointFingerprint;
            } else if (!endpointFingerprint.equals(record.endpointFingerprint)) {
                throw new IllegalStateException("care_outbox_mixed_endpoints");
            }
        }
    }

    private static boolean storedPayloadMatches(
            CareInteractionPersistence.OutboxRecord record) {
        try {
            JsonObject payload = JsonParser.parseString(record.payload).getAsJsonObject();
            return payload.entrySet().size() == 9
                    && "1.0".equals(stringValue(payload, "schema_version"))
                    && record.requestId.equals(stringValue(payload, "request_id"))
                    && record.reportId.equals(stringValue(payload, "report_id"))
                    && record.residentId.equals(stringValue(payload, "resident_id"))
                    && record.action.equals(stringValue(payload, "action"))
                    && "accepted".equals(stringValue(payload, "status"))
                    && payload.has("error_code")
                    && payload.get("error_code").isJsonNull()
                    && payload.has("error_message")
                    && payload.get("error_message").isJsonNull()
                    && !stringValue(payload, "timestamp").isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String stringValue(JsonObject object, String name) {
        if (!object.has(name) || !(object.get(name) instanceof JsonPrimitive)
                || !object.getAsJsonPrimitive(name).isString()) {
            throw new IllegalStateException("care_outbox_invalid_string");
        }
        return object.get(name).getAsString();
    }

    private static String payload(
            String requestId, String reportId, String residentId,
            String action, String timestamp) {
        JsonObject json = new JsonObject();
        json.addProperty("schema_version", "1.0");
        json.addProperty("request_id", requestId);
        json.addProperty("report_id", reportId);
        json.addProperty("resident_id", residentId);
        json.addProperty("action", action);
        json.addProperty("status", "accepted");
        json.add("error_code", JsonNull.INSTANCE);
        json.add("error_message", JsonNull.INSTANCE);
        json.addProperty("timestamp", timestamp);
        return json.toString();
    }

    static String sha256(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) result.append(String.format("%02x", value & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("sha256_unavailable", e);
        }
    }

    public static Clock systemClock() {
        return new Clock() {
            @Override
            public long nowMs() {
                return System.currentTimeMillis();
            }

            @Override
            public String nowTimestamp() {
                SimpleDateFormat format =
                        new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
                format.setTimeZone(TimeZone.getTimeZone("UTC"));
                return format.format(new Date());
            }
        };
    }

    public static IdGenerator uuidGenerator() {
        return () -> UUID.randomUUID().toString();
    }
}
