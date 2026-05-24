package com.hunt.otziv.c_companies.dto.api;

public record CompanyDeepReportLaunchResponse(
        boolean attempted,
        boolean started,
        Long jobId,
        String status,
        String message
) {
    public static CompanyDeepReportLaunchResponse started(Long jobId, String status, String message) {
        return new CompanyDeepReportLaunchResponse(true, true, jobId, normalize(status), normalize(message));
    }

    public static CompanyDeepReportLaunchResponse failed(String message) {
        return new CompanyDeepReportLaunchResponse(true, false, null, "FAILED", normalize(message));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
