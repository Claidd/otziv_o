package com.hunt.otziv.payments.tochka.dto;

public record TochkaRefundCommand(
        String operationId,
        long amountKopecks
) {
}
