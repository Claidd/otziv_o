package com.hunt.otziv.c_companies.dto.api;

public record CompanyCreateResultResponse(
        Long companyId,
        String title,
        Long leadId,
        String source
) {
}
