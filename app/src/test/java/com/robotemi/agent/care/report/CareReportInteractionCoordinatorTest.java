package com.robotemi.agent.care.report;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CareReportInteractionCoordinatorTest {
    private InMemoryPersistence persistence;
    private CareReportInteractionCoordinator coordinator;
    private CareReport report;

    @Before
    public void setUp() throws Exception {
        persistence = new InMemoryPersistence();
        coordinator = coordinator(persistence);
        report = new CareReportParser().parse(
                CareReportFixtures.direct("complete_report.json"));
    }

    @Test
    public void viewedAndAcknowledgedAreDistinctCanonicalActions() {
        CareReportInteractionCoordinator.Outcome viewed = coordinator.enqueue(
                "request-viewed", report, "viewed", "endpoint-a",
                "2026-07-27T20:05:00Z", 1L);
        CareReportInteractionCoordinator.Outcome acknowledged = coordinator.enqueue(
                "request-ack", report, "acknowledged", "endpoint-a",
                "2026-07-27T20:06:00Z", 2L);

        assertEquals(CareReportInteractionCoordinator.Disposition.ENQUEUED,
                viewed.disposition);
        assertEquals(CareReportInteractionCoordinator.Disposition.ENQUEUED,
                acknowledged.disposition);
        assertTrue(viewed.record.payload.contains("\"action\":\"viewed\""));
        assertTrue(acknowledged.record.payload.contains("\"action\":\"acknowledged\""));
        assertFalse(viewed.record.payload.equals(acknowledged.record.payload));
    }

    @Test
    public void generatedViewedPayloadConformsToCanonicalFixtureShape()
            throws Exception {
        CareReportInteractionCoordinator.Outcome viewed = coordinator.enqueue(
                "synthetic_interaction_viewed_001", report, "viewed", "endpoint-a",
                "2026-07-27T20:05:00Z", 1L);

        JsonObject expected = JsonParser.parseString(
                CareReportFixtures.direct("viewed_interaction.json")).getAsJsonObject();
        JsonObject actual = JsonParser.parseString(viewed.record.payload).getAsJsonObject();

        assertEquals(expected, actual);
        assertEquals(9, actual.entrySet().size());
        assertFalse(actual.has("side_effect_applied"));
    }

    @Test
    public void retryIsByteIdenticalAndRequestConflictFailsClosed() {
        CareReportInteractionCoordinator.Outcome first = coordinator.enqueue(
                "same-request", report, "viewed", "endpoint-a",
                "2026-07-27T20:05:00Z", 1L);
        CareReportInteractionCoordinator.Outcome duplicate = coordinator.enqueue(
                "same-request", report, "viewed", "endpoint-a",
                "2026-07-27T20:05:00Z", 1L);
        CareReportInteractionCoordinator.Outcome conflict = coordinator.enqueue(
                "same-request", report, "acknowledged", "endpoint-a",
                "2026-07-27T20:05:00Z", 1L);

        assertEquals(CareReportInteractionCoordinator.Disposition.DUPLICATE,
                duplicate.disposition);
        assertEquals(first.record.payload, duplicate.record.payload);
        assertEquals(CareReportInteractionCoordinator.Disposition.CONFLICT,
                conflict.disposition);
        assertEquals(1, coordinator.pending().size());
    }

    @Test
    public void publishSuccessRemovesOnlyMatchingRecord() {
        coordinator.enqueue("one", report, "viewed", "endpoint-a",
                "2026-07-27T20:05:00Z", 1L);
        coordinator.enqueue("two", report, "acknowledged", "endpoint-a",
                "2026-07-27T20:06:00Z", 2L);
        assertTrue(coordinator.acknowledgePublished("one"));
        assertEquals(1, coordinator.pending().size());
        assertEquals("two", coordinator.pending().get(0).requestId);
    }

    @Test
    public void processRestartLoadsEndpointBoundOutboxWithoutReportBody() {
        coordinator.enqueue("one", report, "viewed", "endpoint-a",
                "2026-07-27T20:05:00Z", 1L);
        CareReportInteractionCoordinator restarted = coordinator(persistence);
        assertEquals(1, restarted.pending().size());
        assertEquals("endpoint-a", restarted.pending().get(0).endpointFingerprint);
        assertFalse(restarted.pending().get(0).payload.contains("\"summary\""));
    }

    @Test
    public void pendingOutboxRejectsASecondEndpoint() {
        coordinator.enqueue("one", report, "viewed", "endpoint-a",
                "2026-07-27T20:05:00Z", 1L);

        assertEquals(CareReportInteractionCoordinator.Disposition.CONFLICT,
                coordinator.enqueue("two", report, "acknowledged", "endpoint-b",
                        "2026-07-27T20:06:00Z", 2L).disposition);
        assertEquals("endpoint-a", coordinator.pendingEndpointFingerprint());
        assertEquals(1, coordinator.pending().size());
    }

    @Test
    public void capacityFullDoesNotEvict() {
        for (int i = 0; i < CareReportInteractionCoordinator.CAPACITY; i++) {
            assertEquals(CareReportInteractionCoordinator.Disposition.ENQUEUED,
                    coordinator.enqueue("request-" + i, report, "viewed", "endpoint-a",
                            "2026-07-27T20:05:00Z", i).disposition);
        }
        assertEquals(CareReportInteractionCoordinator.Disposition.CAPACITY_FULL,
                coordinator.enqueue("overflow", report, "viewed", "endpoint-a",
                        "2026-07-27T20:05:00Z", 999L).disposition);
        assertEquals(CareReportInteractionCoordinator.CAPACITY,
                coordinator.pending().size());
        assertEquals("request-0", coordinator.pending().get(0).requestId);
    }

    @Test
    public void persistenceFailureFailsClosed() {
        persistence.failSave = true;
        assertEquals(CareReportInteractionCoordinator.Disposition.STORE_FAILED,
                coordinator.enqueue("request", report, "viewed", "endpoint-a",
                        "2026-07-27T20:05:00Z", 1L).disposition);
        assertTrue(coordinator.pending().isEmpty());
    }

    @Test
    public void removalPersistenceFailureRetainsPublishedRecord() {
        coordinator.enqueue("request", report, "viewed", "endpoint-a",
                "2026-07-27T20:05:00Z", 1L);
        persistence.failSave = true;

        assertFalse(coordinator.acknowledgePublished("request"));
        assertEquals(1, coordinator.pending().size());
    }

    @Test
    public void corruptedStoreFailsClosedWithoutTreatingOutboxAsEmpty() {
        InMemoryPersistence corrupted = new InMemoryPersistence();
        corrupted.snapshot.outbox = null;
        CareReportInteractionCoordinator unavailable = coordinator(corrupted);

        assertEquals(CareReportInteractionCoordinator.Disposition.STORE_FAILED,
                unavailable.enqueue("request", report, "viewed", "endpoint-a",
                        "2026-07-27T20:05:00Z", 1L).disposition);
        assertFalse(unavailable.isStoreAvailable());
    }

    @Test
    public void storedPayloadMetadataMismatchFailsClosed() {
        coordinator.enqueue("request", report, "viewed", "endpoint-a",
                "2026-07-27T20:05:00Z", 1L);
        persistence.snapshot.outbox.get(0).reportId = "different-report";

        CareReportInteractionCoordinator unavailable = coordinator(persistence);

        assertFalse(unavailable.isStoreAvailable());
        assertEquals(CareReportInteractionCoordinator.Disposition.STORE_FAILED,
                unavailable.enqueue("second", report, "viewed", "endpoint-a",
                        "2026-07-27T20:06:00Z", 2L).disposition);
    }

    private static CareReportInteractionCoordinator coordinator(
            InMemoryPersistence persistence) {
        return new CareReportInteractionCoordinator(
                persistence,
                new CareReportInteractionCoordinator.Clock() {
                    @Override public long nowMs() { return 1L; }
                    @Override public String nowTimestamp() {
                        return "2026-07-27T20:05:00Z";
                    }
                },
                () -> "generated-request");
    }

    static final class InMemoryPersistence implements CareInteractionPersistence {
        Snapshot snapshot = new Snapshot();
        boolean failSave;

        @Override
        public Snapshot load() {
            return snapshot.copy();
        }

        @Override
        public void save(Snapshot snapshot) throws StoreException {
            if (failSave) throw new StoreException("synthetic_failure");
            this.snapshot = snapshot.copy();
        }
    }
}
