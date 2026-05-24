package com.hunt.otziv.payments;

public record TbankCancelCommand(
        String paymentId,
        Long amountKopecks
) {
}
