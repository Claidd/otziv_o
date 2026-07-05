package com.hunt.otziv.performers.dto;

public record PerformerCityReportResponse(
        Long cityId,
        String cityTitle,
        long activePerformers,
        long queueAssignments,
        long activeAssignments,
        long verifiedAssignments,
        long rejectedAssignments
) {
}
