package com.hunt.otziv.common_billing.dto;

import java.util.List;

/**
 * Recipients outside the invoice's own participants. They are offered behind a separate button and
 * are valid for this one receipt only: the invoice keeps its recipient.
 */
public record CommonManualPaymentOtherRecipientsResponse(
        Long invoiceId,
        List<Item> recipients
) {
    public record Item(
            CommonManualPaymentRecipientCandidateResponse candidate,
            boolean previousPerformer,
            boolean teamMember
    ) {
    }
}
