package com.hunt.otziv.p_products.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.review_recovery.api.ReviewRecoveryMessageHold;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class PaperInvoiceOrderAdapterTest {
    @Test void completedCounterDoesNotPermitActBeforeRecoveryFinishes() {
        var repository=mock(OrderRepository.class);
        var recovery=mock(ReviewRecoveryMessageHold.class);
        var order=Order.builder().id(42L).amount(1).counter(1).status(new OrderStatus(1L,"Опубликовано")).build();
        when(repository.findByIdForCounterUpdate(42L)).thenReturn(java.util.Optional.of(order));
        var adapter=new PaperInvoiceOrderAdapter(repository,mock(JdbcTemplate.class),recovery);
        when(recovery.shouldPauseActiveOrder(42L)).thenReturn(true);
        assertThrows(ResponseStatusException.class,() -> adapter.lockCompletedUnpaid(42));
        when(recovery.shouldPauseActiveOrder(42L)).thenReturn(false);
        assertDoesNotThrow(() -> adapter.lockCompletedUnpaid(42));
    }
}
