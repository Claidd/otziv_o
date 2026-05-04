package com.hunt.otziv.p_products.deletion;

import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderDeletionPolicyTest {

    private final OrderDeletionPolicy policy = new OrderDeletionPolicy();

    @Test
    void adminAndOwnerCanDeleteAnyOrderStatus() {
        Order archivedOrder = orderWithStatus("Архив");

        assertTrue(policy.canDelete("ROLE_ADMIN", archivedOrder));
        assertTrue(policy.canDelete("ROLE_OWNER", archivedOrder));
    }

    @Test
    void managerCanDeleteOnlyNewOrders() {
        assertTrue(policy.canDelete("ROLE_MANAGER", orderWithStatus("Новый")));
        assertFalse(policy.canDelete("ROLE_MANAGER", orderWithStatus("Публикация")));
        assertFalse(policy.canDelete("ROLE_MANAGER", orderWithStatus(null)));
    }

    @Test
    void workerCannotDeleteOrder() {
        assertFalse(policy.canDelete("ROLE_WORKER", orderWithStatus("Новый")));
    }

    private Order orderWithStatus(String title) {
        OrderStatus status = new OrderStatus();
        status.setTitle(title);

        Order order = new Order();
        order.setStatus(status);
        return order;
    }
}
