package com.robotemi.agent.care.report;

/** Keeps the report entry control mutually exclusive with sensitive content. */
final class CareReportOverlayPolicy {
    private CareReportOverlayPolicy() {}

    static boolean shouldShowEntry(
            boolean entryAllowed, boolean overlayVisible) {
        return entryAllowed && !overlayVisible;
    }
}
