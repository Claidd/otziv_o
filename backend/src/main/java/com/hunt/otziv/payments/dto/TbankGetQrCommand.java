package com.hunt.otziv.payments.dto;

public record TbankGetQrCommand(
        String paymentId,
        String dataType,
        String bankId
) {
}
