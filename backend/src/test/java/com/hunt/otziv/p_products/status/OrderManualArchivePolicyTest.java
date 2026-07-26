package com.hunt.otziv.p_products.status;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OrderManualArchivePolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {"В проверку", "На проверке", "Коррекция"})
    void allowsOnlyApprovedWorkStatuses(String status) {
        assertTrue(OrderManualArchivePolicy.isAllowed(order(status)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Новый",
            "Публикация",
            "Опубликовано",
            "Выставлен счет",
            "Напоминание",
            "Не оплачено",
            "Оплачено",
            "Бан",
            "Архив"
    })
    void rejectsOtherStatuses(String status) {
        assertFalse(OrderManualArchivePolicy.isAllowed(order(status)));
    }

    private Order order(String title) {
        OrderStatus status = new OrderStatus();
        status.setTitle(title);
        Order order = new Order();
        order.setStatus(status);
        return order;
    }
}
