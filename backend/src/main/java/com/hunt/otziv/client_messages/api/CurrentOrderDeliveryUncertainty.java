package com.hunt.otziv.client_messages.api;

/** Read-only delivery safety projection. Authorized caller supplies the exact current order targets. */
public interface CurrentOrderDeliveryUncertainty {
    boolean hasUncertainCurrentDelivery(long orderId, String orderTarget, String waitingTarget);
    boolean requiresReconciliation(long stateId);
}
