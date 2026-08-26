package com.robotemi.agent.care.report;

import com.robotemi.agent.identity.ResidentIdentityUiState;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Process-local report authorization, duplicate, and resident-isolation owner. */
public final class CareReportStateHolder {
    private static final int REPORT_CAPACITY = 32;
    public enum Disposition {
        ACCEPTED,
        DUPLICATE,
        CONFLICT,
        INVALID,
        RETAINED_REJECTED,
        UNAUTHORIZED,
        WRONG_RESIDENT,
        IDENTITY_UNKNOWN,
        IDENTITY_CLEARED,
        ENDPOINT_CHANGED,
        DISABLED
    }

    public static final class Update {
        public final Disposition disposition;
        public final CareReportUiState state;
        public final boolean clearSensitiveUi;

        Update(Disposition disposition, CareReportUiState state, boolean clearSensitiveUi) {
            this.disposition = disposition;
            this.state = state;
            this.clearSensitiveUi = clearSensitiveUi;
        }
    }

    private final CareReportParser parser;
    private final boolean enabled;
    private final Map<String, String> seenDigests = new LinkedHashMap<>();
    private final List<CareReport> reportsInReceiptOrder = new ArrayList<>();
    private ResidentIdentityUiState identity = ResidentIdentityUiState.UNKNOWN;
    private CareReportUiState state = CareReportUiState.WAITING;
    private int selectedReportIndex = -1;
    private boolean reportAccessBlocked = true;

    public CareReportStateHolder(CareReportParser parser, boolean enabled) {
        this.parser = parser;
        this.enabled = enabled;
    }

    public synchronized Update syncIdentity(ResidentIdentityUiState newIdentity) {
        String oldResident = identity.residentId;
        identity = newIdentity;
        boolean authorized = newIdentity.allowsResidentSpecificContent();
        boolean switched = oldResident != null
                && !oldResident.equals(newIdentity.residentId);
        if (!enabled) {
            reportAccessBlocked = true;
            return clearAll(Disposition.DISABLED);
        }
        if (!authorized) {
            reportAccessBlocked = true;
            return clearAll(Disposition.IDENTITY_CLEARED);
        }
        reportAccessBlocked = false;
        if (switched) return clearAll(Disposition.IDENTITY_CLEARED);
        if (state.report != null
                && !newIdentity.residentId.equals(state.report.residentId)) {
            return clearAll(Disposition.IDENTITY_CLEARED);
        }
        return unchanged(Disposition.DUPLICATE);
    }

    public synchronized Update accept(String payload, boolean retained) {
        if (!enabled) return clearAll(Disposition.DISABLED);
        // Retained delivery is rejected before parsing or any state mutation.
        if (retained) return unchanged(Disposition.RETAINED_REJECTED);
        final CareReport report;
        try {
            report = parser.parse(payload);
        } catch (CareReportParser.ParseException e) {
            return clearReports(Disposition.INVALID);
        }
        if ("identity_unknown".equals(report.status)) {
            reportAccessBlocked = true;
            return clearAll(Disposition.IDENTITY_UNKNOWN);
        }
        if (!identity.allowsResidentSpecificContent()) {
            reportAccessBlocked = true;
            return clearAll(Disposition.UNAUTHORIZED);
        }
        if (!identity.residentId.equals(report.residentId)
                || !identityMatchesDisplay(identity, report.displayName)) {
            return clearReports(Disposition.WRONG_RESIDENT);
        }
        String digest = sha256(payload);
        String reportKey = report.residentId + "\u0000" + report.reportId;
        String prior = seenDigests.get(reportKey);
        if (prior != null) {
            if (prior.equals(digest)) {
                if (containsReport(reportKey)) {
                    return unchanged(Disposition.DUPLICATE);
                }
                reportAccessBlocked = false;
                addReport(report);
                return new Update(Disposition.ACCEPTED, state, false);
            }
            return clearReports(Disposition.CONFLICT);
        }
        // Distinct report IDs intentionally follow process-local receipt order.
        reportAccessBlocked = false;
        seenDigests.put(reportKey, digest);
        addReport(report);
        return new Update(Disposition.ACCEPTED, state, false);
    }

    public synchronized boolean isEntryAuthorized() {
        return enabled && !reportAccessBlocked
                && identity.allowsResidentSpecificContent();
    }

    private boolean containsReport(String reportKey) {
        for (CareReport report : reportsInReceiptOrder) {
            if (reportKey.equals(report.residentId + "\u0000" + report.reportId)) {
                return true;
            }
        }
        return false;
    }

    private void addReport(CareReport report) {
        reportsInReceiptOrder.add(report);
        if (reportsInReceiptOrder.size() > REPORT_CAPACITY) {
            CareReport removed = reportsInReceiptOrder.remove(0);
            seenDigests.remove(removed.residentId + "\u0000" + removed.reportId);
        }
        selectedReportIndex = reportsInReceiptOrder.size() - 1;
        state = stateForSelectedReport();
    }

    public synchronized Update endpointChanged() {
        reportAccessBlocked = !identity.allowsResidentSpecificContent();
        return clearAll(enabled ? Disposition.ENDPOINT_CHANGED : Disposition.DISABLED);
    }

    public synchronized CareReportUiState previousReport() {
        if (selectedReportIndex > 0) {
            selectedReportIndex--;
            state = stateForSelectedReport();
        }
        return state;
    }

    public synchronized CareReportUiState nextReport() {
        if (selectedReportIndex >= 0
                && selectedReportIndex + 1 < reportsInReceiptOrder.size()) {
            selectedReportIndex++;
            state = stateForSelectedReport();
        }
        return state;
    }

    public synchronized CareReportUiState currentState() {
        return state;
    }

    public synchronized boolean isAuthorizedFor(CareReport report) {
        return enabled && report != null && identity.allowsResidentSpecificContent()
                && identity.residentId.equals(report.residentId)
                && identityMatchesDisplay(identity, report.displayName);
    }

    private static boolean identityMatchesDisplay(
            ResidentIdentityUiState identity, String displayName) {
        return (identity.kind == ResidentIdentityUiState.Kind.FATHER
                && "father".equals(displayName))
                || (identity.kind == ResidentIdentityUiState.Kind.MOTHER
                && "mother".equals(displayName));
    }

    private CareReportUiState stateForSelectedReport() {
        if (selectedReportIndex < 0
                || selectedReportIndex >= reportsInReceiptOrder.size()) {
            return CareReportUiState.WAITING;
        }
        return CareReportUiMapper.map(reportsInReceiptOrder.get(selectedReportIndex))
                .withNavigation(
                        selectedReportIndex + 1, reportsInReceiptOrder.size());
    }

    private Update clearReports(Disposition disposition) {
        reportsInReceiptOrder.clear();
        selectedReportIndex = -1;
        state = disposition == Disposition.INVALID
                || disposition == Disposition.CONFLICT
                || disposition == Disposition.RETAINED_REJECTED
                ? CareReportUiState.INVALID
                : disposition == Disposition.WRONG_RESIDENT
                ? new CareReportUiState(
                        CareReportUiState.Kind.RESIDENT_MISMATCH,
                        null, null, 0, 0)
                : CareReportUiState.WAITING;
        return new Update(disposition, state, true);
    }

    private Update clearAll(Disposition disposition) {
        seenDigests.clear();
        return clearReports(disposition);
    }

    private Update unchanged(Disposition disposition) {
        return new Update(disposition, state, false);
    }

    private static String sha256(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) result.append(String.format("%02x", value & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("sha256_unavailable", e);
        }
    }
}
