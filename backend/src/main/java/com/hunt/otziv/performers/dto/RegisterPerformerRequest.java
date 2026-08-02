package com.hunt.otziv.performers.dto;

import com.hunt.otziv.performers.model.PerformerGender;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterPerformerRequest {
    @NotBlank
    @Size(max = 20)
    @Pattern(regexp = "^(?=(?:\\D*\\d){10,15}\\D*$)\\+?[0-9()\\-\\s]{10,20}$")
    private String phoneNumber;

    @NotNull
    @Positive
    private Long cityId;

    private PerformerGender gender;

    @NotBlank
    @Size(max = 255)
    @Pattern(regexp = "^[^\\p{Cc}\\p{Zl}\\p{Zp}]*$")
    private String fio;

    @Size(max = 64)
    @Pattern(regexp = "^$|^@?[A-Za-z0-9_]{5,32}$")
    private String telegramUsername;

    @Size(max = 100)
    @Pattern(regexp = "^[^\\p{Cc}\\p{Zl}\\p{Zp}]*$")
    private String registeredSource;
}
