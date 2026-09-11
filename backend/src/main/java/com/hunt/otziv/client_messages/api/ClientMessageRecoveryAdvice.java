package com.hunt.otziv.client_messages.api;

/** Presentation contract: recovery evidence to inspect, never permission to resend. */
public enum ClientMessageRecoveryAdvice {
    NONE, VERIFY_PREPARATION, VERIFY_RECEIPT;

    public static ClientMessageRecoveryAdvice classify(String deliveryStatus, String errorCode, String errorMessage) {
        String delivery = deliveryStatus == null ? "" : deliveryStatus.trim();
        if ("PREPARED".equalsIgnoreCase(delivery) || "UNKNOWN".equalsIgnoreCase(delivery)) return VERIFY_RECEIPT;
        if ("legacy_operation_unverified".equals(errorCode)
                || ("state_transaction_outcome_uncertain".equals(errorCode) && errorMessage != null
                    && errorMessage.endsWith("Причина: legacy_operation_unverified"))) return VERIFY_PREPARATION;
        String code = errorCode == null ? "" : errorCode.trim();
        return "state_transaction_in_progress".equalsIgnoreCase(code)
                || "state_transaction_outcome_uncertain".equalsIgnoreCase(code)
                || "legacy_operation_unverified".equalsIgnoreCase(code) ? VERIFY_RECEIPT : NONE;
    }
}
