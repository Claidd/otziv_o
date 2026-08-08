package com.hunt.otziv.contractor_payments.service;

/** Durable source evidence is incomplete and requires a later/manual repair. */
public class ContractorReconciliationRequiredException extends RuntimeException {
    public ContractorReconciliationRequiredException(String message) {
        super(message);
    }
}
