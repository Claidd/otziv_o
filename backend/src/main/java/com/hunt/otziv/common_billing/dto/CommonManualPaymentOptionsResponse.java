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
        String routeRevision,
        OwnerReport ownerReport
) {
    public record OwnerReport(long id, String reportedBy, boolean attentionMode, boolean canDecide,
            CommonManualPaymentAttributionRequest request) {}

    public CommonManualPaymentOptionsResponse(Long invoiceId, long remainingKopecks, String defaultRecipientKey,
            List<CommonManualPaymentRecipientCandidateResponse> candidates, List<CommonManualPaymentAttributionResponse> history,
            String contractVersion, String routeRevision) {
        this(invoiceId, remainingKopecks, defaultRecipientKey, candidates, history, contractVersion, routeRevision, null);
    }

    public CommonManualPaymentOptionsResponse withReport(OwnerReport report) {
        return new CommonManualPaymentOptionsResponse(invoiceId, remainingKopecks, defaultRecipientKey, candidates, history,
                contractVersion, routeRevision, report);
    }

    public CommonManualPaymentOptionsResponse(
            Long invoiceId, long remainingKopecks, String defaultRecipientKey,
            List<CommonManualPaymentRecipientCandidateResponse> candidates,
            List<CommonManualPaymentAttributionResponse> history
    ) {
        this(invoiceId, remainingKopecks, defaultRecipientKey, candidates, history,
                "TASK_RECIPIENT_V1", null);
    }
}
