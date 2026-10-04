package com.hunt.otziv.p_products.api;

import java.util.Optional;

/** Order-owned contact projection for an internal manager payment reminder. */
public interface PaymentAttentionOrderReader {
    record Contact(long managerUserId, String companyTitle, String chatUrl) {}

    Optional<Contact> awaitingPayment(long orderId);
}
