package com.hunt.otziv.u_users.dto;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;

@Builder
public record AdminUserResponse(
        Long id,
        String keycloakId,
        boolean keycloakLinked,
        String authProvider,
        String username,
        String email,
        String fio,
        String phoneNumber,
        BigDecimal coefficient,
        Long imageId,
        boolean active,
        LocalDate createTime,
        LocalDateTime lastLoginAt,
        Set<String> roles
) {
}
