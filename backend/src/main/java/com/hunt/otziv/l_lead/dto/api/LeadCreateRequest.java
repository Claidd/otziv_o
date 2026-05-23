package com.hunt.otziv.l_lead.dto.api;

import jakarta.validation.constraints.NotBlank;

public record LeadCreateRequest(
        @NotBlank(message = "Номер не может быть пустым")
        String telephoneLead,
        String companyName,
        String phones,
        String mobilePhones,
        String whatsappPhones,
        String emails,
        String websites,
        String vkUrl,
        String telegramUrl,
        String industries,
        String companyType,
        String region,
        String address,
        @NotBlank(message = "Город не может быть пустым")
        String cityLead,
        String commentsLead,
        Long managerId
) {
}
