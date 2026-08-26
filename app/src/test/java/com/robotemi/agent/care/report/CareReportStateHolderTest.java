package com.robotemi.agent.care.report;

import com.google.gson.JsonObject;
import com.robotemi.agent.identity.IdentityClock;
import com.robotemi.agent.identity.ResidentIdentityParser;
import com.robotemi.agent.identity.ResidentIdentityStateHolder;
import com.robotemi.agent.identity.ResidentIdentityUiState;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class CareReportStateHolderTest {
    private CareReportStateHolder holder;
    private String fatherReport;

    @Before
    public void setUp() throws Exception {
        holder = new CareReportStateHolder(new CareReportParser(), true);
        fatherReport = CareReportFixtures.direct("complete_report.json");
        holder.syncIdentity(identity("father"));
    }

    @Test
    public void acceptsAuthorizedReportInReceiptOrder() throws Exception {
        assertEquals(CareReportStateHolder.Disposition.ACCEPTED,
                holder.accept(fatherReport, false).disposition);
        JsonObject next = CareReportFixtures.completeObject();
        next.addProperty("report_id", "synthetic_report_distinct_002");
        next.addProperty("generated_at", "2026-07-26T20:00:00Z");
        assertEquals(CareReportStateHolder.Disposition.ACCEPTED,
                holder.accept(next.toString(), false).disposition);
        assertEquals("synthetic_report_distinct_002",
                holder.currentState().report.reportId);
    }

    @Test
    public void sameIdSameDigestDuplicateChangedDigestConflict() throws Exception {
        holder.accept(fatherReport, false);
        assertEquals(CareReportStateHolder.Disposition.DUPLICATE,
                holder.accept(fatherReport, false).disposition);
        JsonObject changed = CareReportFixtures.completeObject();
        changed.addProperty("summary", "Changed synthetic summary.");
        CareReportStateHolder.Update conflict = holder.accept(changed.toString(), false);
        assertEquals(CareReportStateHolder.Disposition.CONFLICT, conflict.disposition);
        assertNull(conflict.state.report);
    }

    @Test
    public void retainedReportRejectedBeforeMutation() throws Exception {
        holder.accept(fatherReport, false);
        CareReportStateHolder.Update update = holder.accept("{not-json", true);
        assertEquals(CareReportStateHolder.Disposition.RETAINED_REJECTED,
                update.disposition);
        assertEquals("synthetic_report_complete_001", update.state.report.reportId);
        assertEquals(false, update.clearSensitiveUi);
    }

    @Test
    public void wrongResidentImmediatelyClearsPriorSensitiveReport() throws Exception {
        holder.accept(fatherReport, false);
        CareReportStateHolder.Update wrong = holder.accept(
                CareReportFixtures.wrappedReport("wrong_resident_report.json"), false);
        assertEquals(CareReportStateHolder.Disposition.WRONG_RESIDENT,
                wrong.disposition);
        assertNull(wrong.state.report);
        assertEquals(CareReportUiState.Kind.RESIDENT_MISMATCH, wrong.state.kind);
        assertEquals(true, wrong.clearSensitiveUi);
    }

    @Test
    public void exactAcceptedPayloadRecoversAfterInvalidWrongAndConflict()
            throws Exception {
        holder.accept(fatherReport, false);

        holder.accept("{invalid", false);
        assertEquals(CareReportStateHolder.Disposition.ACCEPTED,
                holder.accept(fatherReport, false).disposition);

        holder.accept(
                CareReportFixtures.wrappedReport("wrong_resident_report.json"), false);
        assertEquals(CareReportStateHolder.Disposition.ACCEPTED,
                holder.accept(fatherReport, false).disposition);

        JsonObject changed = CareReportFixtures.completeObject();
        changed.addProperty("summary", "Synthetic changed content.");
        assertEquals(CareReportStateHolder.Disposition.CONFLICT,
                holder.accept(changed.toString(), false).disposition);
        assertEquals(CareReportStateHolder.Disposition.ACCEPTED,
                holder.accept(fatherReport, false).disposition);
        assertEquals(CareReportStateHolder.Disposition.CONFLICT,
                holder.accept(changed.toString(), false).disposition);
    }

    @Test
    public void sameReportIdIsPartitionedByStableResidentId() throws Exception {
        holder.accept(fatherReport, false);
        holder.syncIdentity(identity("mother"));
        JsonObject motherPayload = com.google.gson.JsonParser.parseString(
                CareReportFixtures.wrappedReport("wrong_resident_report.json"))
                .getAsJsonObject();
        motherPayload.addProperty("report_id", "synthetic_report_complete_001");

        CareReportStateHolder.Update mother =
                holder.accept(motherPayload.toString(), false);

        assertEquals(CareReportStateHolder.Disposition.ACCEPTED, mother.disposition);
        assertEquals("resident_mother", mother.state.report.residentId);
    }

    @Test
    public void identitySwitchUnknownAndEndpointChangeClearReport() throws Exception {
        holder.accept(fatherReport, false);
        assertEquals(CareReportStateHolder.Disposition.IDENTITY_CLEARED,
                holder.syncIdentity(identity("mother")).disposition);
        assertNull(holder.currentState().report);

        holder.syncIdentity(identity("father"));
        assertEquals(CareReportStateHolder.Disposition.ACCEPTED,
                holder.accept(fatherReport, false).disposition);
        holder.syncIdentity(ResidentIdentityUiState.UNKNOWN);
        assertNull(holder.currentState().report);

        holder.syncIdentity(identity("father"));
        assertEquals(CareReportStateHolder.Disposition.ACCEPTED,
                holder.accept(fatherReport, false).disposition);
        assertEquals(CareReportStateHolder.Disposition.ENDPOINT_CHANGED,
                holder.endpointChanged().disposition);
        assertNull(holder.currentState().report);

        holder.syncIdentity(identity("father"));
        assertEquals(CareReportStateHolder.Disposition.ACCEPTED,
                holder.accept(fatherReport, false).disposition);
    }

    @Test
    public void navigationUsesBoundedReceiptOrderNotGeneratedTimestamp()
            throws Exception {
        holder.accept(fatherReport, false);
        JsonObject second = CareReportFixtures.completeObject();
        second.addProperty("report_id", "synthetic_report_received_second");
        second.addProperty("report_date", "2026-07-26");
        second.addProperty("generated_at", "2026-07-26T20:00:00Z");

        CareReportUiState newestReceipt = holder.accept(
                second.toString(), false).state;

        assertEquals("synthetic_report_received_second",
                newestReceipt.report.reportId);
        assertEquals(2, newestReceipt.reportPosition);
        assertEquals(2, newestReceipt.reportCount);
        assertEquals("synthetic_report_complete_001",
                holder.previousReport().report.reportId);
        assertEquals("synthetic_report_received_second",
                holder.nextReport().report.reportId);
    }

    @Test
    public void reportReceiptHistoryIsBounded() throws Exception {
        for (int index = 0; index < 33; index++) {
            JsonObject report = CareReportFixtures.completeObject();
            report.addProperty("report_id", "synthetic_report_" + index);
            holder.accept(report.toString(), false);
        }

        assertEquals(32, holder.currentState().reportCount);
        assertEquals(32, holder.currentState().reportPosition);
        for (int index = 0; index < 31; index++) {
            holder.previousReport();
        }
        assertEquals("synthetic_report_1", holder.currentState().report.reportId);
    }

    @Test
    public void newProcessStartsWithoutReportBody() throws Exception {
        holder.accept(fatherReport, false);
        CareReportStateHolder restarted =
                new CareReportStateHolder(new CareReportParser(), true);
        assertNull(restarted.currentState().report);
    }

    @Test
    public void invalidAndIdentityUnknownFailClosed() throws Exception {
        holder.accept(fatherReport, false);
        assertEquals(CareReportStateHolder.Disposition.INVALID,
                holder.accept(
                        CareReportFixtures.wrappedReport("invalid_report.json"), false)
                        .disposition);
        assertNull(holder.currentState().report);

        JsonObject unknown = CareReportFixtures.completeObject();
        unknown.add("resident_id", com.google.gson.JsonNull.INSTANCE);
        unknown.addProperty("display_name", "unknown");
        unknown.addProperty("status", "identity_unknown");
        unknown.getAsJsonObject("data_completeness")
                .addProperty("status", "identity_unknown");
        unknown.addProperty("error_code", "unknown_resident");
        unknown.addProperty("error_message", "Synthetic unknown.");
        assertEquals(CareReportStateHolder.Disposition.IDENTITY_UNKNOWN,
                holder.accept(unknown.toString(), false).disposition);
    }

    static ResidentIdentityUiState identity(String status) throws Exception {
        MutableClock clock = new MutableClock();
        ResidentIdentityStateHolder identities =
                new ResidentIdentityStateHolder(clock, true);
        String resident = "father".equals(status)
                ? "resident_father" : "resident_mother";
        String payload = "{"
                + "\"schema_version\":\"1.0\","
                + "\"event_id\":\"identity-" + status + "\","
                + "\"resident_id\":\"" + resident + "\","
                + "\"display_name\":\"" + status + "\","
                + "\"identity_status\":\"" + status + "\","
                + "\"confidence\":0.9,"
                + "\"source\":\"manual_selection\","
                + "\"reason\":\"Synthetic test identity.\","
                + "\"timestamp\":\"2026-07-27T12:00:00Z\"}";
        return identities.accept(new ResidentIdentityParser().parse(payload)).state;
    }

    private static final class MutableClock implements IdentityClock {
        @Override public long wallTimeMillis() { return 1_785_153_601_000L; }
        @Override public long monotonicTimeMillis() { return 10_000L; }
    }
}
