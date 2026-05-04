package com.hunt.otziv.manager.dto.api;

import java.util.List;

public record CompanyEditResponse(
        Long id,
        String title,
        String urlChat,
        String telephone,
        String city,
        String email,
        String commentsCompany,
        boolean active,
        String createDate,
        String updateStatus,
        String dateNewTry,
        OptionResponse status,
        OptionResponse category,
        OptionResponse subCategory,
        OptionResponse manager,
        List<OptionResponse> categories,
        List<OptionResponse> subCategories,
        List<OptionResponse> statuses,
        List<OptionResponse> managers,
        List<OptionResponse> workers,
        List<OptionResponse> currentWorkers,
        List<FilialResponse> filials,
        List<OptionResponse> cities,
        boolean canChangeManager
) {
}
