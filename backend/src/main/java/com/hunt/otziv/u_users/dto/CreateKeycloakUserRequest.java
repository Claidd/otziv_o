package com.hunt.otziv.u_users.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Set;

@Data
public class CreateKeycloakUserRequest {

    @NotBlank
    private String username;

    @Email
    @NotBlank
    private String email;

    private String fio;

    private String phoneNumber;

    @NotBlank
    private String password;

    private boolean temporaryPassword = true;

    private boolean enabled = true;

    private boolean emailVerified = false;

    private BigDecimal coefficient;

    @NotEmpty
    private Set<String> roles = new LinkedHashSet<>();
}
