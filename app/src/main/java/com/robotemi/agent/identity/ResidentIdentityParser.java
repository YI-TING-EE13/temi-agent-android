package com.robotemi.agent.identity;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict streaming parser for resident_identity_result v1.0. */
public final class ResidentIdentityParser {
    private static final Pattern RFC3339 = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2})(?:\\.(\\d{1,9}))?(Z|[+-]\\d{2}:\\d{2})$");

    public ResidentIdentityResult parse(String payload) throws ParseException {
        if (payload == null) {
            throw new ParseException("payload_null");
        }
        String schemaVersion = null;
        String eventId = null;
        String residentId = null;
        boolean residentIdSeen = false;
        String displayName = null;
        String identityStatus = null;
        Double confidence = null;
        String source = null;
        String reason = null;
        String timestamp = null;
        Set<String> fields = new HashSet<>();

        try (JsonReader reader = new JsonReader(new StringReader(payload))) {
            reader.setLenient(false);
            require(reader, JsonToken.BEGIN_OBJECT, "root_object");
            reader.beginObject();
            while (reader.hasNext()) {
                require(reader, JsonToken.NAME, "field_name");
                String name = reader.nextName();
                if (!fields.add(name)) {
                    throw new ParseException("duplicate_field:" + name);
                }
                switch (name) {
                    case "schema_version":
                        schemaVersion = readString(reader, name);
                        break;
                    case "event_id":
                        eventId = readString(reader, name);
                        break;
                    case "resident_id":
                        residentIdSeen = true;
                        if (reader.peek() == JsonToken.NULL) {
                            reader.nextNull();
                            residentId = null;
                        } else {
                            residentId = readString(reader, name);
                        }
                        break;
                    case "display_name":
                        displayName = readString(reader, name);
                        break;
                    case "identity_status":
                        identityStatus = readString(reader, name);
                        break;
                    case "confidence":
                        require(reader, JsonToken.NUMBER, name);
                        confidence = reader.nextDouble();
                        break;
                    case "source":
                        source = readString(reader, name);
                        break;
                    case "reason":
                        reason = readString(reader, name);
                        break;
                    case "timestamp":
                        timestamp = readString(reader, name);
                        break;
                    default:
                        throw new ParseException("unknown_field:" + name);
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new ParseException("trailing_content");
            }
        } catch (IOException | IllegalStateException | NumberFormatException e) {
            throw new ParseException("malformed_json", e);
        }

        if (fields.size() != 9 || !residentIdSeen || schemaVersion == null || eventId == null
                || displayName == null || identityStatus == null || confidence == null
                || source == null || reason == null || timestamp == null) {
            throw new ParseException("missing_required_field");
        }
        if (!"1.0".equals(schemaVersion)) {
            throw new ParseException("unsupported_schema");
        }
        if (eventId.isEmpty() || reason.isEmpty()
                || reason.codePointCount(0, reason.length()) > 500) {
            throw new ParseException("invalid_string_constraint");
        }
        if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
            throw new ParseException("invalid_confidence");
        }
        if (!isOneOf(displayName, "father", "mother", "unknown")
                || !isOneOf(identityStatus, "father", "mother", "unknown")
                || !isOneOf(source, "vision_gender_fallback", "manual_selection", "unknown")) {
            throw new ParseException("invalid_enum");
        }
        if ("unknown".equals(identityStatus)) {
            if (residentId != null || !"unknown".equals(displayName)
                    || !"unknown".equals(source) || Double.compare(confidence, 0d) != 0) {
                throw new ParseException("invalid_unknown_shape");
            }
        } else {
            String expectedId = "father".equals(identityStatus)
                    ? "resident_father" : "resident_mother";
            if (!expectedId.equals(residentId) || !identityStatus.equals(displayName)
                    || "unknown".equals(source)) {
                throw new ParseException("resident_partition_mismatch");
            }
            if ("vision_gender_fallback".equals(source) && confidence < 0.70d) {
                throw new ParseException("fallback_confidence_below_threshold");
            }
        }
        ParsedTimestamp parsedTimestamp = parseRfc3339(timestamp);
        return new ResidentIdentityResult(
                schemaVersion, eventId, residentId, displayName, identityStatus,
                confidence, source, reason, timestamp, parsedTimestamp.epochMillis,
                parsedTimestamp.epochSecond, parsedTimestamp.nano);
    }

    private static String readString(JsonReader reader, String field)
            throws IOException, ParseException {
        require(reader, JsonToken.STRING, field);
        return reader.nextString();
    }

    private static void require(JsonReader reader, JsonToken token, String field)
            throws IOException, ParseException {
        if (reader.peek() != token) {
            throw new ParseException("invalid_type:" + field);
        }
    }

    private static boolean isOneOf(String value, String... values) {
        for (String candidate : values) {
            if (candidate.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static ParsedTimestamp parseRfc3339(String value) throws ParseException {
        Matcher matcher = RFC3339.matcher(value);
        if (!matcher.matches()) {
            throw new ParseException("timestamp_requires_offset");
        }
        String fraction = matcher.group(2);
        String normalizedFraction = fraction == null ? "000000000"
                : (fraction + "000000000").substring(0, 9);
        String millis = normalizedFraction.substring(0, 3);
        String offset = matcher.group(3);
        if (!"Z".equals(offset)) {
            int hours = Integer.parseInt(offset.substring(1, 3));
            int minutes = Integer.parseInt(offset.substring(4, 6));
            if (hours > 23 || minutes > 59) {
                throw new ParseException("invalid_timestamp_offset");
            }
        }
        String normalizedOffset = "Z".equals(offset) ? "+0000" : offset.replace(":", "");
        String normalized = matcher.group(1) + "." + millis + normalizedOffset;
        SimpleDateFormat format =
                new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US);
        format.setLenient(false);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        ParsePosition position = new ParsePosition(0);
        java.util.Date parsed = format.parse(normalized, position);
        if (parsed == null || position.getIndex() != normalized.length()) {
            throw new ParseException("invalid_timestamp");
        }
        long epochMillis = parsed.getTime();
        return new ParsedTimestamp(
                epochMillis,
                Math.floorDiv(epochMillis, 1000L),
                Integer.parseInt(normalizedFraction));
    }

    private static final class ParsedTimestamp {
        final long epochMillis;
        final long epochSecond;
        final int nano;

        ParsedTimestamp(long epochMillis, long epochSecond, int nano) {
            this.epochMillis = epochMillis;
            this.epochSecond = epochSecond;
            this.nano = nano;
        }
    }

    public static final class ParseException extends Exception {
        ParseException(String message) {
            super(message);
        }

        ParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
