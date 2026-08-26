package com.robotemi.agent.care.report;

/** Maps canonical status to bounded UI states without clinical inference. */
public final class CareReportUiMapper {
    private CareReportUiMapper() {}

    public static CareReportUiState map(CareReport report) {
        switch (report.status) {
            case "complete":
                return new CareReportUiState(
                        CareReportUiState.Kind.COMPLETE, report, null, 1, 1);
            case "partial":
                return new CareReportUiState(
                        CareReportUiState.Kind.PARTIAL, report, null, 1, 1);
            case "no_records":
                return new CareReportUiState(
                        CareReportUiState.Kind.NO_RECORDS, report, null, 1, 1);
            case "date_not_found":
                return new CareReportUiState(
                        CareReportUiState.Kind.DATE_NOT_FOUND, report, null, 1, 1);
            case "unsupported_schema_version":
                return new CareReportUiState(
                        CareReportUiState.Kind.UNSUPPORTED, report, null, 1, 1);
            default:
                return CareReportUiState.INVALID;
        }
    }
}
