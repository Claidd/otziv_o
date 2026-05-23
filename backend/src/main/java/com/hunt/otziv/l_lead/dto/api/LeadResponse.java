package com.hunt.otziv.l_lead.dto.api;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record LeadResponse(
        Long id,
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
        LocalDate createDate,
        LocalDateTime updateStatus,
        LocalDate dateNewTry,
        boolean offer,
        Long operatorId,
        Long telephoneId,
        LeadPersonResponse operator,
        LeadPersonResponse manager,
        LeadPersonResponse marketolog
) {
}
