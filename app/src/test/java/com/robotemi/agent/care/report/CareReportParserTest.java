package com.robotemi.agent.care.report;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CareReportParserTest {
    private final CareReportParser parser = new CareReportParser();

    @Test
    public void parsesCanonicalDirectFixturesAndExactEvidence() throws Exception {
        CareReport complete = parser.parse(CareReportFixtures.direct("complete_report.json"));
        CareReport partial = parser.parse(CareReportFixtures.direct("partial_report.json"));
        CareReport noRecords =
                parser.parse(CareReportFixtures.direct("no_records_report.json"));

        assertEquals("complete", complete.status);
        assertEquals("synthetic_discomfort_001",
                complete.discomfortEvents.get(0).fields.get("event_id"));
        assertEquals("partial", partial.status);
        assertEquals(4, partial.missingSections.size());
        assertEquals("no_records", noRecords.status);
    }

    @Test(expected = CareReportParser.ParseException.class)
    public void rejectsWrappedInvalidFixture() throws Exception {
        parser.parse(CareReportFixtures.wrappedReport("invalid_report.json"));
    }

    @Test
    public void parsesReportInsideWrongResidentWrapperStructurally() throws Exception {
        CareReport report = parser.parse(
                CareReportFixtures.wrappedReport("wrong_resident_report.json"));
        assertEquals("resident_mother", report.residentId);
    }

    @Test(expected = CareReportParser.ParseException.class)
    public void rejectsDuplicateTopLevelKey() throws Exception {
        String payload = CareReportFixtures.direct("complete_report.json")
                .replace("\"schema_version\": \"1.0\",",
                        "\"schema_version\":\"1.0\",\"schema_version\":\"1.0\",");
        parser.parse(payload);
    }

    @Test(expected = CareReportParser.ParseException.class)
    public void rejectsAdditionalField() throws Exception {
        JsonObject json = CareReportFixtures.completeObject();
        json.addProperty("extra", true);
        parser.parse(json.toString());
    }

    @Test(expected = CareReportParser.ParseException.class)
    public void rejectsMissingRequiredField() throws Exception {
        JsonObject json = CareReportFixtures.completeObject();
        json.remove("summary");
        parser.parse(json.toString());
    }

    @Test(expected = CareReportParser.ParseException.class)
    public void rejectsUnknownStatus() throws Exception {
        JsonObject json = CareReportFixtures.completeObject();
        json.addProperty("status", "synthetic_unknown");
        json.getAsJsonObject("data_completeness")
                .addProperty("status", "synthetic_unknown");
        parser.parse(json.toString());
    }

    @Test(expected = CareReportParser.ParseException.class)
    public void rejectsMalformedJson() throws Exception {
        parser.parse("{\"schema_version\":\"1.0\"");
    }

    @Test(expected = CareReportParser.ParseException.class)
    public void rejectsWrongType() throws Exception {
        JsonObject json = CareReportFixtures.completeObject();
        json.addProperty("summary", 3);
        parser.parse(json.toString());
    }

    @Test(expected = CareReportParser.ParseException.class)
    public void rejectsCalendarInvalidDate() throws Exception {
        JsonObject json = CareReportFixtures.completeObject();
        json.addProperty("report_date", "2026-02-30");
        parser.parse(json.toString());
    }

    @Test(expected = CareReportParser.ParseException.class)
    public void rejectsTimestampWithoutOffset() throws Exception {
        JsonObject json = CareReportFixtures.completeObject();
        json.addProperty("generated_at", "2026-07-27T20:00:00");
        parser.parse(json.toString());
    }

    @Test
    public void evidenceTimestampAcceptsCanonicalBoundedOpaqueString()
            throws Exception {
        JsonObject json = CareReportFixtures.completeObject();
        json.getAsJsonArray("discomfort_events").get(0).getAsJsonObject()
                .addProperty("timestamp", "synthetic-morning-observation");

        CareReport report = parser.parse(json.toString());

        assertEquals(
                "synthetic-morning-observation",
                report.discomfortEvents.get(0).fields.get("timestamp"));
    }

    @Test(expected = CareReportParser.ParseException.class)
    public void evidenceTimestampRejectsEmptyString() throws Exception {
        JsonObject json = CareReportFixtures.completeObject();
        json.getAsJsonArray("discomfort_events").get(0).getAsJsonObject()
                .addProperty("timestamp", "");
        parser.parse(json.toString());
    }

    @Test(expected = CareReportParser.ParseException.class)
    public void rejectsStatusCompletenessMismatch() throws Exception {
        JsonObject json = CareReportFixtures.completeObject();
        json.addProperty("status", "partial");
        parser.parse(json.toString());
    }

    @Test(expected = CareReportParser.ParseException.class)
    public void rejectsEventAdditionalKey() throws Exception {
        JsonObject json = CareReportFixtures.completeObject();
        json.getAsJsonArray("discomfort_events").get(0).getAsJsonObject()
                .addProperty("detail", "not canonical");
        parser.parse(json.toString());
    }

    @Test
    public void accepts4000UnicodeCodePoints() throws Exception {
        JsonObject json = CareReportFixtures.completeObject();
        json.addProperty("summary", repeatSupplementary(4000));
        parser.parse(json.toString());
    }

    @Test(expected = CareReportParser.ParseException.class)
    public void rejects4001UnicodeCodePoints() throws Exception {
        JsonObject json = CareReportFixtures.completeObject();
        json.addProperty("summary", repeatSupplementary(4001));
        parser.parse(json.toString());
    }

    @Test
    public void parsesDateNotFoundAndUnsupportedShapes() throws Exception {
        JsonObject date = CareReportFixtures.completeObject();
        date.addProperty("status", "date_not_found");
        date.getAsJsonObject("data_completeness")
                .addProperty("status", "date_not_found");
        date.addProperty("error_code", "report_not_found");
        date.addProperty("error_message", "Synthetic date is absent.");
        assertEquals("date_not_found", parser.parse(date.toString()).status);

        JsonObject unsupported = date.deepCopy();
        unsupported.addProperty("status", "unsupported_schema_version");
        unsupported.getAsJsonObject("data_completeness")
                .addProperty("status", "unsupported_schema_version");
        unsupported.getAsJsonObject("data_completeness")
                .addProperty("requested_schema_version", "2.0");
        unsupported.addProperty("error_code", "unsupported_schema_version");
        assertEquals("2.0", parser.parse(unsupported.toString()).requestedSchemaVersion);
    }

    @Test
    public void parsesIdentityUnknownShape() throws Exception {
        JsonObject json = CareReportFixtures.completeObject();
        json.add("resident_id", JsonNull.INSTANCE);
        json.addProperty("display_name", "unknown");
        json.addProperty("status", "identity_unknown");
        json.getAsJsonObject("data_completeness")
                .addProperty("status", "identity_unknown");
        json.addProperty("error_code", "unknown_resident");
        json.addProperty("error_message", "Synthetic identity unavailable.");
        assertEquals("identity_unknown", parser.parse(json.toString()).status);
    }

    private static String repeatSupplementary(int count) {
        String value = new String(Character.toChars(0x1F642));
        StringBuilder result = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }
}
