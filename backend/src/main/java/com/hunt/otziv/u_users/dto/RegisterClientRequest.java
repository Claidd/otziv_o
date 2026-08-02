package com.hunt.otziv.u_users.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterClientRequest {

    @NotBlank
    @Size(max = 100)
    @Pattern(regexp = "^[^\\p{Cc}\\p{Zl}\\p{Zp}]*$")
    private String username;

    @Email
    @NotBlank
    @Size(max = 255)
    @Pattern(regexp = "^[^\\p{Cc}\\p{Zl}\\p{Zp}]*$")
    private String email;

    @Size(max = 255)
    @Pattern(regexp = "^[^\\p{Cc}\\p{Zl}\\p{Zp}]*$")
    private String fio;

    @Size(max = 50)
    @Pattern(regexp = "^[^\\p{Cc}\\p{Zl}\\p{Zp}]*$")
    private String phoneNumber;

    @NotBlank
    @Size(max = 256)
    private String password;

    @NotBlank
    @Size(max = 256)
    private String matchingPassword;
}
