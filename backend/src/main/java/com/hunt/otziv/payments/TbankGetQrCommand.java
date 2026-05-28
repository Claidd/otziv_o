package com.hunt.otziv.payments;

public record TbankGetQrCommand(
        String paymentId,
        String dataType,
        String bankId
) {
}
