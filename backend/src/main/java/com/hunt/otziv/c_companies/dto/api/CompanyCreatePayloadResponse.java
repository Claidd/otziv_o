package com.hunt.otziv.c_companies.dto.api;

import java.util.List;

public record CompanyCreatePayloadResponse(
        String source,
        Long leadId,
        String title,
        String urlChat,
        String urlSite,
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
        String phones,
        String mobilePhones,
        String whatsappPhones,
        String emails,
        String websites,
        String vkUrl,
        String telegramUrl,
        String region,
        String address,
        String industries,
        String companyType,
        List<CompanyCreateOptionResponse> managers,
        List<CompanyCreateOptionResponse> workers,
        List<CompanyCreateOptionResponse> categories,
        List<CompanyCreateOptionResponse> subCategories,
        List<CompanyCreateOptionResponse> cities,
        boolean canChangeManager
) {
}
