package com.hunt.otziv.l_lead.dto.api;

import java.time.LocalDateTime;

public record DeviceTokenResponse(
        String token,
        LocalDateTime createdAt,
        boolean active
) {
}
