package com.hunt.otziv.client_messages.api;

/** Read-only delivery safety projection. Authorized caller supplies the exact current order targets. */
public interface CurrentOrderDeliveryUncertainty {
    boolean hasUncertainCurrentDelivery(long orderId, String orderTarget, String waitingTarget);
    /** Current locking read; caller holds the order lock in a write transaction. */
    boolean hasUncertainCurrentDeliveryForUpdate(long orderId, String orderTarget, String waitingTarget, boolean includeClaimed);
    boolean hasRecentCurrentDelivery(long orderId, String orderTarget, String waitingTarget, java.time.LocalDateTime since);
    boolean hasRecentCurrentDeliveryForUpdate(long orderId, String orderTarget, String waitingTarget, java.time.LocalDateTime since);
    boolean requiresReconciliation(long stateId);
}
