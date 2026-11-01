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
    @Test void deletedSourceDoesNotDiscardLateBankEvidenceOrRequireAnAutomationMutation() {
        var repository=mock(OrderRepository.class);var automation=mock(com.hunt.otziv.client_messages.api.PaperInvoiceAutomation.class);
        var adapter=new PaperInvoiceOrderAdapter(repository,mock(JdbcTemplate.class),mock(ReviewRecoveryMessageHold.class),mock(com.hunt.otziv.p_products.status.service.OrderStatusTransitionService.class),automation,mock(OrderStatusService.class));
        assertDoesNotThrow(()->adapter.holdForPaymentReview(42));verifyNoInteractions(automation);verify(repository,never()).saveAndFlush(any());
    }
    @Test void cancellationRestoresPublishedStatusAndHoldsOnlyPaymentAutomation() {
        var repository=mock(OrderRepository.class);var automation=mock(com.hunt.otziv.client_messages.api.PaperInvoiceAutomation.class);var statuses=mock(OrderStatusService.class);
        var order=Order.builder().id(42L).status(new OrderStatus(7L,"Выставлен счет")).clientMessageGeneration(3).build();
        when(repository.findByIdForCounterUpdate(42L)).thenReturn(java.util.Optional.of(order));
        when(statuses.getOrderStatusByTitle("Опубликовано")).thenReturn(new OrderStatus(6L,"Опубликовано"));
        var adapter=new PaperInvoiceOrderAdapter(repository,mock(JdbcTemplate.class),mock(ReviewRecoveryMessageHold.class),mock(com.hunt.otziv.p_products.status.service.OrderStatusTransitionService.class),automation,statuses);
        adapter.cancelled(42,false);assertTrue(order.isPaperInvoiceAutomationHold());assertEquals("Опубликовано",order.getStatus().getTitle());assertEquals(4,order.getClientMessageGeneration());verify(automation).synchronizeOrder(42);
        adapter.holdForPaymentReview(42);assertTrue(order.isPaperInvoiceReviewHold());assertThrows(ResponseStatusException.class,()->adapter.holdAutomation(42,false));
    }
    @Test void paidCanonicalOrderBlocksCancellationEvenWithoutBankWebhookEvidence() {
        var repository=mock(OrderRepository.class);var order=Order.builder().id(42L).status(new OrderStatus(10L,"Оплачено")).build();
        when(repository.findByIdForCounterUpdate(42L)).thenReturn(java.util.Optional.of(order));
        var adapter=new PaperInvoiceOrderAdapter(repository,mock(JdbcTemplate.class),mock(ReviewRecoveryMessageHold.class),mock(com.hunt.otziv.p_products.status.service.OrderStatusTransitionService.class),mock(com.hunt.otziv.client_messages.api.PaperInvoiceAutomation.class),mock(OrderStatusService.class));
        assertThrows(ResponseStatusException.class,()->adapter.requireUnpaidForCancellation(42));assertThrows(ResponseStatusException.class,()->adapter.cancelled(42,false));
    }
    @Test void completedCounterDoesNotPermitActBeforeRecoveryFinishes() {
        var repository=mock(OrderRepository.class);
        var recovery=mock(ReviewRecoveryMessageHold.class);
        var order=Order.builder().id(42L).amount(1).counter(1).status(new OrderStatus(1L,"Опубликовано")).build();
        when(repository.findByIdForCounterUpdate(42L)).thenReturn(java.util.Optional.of(order));
        var adapter=new PaperInvoiceOrderAdapter(repository,mock(JdbcTemplate.class),recovery,mock(com.hunt.otziv.p_products.status.service.OrderStatusTransitionService.class),mock(com.hunt.otziv.client_messages.api.PaperInvoiceAutomation.class),mock(OrderStatusService.class));
        when(recovery.shouldPauseActiveOrder(42L)).thenReturn(true);
        assertThrows(ResponseStatusException.class,() -> adapter.lockCompletedUnpaid(42));
        when(recovery.shouldPauseActiveOrder(42L)).thenReturn(false);
        assertDoesNotThrow(() -> adapter.lockCompletedUnpaid(42));
    }
}
