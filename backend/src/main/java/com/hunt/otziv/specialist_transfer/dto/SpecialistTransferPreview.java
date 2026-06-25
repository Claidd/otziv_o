package com.hunt.otziv.specialist_transfer.dto;

import java.util.List;

public record SpecialistTransferPreview(
        SpecialistTransferWorkerResponse fromWorker,
        SpecialistTransferWorkerResponse toWorker,
        int companyCount,
        int activeOrderCount,
        int unpublishedReviewCount,
        int badReviewTaskCount,
        int targetAlreadyAssignedCompanyCount,
        int companiesWithoutActiveOrdersCount,
        int targetWorkerMissingManagerLinksCount,
        List<SpecialistTransferCompanySample> sampleCompanies,
        List<SpecialistTransferWarning> warnings
) {
}
