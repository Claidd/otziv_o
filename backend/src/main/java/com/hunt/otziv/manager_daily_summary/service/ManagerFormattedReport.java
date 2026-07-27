package com.hunt.otziv.manager_daily_summary.service;

public record ManagerFormattedReport(String html, String richHtml, String questionContext) {

    public ManagerFormattedReport(String html, String richHtml) {
        this(html, richHtml, html);
    }

    public ManagerFormattedReport {
        html = html == null ? "" : html;
        richHtml = richHtml == null ? "" : richHtml;
        questionContext = questionContext == null ? html : questionContext;
    }
}
