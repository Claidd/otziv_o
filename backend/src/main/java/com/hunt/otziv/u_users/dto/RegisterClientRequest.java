package com.hunt.otziv.u_users.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterClientRequest {

    private static final String STRONG_PASSWORD = "^(?=.*\\p{Ll})(?=.*\\p{Lu})(?=.*\\p{N})(?=.*[^\\p{L}\\p{N}\\s])[^\\r\\n]{12,128}$";

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
    @Size(min = 12, max = 128)
    @Pattern(regexp = STRONG_PASSWORD, message = "Password must include upper/lower-case letters, a number and a special character")
    private String password;

    @NotBlank
    @Size(min = 12, max = 128)
    @Pattern(regexp = STRONG_PASSWORD, message = "Password must include upper/lower-case letters, a number and a special character")
    private String matchingPassword;
}
