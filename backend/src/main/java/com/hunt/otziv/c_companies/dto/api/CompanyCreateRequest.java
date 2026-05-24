package com.hunt.otziv.c_companies.dto.api;

public record CompanyCreateRequest(
        String source,
        Long leadId,
        Long managerId,
        String title,
        String urlChat,
        String urlSite,
        String telephone,
        String city,
        String email,
        String commentsCompany,
        Long categoryId,
        Long subCategoryId,
        Long workerId,
        Long filialCityId,
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
        String companyType
) {
}
