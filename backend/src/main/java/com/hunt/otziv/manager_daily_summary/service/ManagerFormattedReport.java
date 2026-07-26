package com.hunt.otziv.manager_daily_summary.service;

public record ManagerFormattedReport(String html, String richHtml) {

    public ManagerFormattedReport {
        html = html == null ? "" : html;
        richHtml = richHtml == null ? "" : richHtml;
    }
}
