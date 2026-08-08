package com.hunt.otziv.contractor_payments.model;

public final class ContractorPaymentAmountLimits {

    /** One billion roubles, expressed in kopecks. Safely below JS integer limits. */
    public static final long MAX_AMOUNT_KOPECKS = 100_000_000_000L;

    private ContractorPaymentAmountLimits() {
    }
}
