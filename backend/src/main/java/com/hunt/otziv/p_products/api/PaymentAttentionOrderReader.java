package com.hunt.otziv.p_products.api;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/** Order-owned contact projection for an internal manager payment reminder. */
public interface PaymentAttentionOrderReader {
    record Contact(long managerUserId, String companyTitle, String chatUrl) {}

    Optional<Contact> awaitingPayment(long orderId);

    /** Company and current status of an order, for «Банк → Требует решения». */
    record OrderLabel(long orderId, String companyTitle, String statusTitle) {}

    /** Labels of the given live orders; an archived or missing order is absent from the map. */
    Map<Long, OrderLabel> labels(Collection<Long> orderIds);
}
