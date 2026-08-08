package com.hunt.otziv.contractor_payments.dto;

/**
 * Decrypted, immutable requisites of one exact active contractor allocation.
 *
 * <p>This value must never be persisted in legacy payment-link/common-invoice
 * plaintext columns. It exists only for the duration of an authorised read
 * after the allocation binding has been validated.</p>
 */
public record ContractorPaymentRequisitesSnapshot(
        Long allocationId,
        String recipientName,
        String paymentPhone,
        String bankName,
        String paymentComment
) {
}
