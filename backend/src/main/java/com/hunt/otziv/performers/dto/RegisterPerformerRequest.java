package com.hunt.otziv.performers.dto;

import com.hunt.otziv.performers.model.PerformerGender;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class RegisterPerformerRequest {
    @NotBlank
    private String phoneNumber;

    @NotNull
    private Long cityId;

    private PerformerGender gender;

    @NotBlank
    private String fio;

    private String telegramUsername;

    private String registeredSource;
}
