package com.hunt.otziv.payments.dto;

public record PublicSbpBankResponse(
        String bankId,
        String nspkBankId,
        String name,
        String logoUrl,
        Integer order,
        boolean featured
) {
}
