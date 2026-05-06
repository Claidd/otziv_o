package com.hunt.otziv.l_lead.dto.api;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record PhoneUpsertRequest(
        @NotBlank String number,
        String fio,
        LocalDate birthday,
        Integer amountAllowed,
        Integer amountSent,
        Integer blockTime,
        LocalDateTime timer,
        String googleLogin,
        String googlePassword,
        String avitoPassword,
        String mailLogin,
        String mailPassword,
        String fotoInstagram,
        Boolean active,
        LocalDate createDate,
        Long operatorId
) {
}
