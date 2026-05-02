package com.hunt.otziv.u_users.dto;

public record AssignmentOptionResponse(
        Long id,
        Long userId,
        String username,
        String fio,
        String email,
        String role
) {
}
