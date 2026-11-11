package com.hunt.otziv.payments.api;

import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.model.PaymentMethod;

/**
 * Reads the bound acquiring operation. Only {@link #closeUnpaid} may close an unpaid T-Bank
 * session; Tochka has no cancellation API, so its operations are only observed.
 */
public interface UnpaidBankObservation {
    record Source(String kind, long targetId, long sourceId, Long profileId, String provider,
            String operation, String order, String merchant, long amount, String modes, Boolean test, String routeVersion) {}
    enum Outcome { CLOSED, WAITING, FINANCIAL }
    record Result(Outcome outcome, String status, PaymentLinkStatus mappedStatus, PaymentMethod paymentMethod,
            String providerPaymentType) {
        public Result(Outcome outcome, String status) { this(outcome, status, null, null, null); }
        public boolean openUnpaid(Source source) {
            return outcome==Outcome.WAITING && ("TOCHKA".equals(source.provider()) && "CREATED".equals(status)
                    || "T_BANK".equals(source.provider()) && ("NEW".equals(status) || "FORM_SHOWED".equals(status)));
        }
    }
    Result observe(Source source);

    /**
     * Observes the operation and, when T-Bank reports an unpaid session (NEW or FORM_SHOWED),
     * cancels it. Returns CLOSED/CANCELED, or FINANCIAL when money moved before the Cancel
     * landed. An unconfirmed Cancel throws, so callers keep the request and observe again.
     */
    Result closeUnpaid(Source source);

    /**
     * Cancels a T-Bank session the caller has just observed as unpaid, after its own locked
     * rechecks. Same results as {@link #closeUnpaid}; other providers are rejected.
     */
    Result cancelUnpaid(Source source);
}
