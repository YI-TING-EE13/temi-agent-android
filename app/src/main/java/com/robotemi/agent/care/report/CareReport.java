package com.robotemi.agent.care.report;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable validated canonical care report. */
public final class CareReport {
    public static final class Evidence {
        public final Map<String, String> fields;

        Evidence(LinkedHashMap<String, String> fields) {
            this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        }
    }

    public final String schemaVersion;
    public final String reportId;
    @Nullable public final String residentId;
    public final String displayName;
    public final String reportDate;
    public final String generatedAt;
    public final String status;
    public final String summary;
    public final List<Evidence> discomfortEvents;
    public final List<Evidence> abnormalEvents;
    public final List<Evidence> reminderStatus;
    public final List<String> importantChanges;
    public final List<String> followUpNotes;
    public final String completenessStatus;
    public final List<String> missingSections;
    @Nullable public final String requestedSchemaVersion;
    @Nullable public final String errorCode;
    @Nullable public final String errorMessage;

    CareReport(
            String schemaVersion,
            String reportId,
            @Nullable String residentId,
            String displayName,
            String reportDate,
            String generatedAt,
            String status,
            String summary,
            List<Evidence> discomfortEvents,
            List<Evidence> abnormalEvents,
            List<Evidence> reminderStatus,
            List<String> importantChanges,
            List<String> followUpNotes,
            String completenessStatus,
            List<String> missingSections,
            @Nullable String requestedSchemaVersion,
            @Nullable String errorCode,
            @Nullable String errorMessage
    ) {
        this.schemaVersion = schemaVersion;
        this.reportId = reportId;
        this.residentId = residentId;
        this.displayName = displayName;
        this.reportDate = reportDate;
        this.generatedAt = generatedAt;
        this.status = status;
        this.summary = summary;
        this.discomfortEvents = immutableCopy(discomfortEvents);
        this.abnormalEvents = immutableCopy(abnormalEvents);
        this.reminderStatus = immutableCopy(reminderStatus);
        this.importantChanges =
                Collections.unmodifiableList(new ArrayList<>(importantChanges));
        this.followUpNotes =
                Collections.unmodifiableList(new ArrayList<>(followUpNotes));
        this.completenessStatus = completenessStatus;
        this.missingSections =
                Collections.unmodifiableList(new ArrayList<>(missingSections));
        this.requestedSchemaVersion = requestedSchemaVersion;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    private static List<Evidence> immutableCopy(List<Evidence> source) {
        return Collections.unmodifiableList(new ArrayList<>(source));
    }
}
