package com.hunt.otziv.payments.dto;

public record SpecialistPaymentVerifyRequest(
        Boolean recipientStatementChecked,
        Boolean paymentReceived,
        Long receivedAmountKopecks,
        String note
) {
}
