package com.hunt.otziv.payments.dto;

public record SpecialistPaymentAbsentRequest(
        Boolean recipientStatementChecked,
        Boolean paymentAbsent,
        String note
) {
}
