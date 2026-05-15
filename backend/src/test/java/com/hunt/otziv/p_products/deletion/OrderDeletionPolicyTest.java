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
        Order paidOrder = orderWithStatus("Оплачено");

        assertTrue(policy.canDelete("ROLE_ADMIN", archivedOrder));
        assertTrue(policy.canDelete("ROLE_OWNER", archivedOrder));
        assertTrue(policy.canDelete("ROLE_ADMIN", paidOrder));
        assertTrue(policy.canDelete("ROLE_OWNER", paidOrder));
    }

    @Test
    void managerCanDeleteBeforeApprovedStatuses() {
        assertTrue(policy.canDelete("ROLE_MANAGER", orderWithStatus("Новый")));
        assertTrue(policy.canDelete("manager", orderWithStatus("Новый")));
        assertTrue(policy.canDelete("ROLE_manager", orderWithStatus("Новый")));
        assertTrue(policy.canDelete("ROLE_MANAGER", orderWithStatus("В проверку")));
        assertTrue(policy.canDelete("ROLE_MANAGER", orderWithStatus("На проверке")));
        assertTrue(policy.canDelete("ROLE_MANAGER", orderWithStatus("Коррекция")));
        assertTrue(policy.canDelete("ROLE_MANAGER", orderWithStatus("Архив")));
    }

    @Test
    void managerCannotDeleteApprovedAndPaymentStatuses() {
        assertFalse(policy.canDelete("ROLE_MANAGER", orderWithStatus("Публикация")));
        assertFalse(policy.canDelete("ROLE_MANAGER", orderWithStatus("Одобрено")));
        assertFalse(policy.canDelete("ROLE_MANAGER", orderWithStatus("Опубликовано")));
        assertFalse(policy.canDelete("ROLE_MANAGER", orderWithStatus("Выставлен счет")));
        assertFalse(policy.canDelete("ROLE_MANAGER", orderWithStatus("Напоминание")));
        assertFalse(policy.canDelete("ROLE_MANAGER", orderWithStatus("Не оплачено")));
        assertFalse(policy.canDelete("ROLE_MANAGER", orderWithStatus("Оплачено")));
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
