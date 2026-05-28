package com.hunt.otziv.client_messages;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.CompanyStatus;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class StatusChangedAtTest {

    @Test
    void orderStatusSetterTouchesStatusChangedAt() {
        Order order = new Order();
        assertNull(order.getStatusChangedAt());

        order.setStatus(OrderStatus.builder().id(1L).title("Новый").build());

        assertNotNull(order.getStatusChangedAt());
    }

    @Test
    void companyStatusSetterTouchesStatusChangedAt() {
        Company company = new Company();
        assertNull(company.getStatusChangedAt());

        company.setStatus(CompanyStatus.builder().id(1L).title("Новая").build());

        assertNotNull(company.getStatusChangedAt());
    }

    @Test
    void sameOrderStatusDoesNotRefreshStatusChangedAt() {
        Order order = new Order();
        order.setStatus(OrderStatus.builder().id(1L).title("Новый").build());
        LocalDateTime first = order.getStatusChangedAt();

        order.setStatus(OrderStatus.builder().id(1L).title("Новый").build());

        org.junit.jupiter.api.Assertions.assertEquals(first, order.getStatusChangedAt());
    }
}
