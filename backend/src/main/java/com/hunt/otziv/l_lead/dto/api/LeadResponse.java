package com.hunt.otziv.l_lead.dto.api;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record LeadResponse(
        Long id,
        String telephoneLead,
        String cityLead,
        String commentsLead,
        String lidStatus,
        LocalDate createDate,
        LocalDateTime updateStatus,
        LocalDate dateNewTry,
        boolean offer,
        Long operatorId,
        LeadPersonResponse operator,
        LeadPersonResponse manager,
        LeadPersonResponse marketolog
) {
}
