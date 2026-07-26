package com.hunt.otziv.manager_daily_summary.service;

public record ManagerReportSection(String analysis, String metrics) {

    public ManagerReportSection {
        analysis = analysis == null ? "" : analysis.trim();
        metrics = metrics == null ? "" : metrics.trim();
    }

    public String combined() {
        if (analysis.isBlank()) {
            return metrics;
        }
        return metrics.isBlank() ? analysis : analysis + "\n" + metrics;
    }
}
