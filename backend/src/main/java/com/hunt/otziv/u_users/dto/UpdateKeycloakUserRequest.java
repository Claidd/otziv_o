package com.hunt.otziv.u_users.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Set;

@Data
public class UpdateKeycloakUserRequest {

    @Email
    private String email;

    private String fio;

    private String phoneNumber;

    private BigDecimal coefficient;

    private boolean enabled = true;

    @NotEmpty
    private Set<String> roles = new LinkedHashSet<>();
}
