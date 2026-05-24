package com.hunt.otziv.manager.dto.api;

public record CompanyUpdateRequest(
        String title,
        String urlChat,
        String urlSite,
        String telephone,
        String city,
        String email,
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
        Long categoryId,
        Long subCategoryId,
        Long statusId,
        Long managerId,
        String commentsCompany,
        Boolean active,
        Boolean publicationProgressReportsEnabled,
        Long newWorkerId,
        Long newFilialCityId,
        String newFilialTitle,
        String newFilialUrl
) {
}
