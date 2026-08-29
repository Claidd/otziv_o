package com.hunt.otziv.payments.tochka.dto;

import com.hunt.otziv.payments.tochka.model.TochkaPaymentMode;
import java.util.List;

public record TochkaCreatePaymentCommand(
        String paymentLinkId,
        long amountKopecks,
        String purpose,
        String email,
        String redirectUrl,
        String failRedirectUrl,
        List<TochkaPaymentMode> paymentModes
) {
    public TochkaCreatePaymentCommand {
        paymentModes = paymentModes == null ? List.of() : List.copyOf(paymentModes);
    }
}
