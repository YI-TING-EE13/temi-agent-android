package com.robotemi.agent.care.report;

import androidx.lifecycle.ViewModel;

import com.robotemi.agent.identity.ResidentIdentityUiState;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Recreation-safe report state; a new process starts without report authorization. */
public final class CareReportViewModel extends ViewModel {
    private static final int INTERACTION_RECEIPT_CAPACITY = 64;
    private final CareReportStateHolder reports;
    private final CareReportInteractionCoordinator interactions;
    private final Set<String> viewedReports = new LinkedHashSet<>();
    private final Set<String> acknowledgedReports = new LinkedHashSet<>();
    private CareReportUiState uiState = CareReportUiState.WAITING;

    public CareReportViewModel(
            CareReportStateHolder reports,
            CareReportInteractionCoordinator interactions) {
        this.reports = reports;
        this.interactions = interactions;
    }

    public synchronized CareReportStateHolder.Update syncIdentity(
            ResidentIdentityUiState identity) {
        CareReportStateHolder.Update update = reports.syncIdentity(identity);
        clearInteractionReceiptMemoryIfAuthorizationChanged(update.disposition);
        uiState = update.state;
        return update;
    }

    public synchronized CareReportStateHolder.Update acceptReport(
            String payload, boolean retained) {
        CareReportStateHolder.Update update = reports.accept(payload, retained);
        clearInteractionReceiptMemoryIfAuthorizationChanged(update.disposition);
        uiState = update.state;
        return update;
    }

    public synchronized CareReportStateHolder.Update endpointChanged() {
        CareReportStateHolder.Update update = reports.endpointChanged();
        clearInteractionReceiptMemory();
        uiState = update.state;
        return update;
    }

    public synchronized CareReportUiState previousReport() {
        uiState = reports.previousReport();
        return uiState;
    }

    public synchronized CareReportUiState nextReport() {
        uiState = reports.nextReport();
        return uiState;
    }

    public synchronized CareReportInteractionCoordinator.Outcome reportVisible(
            String endpointFingerprint) {
        CareReport report = reports.currentState().report;
        if (!reports.isAuthorizedFor(report)
                || viewedReports.contains(reportKey(report))) {
            return invalidOutcome();
        }
        CareReportInteractionCoordinator.Outcome outcome =
                interactions.create(report, "viewed", endpointFingerprint);
        if (outcome.enqueued()) {
            remember(viewedReports, reportKey(report));
            uiState = reports.currentState().withInteraction(
                    CareReportUiState.Kind.INTERACTION_PENDING, "viewed");
        } else {
            uiState = reports.currentState().withInteraction(
                    CareReportUiState.Kind.INTERACTION_FAILED, "viewed");
        }
        return outcome;
    }

    public synchronized CareReportInteractionCoordinator.Outcome acknowledge(
            String endpointFingerprint) {
        CareReport report = reports.currentState().report;
        if (!reports.isAuthorizedFor(report)
                || acknowledgedReports.contains(reportKey(report))) {
            return invalidOutcome();
        }
        CareReportInteractionCoordinator.Outcome outcome =
                interactions.create(report, "acknowledged", endpointFingerprint);
        if (outcome.enqueued()) {
            remember(acknowledgedReports, reportKey(report));
            uiState = reports.currentState().withInteraction(
                    CareReportUiState.Kind.INTERACTION_PENDING, "acknowledged");
        } else {
            uiState = reports.currentState().withInteraction(
                    CareReportUiState.Kind.INTERACTION_FAILED, "acknowledged");
        }
        return outcome;
    }

    public synchronized void publishFailed(String action) {
        uiState = reports.currentState().withInteraction(
                CareReportUiState.Kind.INTERACTION_FAILED, action);
    }

    public synchronized void publishSucceeded() {
        uiState = reports.currentState();
    }

    public synchronized CareReportUiState state() {
        return uiState;
    }

    public List<CareInteractionPersistence.OutboxRecord> pendingInteractions() {
        return interactions.pending();
    }

    public String pendingEndpointFingerprint() {
        return interactions.pendingEndpointFingerprint();
    }

    public boolean isInteractionStoreAvailable() {
        return interactions.isStoreAvailable();
    }

    public boolean isEntryAuthorized() {
        return reports.isEntryAuthorized();
    }

    public boolean acknowledgePublished(String requestId) {
        return interactions.acknowledgePublished(requestId);
    }

    public boolean discardPendingInteractions() {
        return interactions.discardAll();
    }

    private static CareReportInteractionCoordinator.Outcome invalidOutcome() {
        return new CareReportInteractionCoordinator.Outcome(
                CareReportInteractionCoordinator.Disposition.INVALID, null);
    }

    private static String reportKey(CareReport report) {
        return report.residentId + "\u0000" + report.reportId;
    }

    private void clearInteractionReceiptMemoryIfAuthorizationChanged(
            CareReportStateHolder.Disposition disposition) {
        switch (disposition) {
            case DISABLED:
            case IDENTITY_UNKNOWN:
            case IDENTITY_CLEARED:
            case UNAUTHORIZED:
                clearInteractionReceiptMemory();
                break;
            default:
                break;
        }
    }

    private void clearInteractionReceiptMemory() {
        viewedReports.clear();
        acknowledgedReports.clear();
    }

    private static void remember(Set<String> values, String value) {
        values.add(value);
        if (values.size() <= INTERACTION_RECEIPT_CAPACITY) {
            return;
        }
        Iterator<String> iterator = values.iterator();
        iterator.next();
        iterator.remove();
    }
}
