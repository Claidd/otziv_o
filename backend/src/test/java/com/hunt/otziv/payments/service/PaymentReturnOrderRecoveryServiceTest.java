package com.hunt.otziv.payments.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.status.service.OrderStatusTransitionService;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class PaymentReturnOrderRecoveryServiceTest {

    @Mock
    private PaymentLinkRepository paymentLinkRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderStatusTransitionService orderStatusTransitionService;

    @Mock
    private PaymentLinkService paymentLinkService;

    @Test
    void fullRefundReopensOrderWithoutPreparingReplacementInsideStatusTransaction() throws Exception {
        Order order = order(42L, "Оплачено");
        PaymentLink link = link(7L, PaymentLinkStatus.REFUNDED, order);
        link.setConfirmedAmountKopecks(10_000L);
        when(paymentLinkRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(link));

        PaymentReturnOrderRecoveryService service = service();

        assertEquals(
                Optional.of(42L),
                service.reopenAfterFullReturn(new PaymentReturnOrderRecoveryService.PaymentLinkReturnOutboxClaim(
                        7L, PaymentLinkStatus.REFUNDED))
        );

        verify(orderStatusTransitionService).changeStatusAfterPaymentReturn(42L, "Напоминание");
        verify(paymentLinkService, never()).createForOrder(42L);
    }

    @Test
    void createReplacementPaymentRouteDelegatesAfterStatusTransaction() throws Exception {
        service().createReplacementPaymentRoute(42L);

        verify(paymentLinkService).createForOrder(42L);
    }

    @Test
    void historicalReturnDoesNotReopenOrderWhenNewerPaymentIsAlreadyConfirmed() throws Exception {
        Order order = order(42L, "Оплачено");
        PaymentLink link = link(7L, PaymentLinkStatus.REFUNDED, order);
        LocalDateTime returnedAt = LocalDateTime.of(2026, 5, 26, 1, 5);
        link.setPaidAt(returnedAt);
        when(paymentLinkRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(link));
        when(paymentLinkRepository.existsNewerConfirmedPayment(42L, 7L, returnedAt)).thenReturn(true);

        assertEquals(Optional.empty(), service().reopenAfterFullReturn(
                new PaymentReturnOrderRecoveryService.PaymentLinkReturnOutboxClaim(
                        7L, PaymentLinkStatus.REFUNDED)));

        verify(orderStatusTransitionService, never()).changeStatusAfterPaymentReturn(42L, "Напоминание");
        verify(paymentLinkService, never()).createForOrder(42L);
    }

    @Test
    void historicalReturnDoesNotReopenOrderWhenNewerManualPaidClosureExists() throws Exception {
        Order order = order(42L, "Оплачено");
        PaymentLink link = link(7L, PaymentLinkStatus.REFUNDED, order);
        LocalDateTime returnedAt = LocalDateTime.of(2026, 5, 26, 1, 5);
        link.setPaidAt(returnedAt);
        when(paymentLinkRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(link));
        when(paymentLinkRepository.existsNewerManualPaidClosure(42L, 7L, returnedAt)).thenReturn(true);

        assertEquals(Optional.empty(), service().reopenAfterFullReturn(
                new PaymentReturnOrderRecoveryService.PaymentLinkReturnOutboxClaim(
                        7L, PaymentLinkStatus.REFUNDED)));

        verify(orderStatusTransitionService, never()).changeStatusAfterPaymentReturn(42L, "Напоминание");
        verify(paymentLinkService, never()).createForOrder(42L);
    }

    @Test
    void disabledPaymentLinksStillLeaveReminderForRetry() throws Exception {
        when(paymentLinkService.createForOrder(42L)).thenThrow(new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Платежные ссылки выключены"
        ));

        assertDoesNotThrow(() -> service().createReplacementPaymentRoute(42L));

        verify(paymentLinkService).createForOrder(42L);
    }

    @Test
    void unresolvedManualTaskRouteStillLeavesReminderForRetry() throws Exception {
        when(paymentLinkService.createForOrder(42L)).thenThrow(new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Получатель платёжного задания не привязан; оплату нужно сверить вручную"
        ));

        assertDoesNotThrow(() -> service().createReplacementPaymentRoute(42L));

        verify(paymentLinkService).createForOrder(42L);
    }

    @Test
    void existingBankPaymentStillLeavesReminderForRetry() throws Exception {
        when(paymentLinkService.createForOrder(42L)).thenThrow(new ResponseStatusException(
                HttpStatus.CONFLICT,
                "У заказа уже есть созданный банковский платеж. Проверьте его статус перед новым счетом."
        ));

        assertDoesNotThrow(() -> service().createReplacementPaymentRoute(42L));

        verify(paymentLinkService).createForOrder(42L);
    }

    @Test
    void disabledPaymentLinksStillCommitReminderStateBeforeRouteRetry() throws Exception {
        Order order = order(42L, "Оплачено");
        PaymentLink link = link(7L, PaymentLinkStatus.REFUNDED, order);
        link.setConfirmedAmountKopecks(10_000L);
        when(paymentLinkRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(link));

        assertEquals(Optional.of(42L), service().reopenAfterFullReturn(
                new PaymentReturnOrderRecoveryService.PaymentLinkReturnOutboxClaim(
                        7L, PaymentLinkStatus.REFUNDED)));

        verify(orderStatusTransitionService).changeStatusAfterPaymentReturn(42L, "Напоминание");
        verify(paymentLinkService, never()).createForOrder(42L);
    }

    @Test
    void retryAfterOrderAlreadyReopenedIsIdempotent() throws Exception {
        Order order = order(42L, "Напоминание");
        PaymentLink link = link(7L, PaymentLinkStatus.REVERSED, order);
        link.setPaidAt(java.time.LocalDateTime.now());
        when(paymentLinkRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(link));

        assertEquals(Optional.of(42L), service().reopenAfterFullReturn(
                new PaymentReturnOrderRecoveryService.PaymentLinkReturnOutboxClaim(
                        7L, PaymentLinkStatus.REVERSED)));

        verify(orderStatusTransitionService, never()).changeStatusAfterPaymentReturn(42L, "Напоминание");
        verify(paymentLinkService, never()).createForOrder(42L);
    }

    @Test
    void partialReturnDoesNotCreateAnotherPaymentCycle() {
        assertEquals(Optional.empty(), service().reopenAfterFullReturn(
                new PaymentReturnOrderRecoveryService.PaymentLinkReturnOutboxClaim(
                        7L, PaymentLinkStatus.PARTIAL_REFUNDED)));
        verify(paymentLinkRepository, never()).findByIdForUpdate(7L);
        verify(paymentLinkService, never()).createForOrder(7L);
    }

    @Test
    void cancellationWithoutSettledEvidenceDoesNotReopenOrder() throws Exception {
        Order order = order(42L, "Выставлен счет");
        PaymentLink link = link(7L, PaymentLinkStatus.CANCELED, order);
        when(paymentLinkRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(link));

        assertEquals(Optional.empty(), service().reopenAfterFullReturn(
                new PaymentReturnOrderRecoveryService.PaymentLinkReturnOutboxClaim(
                        7L, PaymentLinkStatus.CANCELED)));
        verify(orderStatusTransitionService, never()).changeStatusForOrder(42L, "Не оплачено");
        verify(paymentLinkService, never()).createForOrder(42L);
    }

    private PaymentReturnOrderRecoveryService service() {
        return new PaymentReturnOrderRecoveryService(
                paymentLinkRepository,
                orderRepository,
                orderStatusTransitionService,
                paymentLinkService
        );
    }

    private Order order(Long id, String statusTitle) {
        Order order = new Order();
        order.setId(id);
        order.setStatus(OrderStatus.builder().title(statusTitle).build());
        return order;
    }

    private PaymentLink link(Long id, PaymentLinkStatus status, Order order) {
        PaymentLink link = new PaymentLink();
        link.setId(id);
        link.setStatus(status);
        link.setOrder(order);
        return link;
    }
}
