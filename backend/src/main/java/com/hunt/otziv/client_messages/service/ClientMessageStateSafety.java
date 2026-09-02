package com.hunt.otziv.client_messages.service;

import com.hunt.otziv.client_messages.model.ScheduledClientMessageState;

public final class ClientMessageStateSafety {

    public static final String TRANSACTION_IN_PROGRESS = "state_transaction_in_progress";
    public static final String TRANSACTION_OUTCOME_UNCERTAIN = "state_transaction_outcome_uncertain";
    public static final String DELIVERY_PREPARED = "PREPARED";
    public static final String DELIVERY_OUTCOME_UNKNOWN = "UNKNOWN";

    private ClientMessageStateSafety() {
    }

    public static boolean blocksAutomaticRearm(ScheduledClientMessageState state) {
        if (state == null) {
            return false;
        }
        String deliveryStatus = state.getDeliveryStatus();
        if (deliveryStatus != null
                && (DELIVERY_PREPARED.equalsIgnoreCase(deliveryStatus.trim())
                || DELIVERY_OUTCOME_UNKNOWN.equalsIgnoreCase(deliveryStatus.trim()))) {
            return true;
        }
        if (state.getLastErrorCode() == null) {
            return false;
        }
        String code = state.getLastErrorCode().trim();
        return TRANSACTION_IN_PROGRESS.equalsIgnoreCase(code)
                || TRANSACTION_OUTCOME_UNCERTAIN.equalsIgnoreCase(code);
    }

    public static boolean isTransactionInProgress(ScheduledClientMessageState state) {
        return state != null
                && state.getLastErrorCode() != null
                && TRANSACTION_IN_PROGRESS.equalsIgnoreCase(state.getLastErrorCode().trim());
    }
}
