package com.hunt.otziv.l_lead.dto.api;

public record LeadUpdateRequest(
        String telephoneLead,
        String cityLead,
        String commentsLead,
        String lidStatus,
        Long operatorId,
        Long managerId,
        Long marketologId
) {
}
