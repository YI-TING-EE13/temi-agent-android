package com.robotemi.agent.care.report;

/** Framework-neutral routing policy for report entry and overlay mutations. */
public final class CareReportRoutePolicy {
    public static final class Decision {
        public final boolean showEntry;
        public final boolean hideOverlay;
        public final boolean renderOverlay;
        public final boolean recordVisibleReport;

        Decision(
                boolean showEntry,
                boolean hideOverlay,
                boolean renderOverlay,
                boolean recordVisibleReport) {
            this.showEntry = showEntry;
            this.hideOverlay = hideOverlay;
            this.renderOverlay = renderOverlay;
            this.recordVisibleReport = recordVisibleReport;
        }
    }

    private CareReportRoutePolicy() {}

    public static Decision decide(
            CareReportStateHolder.Update update,
            boolean overlayVisible,
            boolean entryAuthorized) {
        boolean hideOverlay = update.clearSensitiveUi;
        boolean retainedRejected =
                update.disposition
                        == CareReportStateHolder.Disposition.RETAINED_REJECTED;
        boolean renderOverlay =
                overlayVisible && !hideOverlay && !retainedRejected;
        boolean recordVisibleReport = renderOverlay
                && update.disposition == CareReportStateHolder.Disposition.ACCEPTED
                && update.state.report != null;
        return new Decision(
                entryAuthorized,
                hideOverlay,
                renderOverlay,
                recordVisibleReport);
    }
}
