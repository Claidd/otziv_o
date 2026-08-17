package com.hunt.otziv.common_billing.dto;

import java.util.List;

/** Current amount, safe defaults, selectable recipients and immutable history. */
public record CommonManualPaymentOptionsResponse(
        Long invoiceId,
        long remainingKopecks,
        String defaultRecipientKey,
        List<CommonManualPaymentRecipientCandidateResponse> candidates,
        List<CommonManualPaymentAttributionResponse> history,
        String contractVersion,
        String routeRevision
) {
    public CommonManualPaymentOptionsResponse(
            Long invoiceId, long remainingKopecks, String defaultRecipientKey,
            List<CommonManualPaymentRecipientCandidateResponse> candidates,
            List<CommonManualPaymentAttributionResponse> history
    ) {
        this(invoiceId, remainingKopecks, defaultRecipientKey, candidates, history,
                "TASK_RECIPIENT_V1", null);
    }
}
