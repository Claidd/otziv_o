package com.hunt.otziv.l_lead.dto.api;

public record LeadPersonOptionResponse(
        Long id,
        Long userId,
        String username,
        String fio,
        String email
) {
}
