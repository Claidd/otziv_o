package com.hunt.otziv.client_messages.api;

/** Serializes scheduled preparation with manager contact. No transport calls or card mutations. */
public interface OrderManualContactReservation {
    /** Caller write transaction required. Locks the order until the prepared envelope is committed. */
    boolean blocksScheduledContact(long orderId);
}
