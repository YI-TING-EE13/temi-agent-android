package com.robotemi.agent.care.report;

import android.app.Activity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.robotemi.agent.R;

/**
 * Activity-owned renderer for the bounded care-report overlay.
 *
 * <p>The binder contains no transport or resident-authorization logic. Its
 * callbacks return to the Activity, which reauthorizes every interaction.</p>
 */
public final class CareReportUiBinder {
    public interface Callbacks {
        void onOpenRequested();

        void onAcknowledgeRequested();

        void onPreviousRequested();

        void onNextRequested();
    }

    private final Button entryButton;
    private final FrameLayout overlay;
    private final TextView residentText;
    private final TextView dateText;
    private final TextView statusText;
    private final TextView summaryText;
    private final TextView completenessText;
    private final TextView missingText;
    private final LinearLayout detailsContainer;
    private final TextView eventsText;
    private final TextView remindersText;
    private final TextView notesText;
    private final TextView interactionText;
    private final LinearLayout navigation;
    private final TextView positionText;
    private final Button previousButton;
    private final Button nextButton;
    private final Button expandButton;
    private final Button acknowledgeButton;

    private boolean entryAllowed;
    private boolean detailsExpanded = true;
    private String renderedReportId;

    public CareReportUiBinder(Activity activity, Callbacks callbacks) {
        entryButton = activity.findViewById(R.id.careReportEntryButton);
        overlay = activity.findViewById(R.id.careReportOverlay);
        residentText = activity.findViewById(R.id.careReportResidentText);
        dateText = activity.findViewById(R.id.careReportDateText);
        statusText = activity.findViewById(R.id.careReportStatusText);
        summaryText = activity.findViewById(R.id.careReportSummaryText);
        completenessText = activity.findViewById(R.id.careReportCompletenessText);
        missingText = activity.findViewById(R.id.careReportMissingText);
        detailsContainer = activity.findViewById(
                R.id.careReportDetailsContainer);
        eventsText = activity.findViewById(R.id.careReportEventsText);
        remindersText = activity.findViewById(R.id.careReportRemindersText);
        notesText = activity.findViewById(R.id.careReportNotesText);
        interactionText = activity.findViewById(R.id.careReportInteractionText);
        navigation = activity.findViewById(R.id.careReportNavigation);
        positionText = activity.findViewById(R.id.careReportPositionText);
        previousButton = activity.findViewById(R.id.careReportPreviousButton);
        nextButton = activity.findViewById(R.id.careReportNextButton);
        expandButton = activity.findViewById(R.id.careReportExpandButton);
        acknowledgeButton = activity.findViewById(R.id.careReportAcknowledgeButton);
        Button backButton = activity.findViewById(R.id.careReportBackButton);

        entryButton.setOnClickListener(v -> callbacks.onOpenRequested());
        acknowledgeButton.setOnClickListener(v ->
                callbacks.onAcknowledgeRequested());
        backButton.setOnClickListener(v -> hideOverlay());
        previousButton.setOnClickListener(v -> callbacks.onPreviousRequested());
        nextButton.setOnClickListener(v -> callbacks.onNextRequested());
        expandButton.setOnClickListener(v -> {
            detailsExpanded = !detailsExpanded;
            updateDetailsVisibility();
        });
    }

    public void disable() {
        entryAllowed = false;
        entryButton.setEnabled(false);
        hideOverlay();
    }

    public void setEntryAllowed(boolean allowed) {
        entryAllowed = allowed;
        entryButton.setEnabled(allowed);
        updateEntryVisibility();
        if (!allowed) {
            hideOverlay();
        }
    }

    public void showOverlay(CareReportUiState state, Runnable afterShown) {
        render(state);
        entryButton.setVisibility(View.GONE);
        overlay.setVisibility(View.VISIBLE);
        overlay.bringToFront();
        postAfterVisible(afterShown);
    }

    public void postAfterVisible(Runnable afterShown) {
        overlay.post(() -> {
            if (overlay.isShown()) {
                afterShown.run();
            }
        });
    }

    public void hideOverlay() {
        overlay.setVisibility(View.GONE);
        updateEntryVisibility();
    }

    public boolean isOverlayVisible() {
        return overlay.getVisibility() == View.VISIBLE && overlay.isShown();
    }

    public void render(CareReportUiState state) {
        CareReport report = state.report;
        String reportId = report == null ? null : report.reportId;
        if (renderedReportId == null
                ? reportId != null : !renderedReportId.equals(reportId)) {
            renderedReportId = reportId;
            detailsExpanded = true;
        }

        residentText.setText(report == null ? ""
                : residentText.getContext().getString(
                        R.string.care_report_resident_value,
                        residentName(report)));
        dateText.setText(report == null ? ""
                : dateText.getContext().getString(
                        R.string.care_report_date_value,
                        report.reportDate));
        summaryText.setText(report == null ? "" : report.summary);
        completenessText.setText(report == null ? ""
                : completenessText.getContext().getString(
                        R.string.care_report_completeness_value,
                        completenessText.getContext().getString(
                                statusResource(CareReportUiMapper.map(report)))));
        missingText.setText(report == null || report.missingSections.isEmpty()
                ? "" : missingText.getContext().getString(
                        R.string.care_report_missing_value,
                        displayMissingSections(report)));
        eventsText.setText(report == null ? "" : reportEvents(report));
        remindersText.setText(report == null ? "" : reportReminders(report));
        notesText.setText(report == null ? "" : reportNotes(report));
        expandButton.setVisibility(report == null ? View.GONE : View.VISIBLE);
        acknowledgeButton.setVisibility(
                report == null ? View.GONE : View.VISIBLE);
        boolean hasMultipleReports = report != null && state.reportCount > 1;
        navigation.setVisibility(
                hasMultipleReports ? View.VISIBLE : View.GONE);
        positionText.setText(report == null ? "" : positionText.getContext().getString(
                R.string.care_report_position,
                state.reportPosition,
                state.reportCount));
        previousButton.setEnabled(
                hasMultipleReports && state.reportPosition > 1);
        nextButton.setEnabled(
                hasMultipleReports && state.reportPosition < state.reportCount);
        statusText.setText(statusResource(state));
        if (state.kind == CareReportUiState.Kind.INTERACTION_PENDING) {
            interactionText.setText(R.string.care_report_interaction_pending);
        } else if (state.kind == CareReportUiState.Kind.INTERACTION_FAILED) {
            interactionText.setText(R.string.care_report_interaction_failed);
        } else {
            interactionText.setText("");
        }
        updateDetailsVisibility();
    }

    private void updateDetailsVisibility() {
        boolean hasReport = renderedReportId != null;
        detailsContainer.setVisibility(
                hasReport && detailsExpanded ? View.VISIBLE : View.GONE);
        expandButton.setText(detailsExpanded
                ? R.string.care_report_hide_details
                : R.string.care_report_show_details);
    }

    private static int statusResource(CareReportUiState state) {
        CareReportUiState.Kind kind = state.kind;
        if ((kind == CareReportUiState.Kind.INTERACTION_PENDING
                || kind == CareReportUiState.Kind.INTERACTION_FAILED)
                && state.report != null) {
            kind = CareReportUiMapper.map(state.report).kind;
        }
        switch (kind) {
            case COMPLETE:
                return R.string.care_report_complete;
            case PARTIAL:
                return R.string.care_report_partial;
            case NO_RECORDS:
                return R.string.care_report_no_records;
            case DATE_NOT_FOUND:
                return R.string.care_report_date_not_found;
            case UNSUPPORTED:
                return R.string.care_report_unsupported;
            case RESIDENT_MISMATCH:
                return R.string.care_report_resident_mismatch;
            case INVALID:
                return R.string.care_report_invalid;
            default:
                return R.string.care_report_waiting;
        }
    }

    private void updateEntryVisibility() {
        boolean showEntry = CareReportOverlayPolicy.shouldShowEntry(
                entryAllowed, overlay.getVisibility() == View.VISIBLE);
        entryButton.setVisibility(showEntry ? View.VISIBLE : View.GONE);
    }

    private String residentName(CareReport report) {
        if ("resident_father".equals(report.residentId)
                || "father".equals(report.displayName)) {
            return residentText.getContext().getString(
                    R.string.resident_identity_father);
        }
        if ("resident_mother".equals(report.residentId)
                || "mother".equals(report.displayName)) {
            return residentText.getContext().getString(
                    R.string.resident_identity_mother);
        }
        return report.displayName;
    }

    private String displayMissingSections(CareReport report) {
        StringBuilder output = new StringBuilder();
        for (String section : report.missingSections) {
            if (output.length() > 0) {
                output.append(", ");
            }
            output.append(sectionLabel(section));
        }
        return output.toString();
    }

    private String sectionLabel(String section) {
        int resource;
        switch (section) {
            case "discomfort_events":
                resource = R.string.care_report_discomfort;
                break;
            case "abnormal_events":
                resource = R.string.care_report_abnormal;
                break;
            case "reminder_status":
                resource = R.string.care_report_reminders;
                break;
            case "important_changes":
                resource = R.string.care_report_important_changes;
                break;
            case "follow_up_notes":
                resource = R.string.care_report_follow_up;
                break;
            default:
                return section;
        }
        return eventsText.getContext().getString(resource);
    }

    private String reportEvents(CareReport report) {
        return sectionLabel("discomfort_events") + "\n"
                + formatEvidence(report, "discomfort_events",
                        report.discomfortEvents)
                + "\n\n" + sectionLabel("abnormal_events") + "\n"
                + formatEvidence(report, "abnormal_events",
                        report.abnormalEvents);
    }

    private String reportReminders(CareReport report) {
        return formatEvidence(
                report, "reminder_status", report.reminderStatus);
    }

    private String reportNotes(CareReport report) {
        return sectionLabel("important_changes") + "\n"
                + CareReportPresentation.formatTextItems(
                        report,
                        "important_changes",
                        report.importantChanges,
                        text(R.string.care_report_section_no_records),
                        text(R.string.care_report_section_unavailable))
                + "\n\n" + sectionLabel("follow_up_notes") + "\n"
                + CareReportPresentation.formatTextItems(
                        report,
                        "follow_up_notes",
                        report.followUpNotes,
                        text(R.string.care_report_section_no_records),
                        text(R.string.care_report_section_unavailable));
    }

    private String formatEvidence(
            CareReport report,
            String sectionName,
            java.util.List<CareReport.Evidence> evidence) {
        return CareReportPresentation.formatEvidence(
                report,
                sectionName,
                evidence,
                text(R.string.care_report_section_no_records),
                text(R.string.care_report_section_unavailable));
    }

    private String text(int resource) {
        return eventsText.getContext().getString(resource);
    }
}
