package com.robotemi.agent.care.report;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CareReportPresentationTest {
    @Test
    public void entryIsHiddenWheneverOverlayIsVisible() {
        assertTrue(CareReportOverlayPolicy.shouldShowEntry(true, false));
        assertFalse(CareReportOverlayPolicy.shouldShowEntry(true, true));
        assertFalse(CareReportOverlayPolicy.shouldShowEntry(false, false));
        assertFalse(CareReportOverlayPolicy.shouldShowEntry(false, true));
    }

    @Test
    public void completeReportFormatsAvailableEvidence() throws Exception {
        CareReport report = new CareReportParser().parse(
                CareReportFixtures.direct("complete_report.json"));

        String events = CareReportPresentation.formatEvidence(
                report, "discomfort_events", report.discomfortEvents,
                "NO_RECORDS", "UNAVAILABLE");

        assertTrue(events.contains("synthetic_discomfort_001"));
        assertFalse(events.contains("UNAVAILABLE"));
    }

    @Test
    public void partialReportMarksMissingSectionsUnavailable() throws Exception {
        CareReport report = new CareReportParser().parse(
                CareReportFixtures.direct("partial_report.json"));

        assertEquals("UNAVAILABLE", CareReportPresentation.formatEvidence(
                report, "reminder_status", report.reminderStatus,
                "NO_RECORDS", "UNAVAILABLE"));
        assertEquals("UNAVAILABLE", CareReportPresentation.formatTextItems(
                report, "follow_up_notes", report.followUpNotes,
                "NO_RECORDS", "UNAVAILABLE"));
    }

    @Test
    public void noRecordsReportUsesExplicitNoRecordsText() throws Exception {
        CareReport report = new CareReportParser().parse(
                CareReportFixtures.direct("no_records_report.json"));

        assertEquals("NO_RECORDS", CareReportPresentation.formatEvidence(
                report, "reminder_status", report.reminderStatus,
                "NO_RECORDS", "UNAVAILABLE"));
        assertFalse(CareReportPresentation.formatEvidence(
                report, "reminder_status", report.reminderStatus,
                "NO_RECORDS", "UNAVAILABLE").contains("\u72c0\u6cc1\u6b63\u5e38"));
    }
}
