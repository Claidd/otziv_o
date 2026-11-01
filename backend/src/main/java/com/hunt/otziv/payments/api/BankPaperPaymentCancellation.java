package com.hunt.otziv.payments.api;

/** Internal only: caller holds the canonical order mutex and has confirmed bank deletion. */
public interface BankPaperPaymentCancellation {
    void cancel(long linkId,String documentId);
}
