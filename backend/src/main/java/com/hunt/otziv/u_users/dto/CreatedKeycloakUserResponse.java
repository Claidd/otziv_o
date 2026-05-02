package com.hunt.otziv.u_users.dto;

import lombok.Builder;

import java.math.BigDecimal;
import java.util.Set;

@Builder
public record CreatedKeycloakUserResponse(
        Long id,
        String keycloakId,
        String username,
        String email,
        String fio,
        String phoneNumber,
        BigDecimal coefficient,
        boolean active,
        Set<String> roles
) {
}
