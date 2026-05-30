package com.hunt.otziv.payments.dto;

public record TbankCancelCommand(
        String paymentId,
        Long amountKopecks
) {
}
