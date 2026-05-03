package com.hunt.otziv.l_lead.dto.api;

import com.hunt.otziv.l_lead.dto.TextPhoneDTO;

import java.time.LocalDateTime;
import java.util.List;

public record OperatorBoardResponse(
        LeadPageResponse leads,
        List<String> promoTexts,
        TextPhoneDTO text,
        boolean requireDeviceId,
        Long telephoneId,
        Long operatorId,
        LocalDateTime timer,
        boolean timerExpired
) {
}
