package com.hunt.otziv.p_products.status;

public record OrderStatusChangedEvent(
        Long orderId,
        String oldStatus,
        String newStatus,
        String requestedStatus
) {
}
