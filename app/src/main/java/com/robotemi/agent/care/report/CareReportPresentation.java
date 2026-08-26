package com.robotemi.agent.care.report;

import java.util.List;
import java.util.Map;

/** Formats validated report fields without adding clinical interpretation. */
final class CareReportPresentation {
    private CareReportPresentation() {}

    static String formatEvidence(
            CareReport report,
            String sectionName,
            List<CareReport.Evidence> evidence,
            String emptyText,
            String unavailableText) {
        if (report.missingSections.contains(sectionName)) {
            return unavailableText;
        }
        if (evidence.isEmpty()) {
            return emptyText;
        }
        StringBuilder output = new StringBuilder();
        for (CareReport.Evidence item : evidence) {
            if (output.length() > 0) {
                output.append('\n');
            }
            output.append('\u2022').append(' ');
            boolean firstField = true;
            for (Map.Entry<String, String> field : item.fields.entrySet()) {
                if (!firstField) {
                    output.append(" \u00b7 ");
                }
                output.append(field.getKey()).append(": ")
                        .append(field.getValue());
                firstField = false;
            }
        }
        return output.toString();
    }

    static String formatTextItems(
            CareReport report,
            String sectionName,
            List<String> items,
            String emptyText,
            String unavailableText) {
        if (report.missingSections.contains(sectionName)) {
            return unavailableText;
        }
        if (items.isEmpty()) {
            return emptyText;
        }
        StringBuilder output = new StringBuilder();
        for (String item : items) {
            if (output.length() > 0) {
                output.append('\n');
            }
            output.append('\u2022').append(' ').append(item);
        }
        return output.toString();
    }
}
