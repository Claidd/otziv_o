package com.hunt.otziv.manager.dto.api;

import com.hunt.otziv.reputationai.domain.DeepCompanyResearchJobStatus;

public record CompanyDeepReportStateResponse(
        Long companyId,
        String companyName,
        DeepCompanyResearchJobStatus latestJob,
        DeepCompanyResearchJobStatus activeJob,
        boolean canStart,
        boolean canRefresh,
        String unavailableReason
) {
}
