package com.hunt.otziv.payments.api;

/**
 * Read-only provider observation of a settled external payment. The caller is
 * responsible for the durable claim and for re-locking its own invoice after
 * the network request; this boundary never applies a financial mutation.
 */
public interface SettledBankPaymentObservation {

    Observation observe(Request request);

    record Request(String provider, String providerOrderId, String providerPaymentId,
                   String merchantId, Long profileId, Long amountKopecks,
                   String paymentMode, String paymentType, Boolean testMode) {
    }

    record Observation(String returnStatus, String providerStatus,
                       String problemCode, boolean unavailable) {
        public static Observation returned(String status, String providerStatus) {
            return new Observation(status, providerStatus, null, false);
        }

        public static Observation confirmed(String providerStatus) {
            return new Observation(null, providerStatus, null, false);
        }

        public static Observation ambiguous(String code) {
            return new Observation(null, null, code, false);
        }

        public static Observation temporarilyUnavailable() {
            return new Observation(null, null, null, true);
        }
    }
}
