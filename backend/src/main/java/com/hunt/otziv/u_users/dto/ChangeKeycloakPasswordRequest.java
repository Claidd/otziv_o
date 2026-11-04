package com.hunt.otziv.u_users.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ChangeKeycloakPasswordRequest {

    /** Matches the Keycloak realm passwordPolicy length(8). */
    @NotBlank
    @Size(min = 8, message = "Пароль должен содержать не менее 8 символов")
    private String password;

    private boolean temporary = false;
}
