package com.hunt.otziv.c_companies.dto.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CompanyCreateRequest(
        @Pattern(regexp = "(?i)^(manager|operator|manual)$")
        String source,
        @Positive
        Long leadId,
        @Positive
        Long managerId,
        @Size(max = 255)
        String title,
        @Size(max = 500)
        String urlChat,
        @Size(max = 2048)
        String urlSite,
        @Size(max = 32)
        String telephone,
        @Size(max = 100)
        String city,
        @Email @Size(max = 255)
        String email,
        @Size(max = 2000)
        String commentsCompany,
        @Positive
        Long categoryId,
        @Positive
        Long subCategoryId,
        @Positive
        Long workerId,
        @Positive
        Long filialCityId,
        @Size(max = 255)
        String filialTitle,
        @Size(max = 2048)
        String filialUrl,
        @Size(max = 10_000)
        String phones,
        @Size(max = 10_000)
        String mobilePhones,
        @Size(max = 10_000)
        String whatsappPhones,
        @Size(max = 10_000)
        String emails,
        @Size(max = 10_000)
        String websites,
        @Size(max = 2048)
        String vkUrl,
        @Size(max = 2048)
        String telegramUrl,
        @Size(max = 255)
        String region,
        @Size(max = 2000)
        String address,
        @Size(max = 2000)
        String industries,
        @Size(max = 255)
        String companyType
) {
}
