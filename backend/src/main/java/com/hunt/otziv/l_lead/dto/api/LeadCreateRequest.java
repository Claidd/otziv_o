package com.hunt.otziv.l_lead.dto.api;

import jakarta.validation.constraints.NotBlank;

public record LeadCreateRequest(
        @NotBlank(message = "Номер не может быть пустым")
        String telephoneLead,
        @NotBlank(message = "Город не может быть пустым")
        String cityLead,
        String commentsLead,
        Long managerId
) {
}
