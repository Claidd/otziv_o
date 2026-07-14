package com.hunt.otziv.p_products.next_order;

public record NextOrderRequestSummary(
        int openCount,
        int failedCount,
        String latestFilialTitle,
        String latestError
) {
}
