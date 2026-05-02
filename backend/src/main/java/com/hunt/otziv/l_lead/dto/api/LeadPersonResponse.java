package com.hunt.otziv.l_lead.dto.api;

public record LeadPersonResponse(
        Long id,
        Long userId,
        String username,
        String fio
) {
}
