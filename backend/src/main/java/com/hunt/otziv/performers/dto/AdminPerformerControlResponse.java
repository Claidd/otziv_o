package com.hunt.otziv.performers.dto;

import java.util.List;

public record AdminPerformerControlResponse(
        List<AdminPerformerResponse> performers,
        List<PerformerAssignmentResponse> assignments,
        List<PerformerCityReportResponse> cityReports,
        PerformerRolloutSettingsResponse rollout
) {
}
