package com.hunt.otziv.c_companies.dto.api;

public record CompanyCreateRequest(
        String source,
        Long leadId,
        Long managerId,
        String title,
        String urlChat,
        String telephone,
        String city,
        String email,
        String commentsCompany,
        Long categoryId,
        Long subCategoryId,
        Long workerId,
        Long filialCityId,
        String filialTitle,
        String filialUrl
) {
}
