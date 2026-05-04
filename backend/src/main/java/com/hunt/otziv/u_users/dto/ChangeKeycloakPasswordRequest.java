package com.hunt.otziv.u_users.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ChangeKeycloakPasswordRequest {

    @NotBlank
    @Size(min = 6)
    private String password;

    private boolean temporary = false;
}
