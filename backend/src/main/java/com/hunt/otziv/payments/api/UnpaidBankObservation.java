package com.hunt.otziv.payments.api;

import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.model.PaymentMethod;

/** Reads the bound acquiring operation without cancelling it or returning funds. */
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
}
