package com.robotemi.agent.identity;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ResidentIdentityParserTest {
    private final ResidentIdentityParser parser = new ResidentIdentityParser();

    @Test
    public void parsesExactFatherAndMotherPartitions() throws Exception {
        ResidentIdentityResult father = parser.parse(known(
                "event-f", "resident_father", "father", 0.70,
                "vision_gender_fallback", "2026-07-27T12:00:00Z"));
        ResidentIdentityResult mother = parser.parse(known(
                "event-m", "resident_mother", "mother", 0.9,
                "manual_selection", "2026-07-27T20:00:00+08:00"));

        assertEquals("resident_father", father.residentId);
        assertEquals("mother", mother.identityStatus);
        assertEquals(father.timestampMillis, mother.timestampMillis);
    }

    @Test
    public void parsesExactUnknownShape() throws Exception {
        ResidentIdentityResult result = parser.parse(unknown("event-u"));
        assertNull(result.residentId);
        assertEquals(0d, result.confidence, 0d);
    }

    @Test(expected = ResidentIdentityParser.ParseException.class)
    public void rejectsDuplicateKey() throws Exception {
        parser.parse(known("event", "resident_father", "father", 0.9,
                "vision_gender_fallback", "2026-07-27T12:00:00Z")
                .replace("\"event_id\":\"event\"",
                        "\"event_id\":\"event\",\"event_id\":\"event\""));
    }

    @Test(expected = ResidentIdentityParser.ParseException.class)
    public void rejectsAdditionalField() throws Exception {
        parser.parse(unknown("event").replace("}", ",\"extra\":true}"));
    }

    @Test(expected = ResidentIdentityParser.ParseException.class)
    public void rejectsMissingField() throws Exception {
        parser.parse(unknown("event").replace("\"reason\":\"synthetic\",", ""));
    }

    @Test(expected = ResidentIdentityParser.ParseException.class)
    public void rejectsTypeCoercion() throws Exception {
        parser.parse(unknown("event").replace("\"confidence\":0", "\"confidence\":\"0\""));
    }

    @Test(expected = ResidentIdentityParser.ParseException.class)
    public void rejectsUnsupportedSchema() throws Exception {
        parser.parse(unknown("event").replace("\"1.0\"", "\"2.0\""));
    }

    @Test(expected = ResidentIdentityParser.ParseException.class)
    public void rejectsWrongStableResidentId() throws Exception {
        parser.parse(known("event", "father", "father", 0.9,
                "vision_gender_fallback", "2026-07-27T12:00:00Z"));
    }

    @Test(expected = ResidentIdentityParser.ParseException.class)
    public void rejectsLowConfidenceVisionFallback() throws Exception {
        parser.parse(known("event", "resident_father", "father", 0.699,
                "vision_gender_fallback", "2026-07-27T12:00:00Z"));
    }

    @Test(expected = ResidentIdentityParser.ParseException.class)
    public void rejectsUnknownWithPersonalResident() throws Exception {
        parser.parse(unknown("event").replace(
                "\"resident_id\":null", "\"resident_id\":\"resident_father\""));
    }

    @Test(expected = ResidentIdentityParser.ParseException.class)
    public void rejectsTimestampWithoutOffset() throws Exception {
        parser.parse(known("event", "resident_father", "father", 0.9,
                "manual_selection", "2026-07-27T12:00:00"));
    }

    @Test
    public void preservesOneToNineFractionalDigitsForOrdering() throws Exception {
        ResidentIdentityResult oneHundredMicros = parser.parse(known(
                "event-a", "resident_father", "father", 0.9,
                "manual_selection", "2026-07-27T12:00:00.0001Z"));
        ResidentIdentityResult twoHundredMicros = parser.parse(known(
                "event-b", "resident_father", "father", 0.9,
                "manual_selection", "2026-07-27T12:00:00.0002Z"));

        assertEquals(oneHundredMicros.timestampMillis, twoHundredMicros.timestampMillis);
        assertEquals(100_000, oneHundredMicros.timestampNano);
        assertEquals(200_000, twoHundredMicros.timestampNano);
        assertEquals(-1, oneHundredMicros.compareTimestamp(twoHundredMicros));
    }

    @Test
    public void reasonMaxLengthUsesUnicodeCodePoints() throws Exception {
        String supplementary = new String(Character.toChars(0x1F642));
        parser.parse(unknown("event")
                .replace("\"reason\":\"synthetic\"",
                        "\"reason\":\"" + repeat(supplementary, 500) + "\""));
    }

    @Test(expected = ResidentIdentityParser.ParseException.class)
    public void rejectsReasonOver500UnicodeCodePoints() throws Exception {
        String supplementary = new String(Character.toChars(0x1F642));
        parser.parse(unknown("event")
                .replace("\"reason\":\"synthetic\"",
                        "\"reason\":\"" + repeat(supplementary, 501) + "\""));
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) {
            result.append(value);
        }
        return result.toString();
    }

    static String known(
            String event, String resident, String status, double confidence,
            String source, String timestamp) {
        return "{"
                + "\"schema_version\":\"1.0\","
                + "\"event_id\":\"" + event + "\","
                + "\"resident_id\":\"" + resident + "\","
                + "\"display_name\":\"" + status + "\","
                + "\"identity_status\":\"" + status + "\","
                + "\"confidence\":" + confidence + ","
                + "\"source\":\"" + source + "\","
                + "\"reason\":\"synthetic\","
                + "\"timestamp\":\"" + timestamp + "\""
                + "}";
    }

    static String unknown(String event) {
        return "{"
                + "\"schema_version\":\"1.0\","
                + "\"event_id\":\"" + event + "\","
                + "\"resident_id\":null,"
                + "\"display_name\":\"unknown\","
                + "\"identity_status\":\"unknown\","
                + "\"confidence\":0,"
                + "\"source\":\"unknown\","
                + "\"reason\":\"synthetic\","
                + "\"timestamp\":\"2026-07-27T12:00:00Z\""
                + "}";
    }
}
