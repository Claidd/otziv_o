package com.hunt.otziv.contractor_payments.model;

/** Selects the only accounting writer allowed for contractor rewards. */
public enum ContractorPaymentAccountingAuthority {
    LEGACY,
    /** Canonical writer: an order reward exists only while the order is paid. */
    PAYMENT,
    /**
     * Read compatibility for databases that have not yet applied the PAYMENT
     * authority migration. New activations never write this value.
     */
    @Deprecated
    COMPLETION

    ;

    public boolean paymentBased() {
        return this == PAYMENT || this == COMPLETION;
    }
}
