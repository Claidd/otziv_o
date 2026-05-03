package com.hunt.otziv.c_companies.dto.api;

import java.util.List;

public record CompanyCreatePayloadResponse(
        String source,
        Long leadId,
        String title,
        String urlChat,
        String telephone,
        String city,
        String email,
        String commentsCompany,
        String operator,
        CompanyCreateOptionResponse manager,
        CompanyCreateOptionResponse worker,
        CompanyCreateOptionResponse status,
        CompanyCreateOptionResponse category,
        CompanyCreateOptionResponse subCategory,
        CompanyCreateOptionResponse filialCity,
        String filialTitle,
        String filialUrl,
        List<CompanyCreateOptionResponse> managers,
        List<CompanyCreateOptionResponse> workers,
        List<CompanyCreateOptionResponse> categories,
        List<CompanyCreateOptionResponse> subCategories,
        List<CompanyCreateOptionResponse> cities,
        boolean canChangeManager
) {
}
