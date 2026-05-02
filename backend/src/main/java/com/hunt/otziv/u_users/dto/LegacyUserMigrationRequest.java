package com.hunt.otziv.u_users.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LegacyUserMigrationRequest {

    @NotBlank
    private String username;

    @NotBlank
    private String password;
}
