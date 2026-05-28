package com.hunt.otziv.payments.dto;

public record PaymentLinkArchiveRunResponse(
        int eligible,
        int archived,
        int deleted,
        boolean dryRun,
        String reason
) {
}
