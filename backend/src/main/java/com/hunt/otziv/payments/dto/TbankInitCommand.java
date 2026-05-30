package com.hunt.otziv.payments.dto;

import java.time.OffsetDateTime;

public record TbankInitCommand(
        String orderId,
        long amountKopecks,
        String description,
        String email,
        String notificationUrl,
        String successUrl,
        String failUrl,
        OffsetDateTime redirectDueDate
) {
}
