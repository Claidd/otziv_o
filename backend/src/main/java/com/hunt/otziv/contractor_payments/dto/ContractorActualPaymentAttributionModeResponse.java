package com.hunt.otziv.contractor_payments.dto;

/** Authoritative switch for manual actual-recipient attribution UX/API. */
public record ContractorActualPaymentAttributionModeResponse(
        boolean attributionRequired
) {
}
