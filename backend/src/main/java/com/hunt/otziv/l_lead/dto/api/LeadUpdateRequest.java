package com.hunt.otziv.l_lead.dto.api;

public record LeadUpdateRequest(
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
        String cityLead,
        String commentsLead,
        String lidStatus,
        Long operatorId,
        Long telephoneId,
        Long managerId,
        Long marketologId
) {
}
