package com.robotemi.agent.care.report;

import androidx.annotation.Nullable;

/** Framework-neutral state exposed to the Activity renderer. */
public final class CareReportUiState {
    public enum Kind {
        WAITING,
        COMPLETE,
        PARTIAL,
        NO_RECORDS,
        DATE_NOT_FOUND,
        UNSUPPORTED,
        RESIDENT_MISMATCH,
        INVALID,
        INTERACTION_PENDING,
        INTERACTION_FAILED
    }

    public static final CareReportUiState WAITING =
            new CareReportUiState(Kind.WAITING, null, null, 0, 0);
    public static final CareReportUiState INVALID =
            new CareReportUiState(Kind.INVALID, null, null, 0, 0);

    public final Kind kind;
    @Nullable public final CareReport report;
    @Nullable public final String interactionAction;
    public final int reportPosition;
    public final int reportCount;

    CareReportUiState(
            Kind kind,
            @Nullable CareReport report,
            @Nullable String interactionAction,
            int reportPosition,
            int reportCount) {
        this.kind = kind;
        this.report = report;
        this.interactionAction = interactionAction;
        this.reportPosition = reportPosition;
        this.reportCount = reportCount;
    }

    CareReportUiState withInteraction(Kind interactionKind, String action) {
        return new CareReportUiState(
                interactionKind, report, action, reportPosition, reportCount);
    }

    CareReportUiState withNavigation(int position, int count) {
        return new CareReportUiState(
                kind, report, interactionAction, position, count);
    }
}
