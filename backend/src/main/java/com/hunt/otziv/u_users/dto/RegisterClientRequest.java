package com.hunt.otziv.u_users.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RegisterClientRequest {

    @NotBlank
    private String username;

    @Email
    @NotBlank
    private String email;

    private String fio;

    private String phoneNumber;

    @NotBlank
    private String password;

    @NotBlank
    private String matchingPassword;
}
