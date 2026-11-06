package com.hunt.otziv.contractor_payments.model;

/** Selects the only accounting writer allowed for contractor rewards. */
public enum ContractorPaymentAccountingAuthority {
    LEGACY,
    /** Canonical writer: an order reward exists only while the order is paid. */
    PAYMENT;

    public boolean paymentBased() {
        return this == PAYMENT;
    }
}
