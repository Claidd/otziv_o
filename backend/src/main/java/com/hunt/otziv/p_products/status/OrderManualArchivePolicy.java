package com.hunt.otziv.p_products.status;

import com.hunt.otziv.p_products.model.Order;
import java.util.Set;

/**
 * One source of truth for the work statuses from which a user may close an
 * order manually. Financial closing remains a separate workflow.
 */
public final class OrderManualArchivePolicy {

    public static final Set<String> ALLOWED_SOURCE_STATUSES = Set.of(
            "В проверку",
            "На проверке",
            "Коррекция"
    );

    private OrderManualArchivePolicy() {
    }

    public static boolean isAllowed(Order order) {
        return order != null
                && order.getStatus() != null
                && ALLOWED_SOURCE_STATUSES.contains(order.getStatus().getTitle());
    }

    public static String statusTitle(Order order) {
        return order == null || order.getStatus() == null || order.getStatus().getTitle() == null
                ? ""
                : order.getStatus().getTitle().trim();
    }
}

