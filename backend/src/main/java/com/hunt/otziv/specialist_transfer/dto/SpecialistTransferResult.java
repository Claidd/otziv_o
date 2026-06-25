package com.hunt.otziv.specialist_transfer.dto;

import java.time.LocalDateTime;

public record SpecialistTransferResult(
        Long auditId,
        LocalDateTime createdAt,
        SpecialistTransferWorkerResponse fromWorker,
        SpecialistTransferWorkerResponse toWorker,
        int companyCount,
        int companyLinksAdded,
        int companyLinksRemoved,
        int activeOrderCount,
        int unpublishedReviewCount,
        int badReviewTaskCount
) {
}
