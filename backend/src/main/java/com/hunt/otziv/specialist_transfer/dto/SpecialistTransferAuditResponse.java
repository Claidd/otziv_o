package com.hunt.otziv.specialist_transfer.dto;

import java.time.LocalDateTime;

public record SpecialistTransferAuditResponse(
        Long id,
        LocalDateTime createdAt,
        Long actorUserId,
        String actorName,
        Long fromWorkerId,
        String fromWorkerName,
        Long toWorkerId,
        String toWorkerName,
        int companyCount,
        int orderCount,
        int reviewCount,
        int badReviewTaskCount,
        String comment
) {
}
