package com.robotemi.agent.care.report;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict streaming parser for canonical care_report v1.0. */
public final class CareReportParser {
    private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern RFC3339 = Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?(?:Z|[+-]\\d{2}:\\d{2})$");
    private static final Set<String> STATUSES = new HashSet<>(Arrays.asList(
            "complete", "partial", "no_records", "identity_unknown",
            "date_not_found", "unsupported_schema_version"));
    private static final Set<String> SECTIONS = new HashSet<>(Arrays.asList(
            "summary", "discomfort_events", "abnormal_events", "reminder_status",
            "important_changes", "follow_up_notes"));

    public CareReport parse(String payload) throws ParseException {
        if (payload == null) {
            throw new ParseException("payload_null");
        }
        Fields fields = new Fields();
        Set<String> names = new HashSet<>();
        try (JsonReader reader = new JsonReader(new StringReader(payload))) {
            reader.setLenient(false);
            require(reader, JsonToken.BEGIN_OBJECT, "root");
            reader.beginObject();
            while (reader.hasNext()) {
                String name = nextUniqueName(reader, names);
                switch (name) {
                    case "schema_version": fields.schemaVersion = string(reader, name); break;
                    case "report_id": fields.reportId = string(reader, name); break;
                    case "resident_id":
                        fields.residentSeen = true;
                        fields.residentId = nullableString(reader, name);
                        break;
                    case "display_name": fields.displayName = string(reader, name); break;
                    case "report_date": fields.reportDate = string(reader, name); break;
                    case "generated_at": fields.generatedAt = string(reader, name); break;
                    case "status": fields.status = string(reader, name); break;
                    case "summary": fields.summary = string(reader, name); break;
                    case "discomfort_events":
                        fields.discomfort = evidenceArray(reader, EventShape.EVENT); break;
                    case "abnormal_events":
                        fields.abnormal = evidenceArray(reader, EventShape.EVENT); break;
                    case "reminder_status":
                        fields.reminders = evidenceArray(reader, EventShape.REMINDER); break;
                    case "important_changes":
                        fields.changes = stringArray(reader, name, 500); break;
                    case "follow_up_notes":
                        fields.followUps = stringArray(reader, name, 500); break;
                    case "data_completeness":
                        fields.completeness = completeness(reader); break;
                    case "error_code": fields.errorCode = nullableString(reader, name); break;
                    case "error_message": fields.errorMessage = nullableString(reader, name); break;
                    default: throw new ParseException("unknown_field:" + name);
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new ParseException("trailing_content");
            }
        } catch (IOException | IllegalStateException | NumberFormatException e) {
            throw new ParseException("malformed_json", e);
        }
        if (names.size() != 16 || !fields.residentSeen || fields.hasMissing()) {
            throw new ParseException("missing_required_field");
        }
        validate(fields);
        return fields.toReport();
    }

    private static void validate(Fields f) throws ParseException {
        if (!"1.0".equals(f.schemaVersion)) throw new ParseException("unsupported_schema");
        nonEmpty(f.reportId, "report_id");
        nonEmpty(f.displayName, "display_name");
        if (codePoints(f.summary) > 4000) throw new ParseException("summary_too_long");
        validateDate(f.reportDate);
        validateTimestamp(f.generatedAt, "generated_at");
        if (!STATUSES.contains(f.status) || !f.status.equals(f.completeness.status)) {
            throw new ParseException("status_completeness_mismatch");
        }
        if (new HashSet<>(f.completeness.missing).size()
                        != f.completeness.missing.size()
                || !SECTIONS.containsAll(f.completeness.missing)) {
            throw new ParseException("invalid_missing_sections");
        }
        if (f.errorMessage != null && (f.errorMessage.isEmpty()
                || codePoints(f.errorMessage) > 500)) {
            throw new ParseException("invalid_error_message");
        }
        if ("identity_unknown".equals(f.status)) {
            if (f.residentId != null || !"unknown".equals(f.displayName)
                    || !"unknown_resident".equals(f.errorCode)
                    || f.errorMessage == null) {
                throw new ParseException("invalid_identity_unknown_shape");
            }
            return;
        }
        String expectedResident = "father".equals(f.displayName)
                ? "resident_father" : "mother".equals(f.displayName)
                ? "resident_mother" : null;
        if (expectedResident == null || !expectedResident.equals(f.residentId)) {
            throw new ParseException("invalid_resident_partition");
        }
        switch (f.status) {
            case "complete":
                requireError(f, null);
                if (!f.completeness.missing.isEmpty()) {
                    throw new ParseException("complete_has_missing_sections");
                }
                break;
            case "partial":
                requireError(f, "report_partial_data");
                if (f.completeness.missing.isEmpty()) {
                    throw new ParseException("partial_without_missing_sections");
                }
                break;
            case "no_records":
                requireError(f, "report_no_records");
                if (!f.completeness.missing.isEmpty()) {
                    throw new ParseException("no_records_has_missing_sections");
                }
                break;
            case "date_not_found":
                requireError(f, "report_not_found");
                break;
            case "unsupported_schema_version":
                requireError(f, "unsupported_schema_version");
                if (f.completeness.requested == null
                        || f.completeness.requested.isEmpty()) {
                    throw new ParseException("missing_requested_schema_version");
                }
                break;
            default:
                throw new ParseException("unsupported_status");
        }
    }

    private static void requireError(Fields f, String expected) throws ParseException {
        if (expected == null) {
            if (f.errorCode != null || f.errorMessage != null) {
                throw new ParseException("unexpected_error");
            }
        } else if (!expected.equals(f.errorCode) || f.errorMessage == null) {
            throw new ParseException("invalid_error");
        }
    }

    private static List<CareReport.Evidence> evidenceArray(
            JsonReader reader, EventShape shape) throws IOException, ParseException {
        require(reader, JsonToken.BEGIN_ARRAY, "evidence");
        List<CareReport.Evidence> result = new ArrayList<>();
        reader.beginArray();
        while (reader.hasNext()) {
            require(reader, JsonToken.BEGIN_OBJECT, "evidence_item");
            reader.beginObject();
            Set<String> names = new HashSet<>();
            LinkedHashMap<String, String> values = new LinkedHashMap<>();
            while (reader.hasNext()) {
                String name = nextUniqueName(reader, names);
                if (!shape.fields.contains(name)) {
                    throw new ParseException("unknown_evidence_field:" + name);
                }
                values.put(name, string(reader, name));
            }
            reader.endObject();
            if (!names.equals(shape.fields)) {
                throw new ParseException("missing_evidence_field");
            }
            shape.validate(values);
            result.add(new CareReport.Evidence(values));
        }
        reader.endArray();
        return result;
    }

    private static List<String> stringArray(JsonReader reader, String field, int max)
            throws IOException, ParseException {
        require(reader, JsonToken.BEGIN_ARRAY, field);
        List<String> values = new ArrayList<>();
        reader.beginArray();
        while (reader.hasNext()) {
            String value = string(reader, field);
            if (value.isEmpty() || codePoints(value) > max) {
                throw new ParseException("invalid_array_string:" + field);
            }
            values.add(value);
        }
        reader.endArray();
        return values;
    }

    private static Completeness completeness(JsonReader reader)
            throws IOException, ParseException {
        require(reader, JsonToken.BEGIN_OBJECT, "data_completeness");
        Set<String> names = new HashSet<>();
        String status = null;
        List<String> missing = null;
        String requested = null;
        reader.beginObject();
        while (reader.hasNext()) {
            String name = nextUniqueName(reader, names);
            switch (name) {
                case "status": status = string(reader, name); break;
                case "missing_sections":
                    missing = stringArray(reader, name, 128); break;
                case "requested_schema_version": requested = string(reader, name); break;
                default: throw new ParseException("unknown_completeness_field:" + name);
            }
        }
        reader.endObject();
        if (status == null || missing == null || names.size() < 2 || names.size() > 3) {
            throw new ParseException("invalid_completeness_shape");
        }
        return new Completeness(status, missing, requested);
    }

    private static String nextUniqueName(JsonReader reader, Set<String> names)
            throws IOException, ParseException {
        require(reader, JsonToken.NAME, "field_name");
        String name = reader.nextName();
        if (!names.add(name)) throw new ParseException("duplicate_field:" + name);
        return name;
    }

    private static String string(JsonReader reader, String field)
            throws IOException, ParseException {
        require(reader, JsonToken.STRING, field);
        return reader.nextString();
    }

    private static String nullableString(JsonReader reader, String field)
            throws IOException, ParseException {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull();
            return null;
        }
        return string(reader, field);
    }

    private static void require(JsonReader reader, JsonToken token, String field)
            throws IOException, ParseException {
        if (reader.peek() != token) throw new ParseException("invalid_type:" + field);
    }

    private static void nonEmpty(String value, String field) throws ParseException {
        if (value == null || value.isEmpty()) throw new ParseException("empty:" + field);
    }

    private static int codePoints(String value) {
        return value.codePointCount(0, value.length());
    }

    private static void validateDate(String value) throws ParseException {
        if (!DATE.matcher(value).matches()) throw new ParseException("invalid_report_date");
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        format.setLenient(false);
        ParsePosition position = new ParsePosition(0);
        if (format.parse(value, position) == null || position.getIndex() != value.length()) {
            throw new ParseException("invalid_report_date");
        }
    }

    private static void validateTimestamp(String value, String field) throws ParseException {
        if (!RFC3339.matcher(value).matches()) {
            throw new ParseException("invalid_timestamp:" + field);
        }
        String normalized = value;
        int fraction = normalized.indexOf('.');
        if (fraction >= 0) {
            int offset = normalized.indexOf('Z', fraction);
            if (offset < 0) {
                int plus = normalized.indexOf('+', fraction);
                int minus = normalized.indexOf('-', fraction);
                offset = plus >= 0 ? plus : minus;
            }
            normalized = normalized.substring(0, fraction)
                    + normalized.substring(offset);
        }
        if (normalized.endsWith("Z")) {
            normalized = normalized.substring(0, normalized.length() - 1) + "+0000";
        } else {
            normalized = normalized.substring(0, normalized.length() - 3)
                    + normalized.substring(normalized.length() - 2);
        }
        SimpleDateFormat format =
                new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US);
        format.setLenient(false);
        ParsePosition position = new ParsePosition(0);
        if (format.parse(normalized, position) == null
                || position.getIndex() != normalized.length()) {
            throw new ParseException("invalid_timestamp:" + field);
        }
    }

    private enum EventShape {
        EVENT("event_id", "timestamp", "category"),
        REMINDER("reminder_id", "status");

        final Set<String> fields;
        EventShape(String... fields) {
            this.fields = new HashSet<>(Arrays.asList(fields));
        }

        void validate(Map<String, String> values) throws ParseException {
            if (this == EVENT) {
                bounded(values.get("event_id"), 128, "event_id");
                bounded(values.get("timestamp"), 64, "timestamp");
                bounded(values.get("category"), 80, "category");
            } else {
                bounded(values.get("reminder_id"), 128, "reminder_id");
                bounded(values.get("status"), 64, "reminder_status");
            }
        }

        private static void bounded(String value, int max, String field)
                throws ParseException {
            if (value == null || value.isEmpty() || codePoints(value) > max) {
                throw new ParseException("invalid_evidence_value:" + field);
            }
        }
    }

    private static final class Completeness {
        final String status;
        final List<String> missing;
        final String requested;
        Completeness(String status, List<String> missing, String requested) {
            this.status = status;
            this.missing = missing;
            this.requested = requested;
        }
    }

    private static final class Fields {
        String schemaVersion, reportId, residentId, displayName, reportDate,
                generatedAt, status, summary, errorCode, errorMessage;
        boolean residentSeen;
        List<CareReport.Evidence> discomfort, abnormal, reminders;
        List<String> changes, followUps;
        Completeness completeness;

        boolean hasMissing() {
            return schemaVersion == null || reportId == null || !residentSeen
                    || displayName == null || reportDate == null || generatedAt == null
                    || status == null || summary == null || discomfort == null
                    || abnormal == null || reminders == null || changes == null
                    || followUps == null || completeness == null;
        }

        CareReport toReport() {
            return new CareReport(
                    schemaVersion, reportId, residentId, displayName, reportDate,
                    generatedAt, status, summary, discomfort, abnormal, reminders,
                    changes, followUps, completeness.status, completeness.missing,
                    completeness.requested, errorCode, errorMessage);
        }
    }

    public static final class ParseException extends Exception {
        ParseException(String message) { super(message); }
        ParseException(String message, Throwable cause) { super(message, cause); }
    }
}
