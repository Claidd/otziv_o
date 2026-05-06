package com.hunt.otziv.l_lead.dto.api;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record PhoneResponse(
        Long id,
        String number,
        String fio,
        LocalDate birthday,
        int amountAllowed,
        int amountSent,
        int blockTime,
        LocalDateTime timer,
        String googleLogin,
        String googlePassword,
        String avitoPassword,
        String mailLogin,
        String mailPassword,
        String fotoInstagram,
        boolean active,
        LocalDate createDate,
        LocalDateTime updateStatus,
        PhoneOperatorOptionResponse operator,
        List<DeviceTokenResponse> deviceTokens
) {
}
