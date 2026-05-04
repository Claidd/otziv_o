package com.hunt.otziv.manager.dto.api;

public record CompanyUpdateRequest(
        String title,
        String urlChat,
        String telephone,
        String city,
        String email,
        Long categoryId,
        Long subCategoryId,
        Long statusId,
        Long managerId,
        String commentsCompany,
        Boolean active,
        Long newWorkerId,
        Long newFilialCityId,
        String newFilialTitle,
        String newFilialUrl
) {
}
