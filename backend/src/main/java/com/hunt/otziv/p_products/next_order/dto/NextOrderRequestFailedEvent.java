package com.hunt.otziv.p_products.next_order.dto;

public record NextOrderRequestFailedEvent(Long requestId, Long sourceOrderId, Throwable cause) {
}
