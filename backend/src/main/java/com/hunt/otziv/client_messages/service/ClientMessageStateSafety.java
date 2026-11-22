package com.hunt.otziv.client_messages.service;

import com.hunt.otziv.client_messages.api.ClientMessageStateDecisions;
import com.hunt.otziv.client_messages.model.ScheduledClientMessageState;
import com.hunt.otziv.client_messages.model.ScheduledMessageStateStatus;

public final class ClientMessageStateSafety {

    /** Closed by an administrator from the monitor («Пометить выполненным»). */
    public static final String MANUAL_DONE = ClientMessageStateDecisions.MANUAL_DONE;
    /** Disabled by an administrator from the monitor («Отключить»). */
    public static final String MANUAL_DISABLED = ClientMessageStateDecisions.MANUAL_DISABLED;

    /** See {@link ClientMessageStateDecisions#isManualDecision}: one rule for the autoresponder and manager control. */
    public static boolean isManualDecision(ScheduledClientMessageState state) {
        return ClientMessageStateDecisions.isManualDecision(state);
    }

    public static final String TRANSACTION_IN_PROGRESS = "state_transaction_in_progress";
    /**
     * The processing transaction of a claimed task rolled back before its preparation was committed. Transport is only
     * called after a committed PREPARED, so the message provably was not sent: the task goes back to the queue.
     */
    public static final String ROLLED_BACK_BEFORE_SEND = "rolled_back_before_send";
    /** An unconfirmed send was queued again as a new operation (by the manager, or once automatically after 24 h). */
    public static final String UNCONFIRMED_RESEND_QUEUED = "unconfirmed_resend_queued";
    /** The silence archive could not read the client's chat for 2 days past its due time: the manager decides. */
    public static final String ARCHIVE_CHECK_UNDECIDED = "archive_check_undecided";
    public static final String TRANSACTION_OUTCOME_UNCERTAIN = "state_transaction_outcome_uncertain";
    public static final String ROLLBACK_RECEIPT_ONLY = "rollback_receipt_only";
    public static final String MANUAL_DELIVERY_HANDOFF_PENDING = "manual_delivery_handoff_pending";
    public static final String LEGACY_PREPARATION_UNVERIFIED = "legacy_operation_unverified";
    public static final String DELIVERY_PREPARED = "PREPARED";
    public static final String DELIVERY_OUTCOME_UNKNOWN = "UNKNOWN";
    public static final String CONFIRMED_NOT_DISPATCHED = ClientMessageStateDecisions.CONFIRMED_NOT_DISPATCHED;

    private ClientMessageStateSafety() {
    }

    public static boolean isQueuedAfterWhatsAppRefusal(ScheduledClientMessageState state) {
        return ClientMessageStateDecisions.isQueuedAfterWhatsAppRefusal(state);
    }

    public static boolean onlyReconcileReceipt(ScheduledClientMessageState state) {
        return state != null && (ROLLBACK_RECEIPT_ONLY.equals(state.getLastErrorCode())
                || MANUAL_DELIVERY_HANDOFF_PENDING.equals(state.getLastErrorCode()));
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
                || TRANSACTION_OUTCOME_UNCERTAIN.equalsIgnoreCase(code)
                || LEGACY_PREPARATION_UNVERIFIED.equalsIgnoreCase(code);
    }

    public static boolean isLegacyPreparationFailure(ScheduledClientMessageState state) {
        return state != null && (LEGACY_PREPARATION_UNVERIFIED.equals(state.getLastErrorCode())
                || (TRANSACTION_OUTCOME_UNCERTAIN.equals(state.getLastErrorCode())
                && state.getLastErrorMessage() != null
                && state.getLastErrorMessage().endsWith("Причина: legacy_operation_unverified")));
    }

    public static boolean isTransactionInProgress(ScheduledClientMessageState state) {
        return state != null
                && state.getLastErrorCode() != null
                && TRANSACTION_IN_PROGRESS.equalsIgnoreCase(state.getLastErrorCode().trim());
    }
}
