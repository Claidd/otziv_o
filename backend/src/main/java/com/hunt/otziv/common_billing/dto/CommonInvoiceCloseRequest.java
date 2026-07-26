package com.hunt.otziv.common_billing.dto;

public record CommonInvoiceCloseRequest(
        boolean confirm,
        String comment
) {
}

