package com.hunt.otziv.payments.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.status.service.OrderStatusNotificationService;
import com.hunt.otziv.payments.dto.ManagerPaymentLinkResponse;
import com.hunt.otziv.payments.repository.PaymentRouteChangeNotificationOutboxRepository;
import com.hunt.otziv.payments.repository.PaymentRouteChangeNotificationOutboxRepository.Delivery;
import com.hunt.otziv.u_users.model.Manager;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentRouteChangeNotificationWorkerTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderStatusNotificationService notificationService;

    @Mock
    private PaymentIssueReminderService paymentIssueReminderService;

    @Mock
    private PaymentRouteChangeNotificationOutboxRepository outboxRepository;

    @Mock
    private PaymentLinkService paymentLinkService;

    @Mock
    private Order order;

    @Mock
    private Company company;

    @Mock
    private Manager manager;

    @Test
    void sendsReplacementDetailsToActiveClientChatWithCopyButton() {
        Delivery delivery = delivery(1);
        when(outboxRepository.tryAcquire(eq(22L), anyString(), anyString(), any(Duration.class)))
                .thenReturn(Optional.of(delivery));
        when(orderRepository.findByIdForCounterUpdate(8L)).thenReturn(Optional.of(order));
        when(outboxRepository.isCurrentReplacement(8L, 22L)).thenReturn(true);
        when(paymentLinkService.paymentRouteChangeNotificationDetails(22L)).thenReturn(payment());
        when(order.getCompany()).thenReturn(company);
        when(order.getManager()).thenReturn(manager);
        when(manager.getClientId()).thenReturn("client");
        when(company.getGroupId()).thenReturn("group");
        when(notificationService.sendInformationalMessageToClientChat(
                order, "client", "group", "По вашей просьбе способ оплаты изменен. Используйте новые реквизиты.\n\nПолный текст",
                "Новые реквизиты оплаты", "89140000000"
        )).thenReturn(true);
        when(outboxRepository.markSent(delivery)).thenReturn(true);
        PaymentRouteChangeNotificationWorker worker = worker();

        worker.send(22L);

        verify(outboxRepository).markSent(delivery);
        verify(paymentIssueReminderService).resolveOrderIssue(
                8L, "PAYMENT_ROUTE_CHANGE_DELIVERY", 22L
        );
        verify(paymentIssueReminderService, never()).notifyOrderIssue(
                anyLong(), anyString(), anyLong(), anyString(), anyString()
        );
    }

    @Test
    void createsManagerAndOwnerReminderWhenNewDetailsAreNotDelivered() {
        Delivery delivery = delivery(2);
        when(outboxRepository.tryAcquire(eq(22L), anyString(), anyString(), any(Duration.class)))
                .thenReturn(Optional.of(delivery));
        when(orderRepository.findByIdForCounterUpdate(8L)).thenReturn(Optional.of(order));
        when(outboxRepository.isCurrentReplacement(8L, 22L)).thenReturn(true);
        when(paymentLinkService.paymentRouteChangeNotificationDetails(22L)).thenReturn(payment());
        when(order.getCompany()).thenReturn(company);
        when(order.getManager()).thenReturn(manager);
        when(manager.getClientId()).thenReturn("client");
        when(company.getGroupId()).thenReturn("group");
        when(notificationService.sendInformationalMessageToClientChat(
                order, "client", "group", "По вашей просьбе способ оплаты изменен. Используйте новые реквизиты.\n\nПолный текст",
                "Новые реквизиты оплаты", "89140000000"
        )).thenReturn(false);
        when(outboxRepository.markFailed(
                eq(delivery),
                eq("Активный клиентский чат недоступен"),
                any(Duration.class)
        )).thenReturn(true);
        PaymentRouteChangeNotificationWorker worker = worker();

        worker.send(22L);

        verify(outboxRepository).markFailed(eq(delivery), eq("Активный клиентский чат недоступен"),
                any(Duration.class));
        verify(paymentIssueReminderService).notifyOrderIssue(
                eq(8L),
                eq("PAYMENT_ROUTE_CHANGE_DELIVERY"),
                eq(22L),
                eq("Новые реквизиты не отправлены: заказ №8"),
                contains("Откройте чат и отправьте реквизиты вручную")
        );
    }

    @Test
    void neverSendsStaleReplacementAfterAnotherRouteChange() {
        Delivery delivery = delivery(1);
        when(outboxRepository.tryAcquire(eq(22L), anyString(), anyString(), any(Duration.class)))
                .thenReturn(Optional.of(delivery));
        when(orderRepository.findByIdForCounterUpdate(8L)).thenReturn(Optional.of(order));
        when(outboxRepository.isCurrentReplacement(8L, 22L)).thenReturn(false);
        when(outboxRepository.markSkipped(delivery, "replacement_payment_link_is_not_current"))
                .thenReturn(true);

        worker().send(22L);

        verify(outboxRepository).markSkipped(delivery, "replacement_payment_link_is_not_current");
        verifyNoInteractions(notificationService);
        verifyNoInteractions(paymentIssueReminderService);
    }

    @Test
    void enqueuePersistsSnapshotBeforeAnyDeliveryAttempt() {
        ManagerPaymentLinkResponse payment = payment();
        when(outboxRepository.enqueue(8L, 22L))
                .thenReturn(true);

        worker().enqueue(8L, 22L, payment);

        verify(outboxRepository).enqueue(8L, 22L);
        verifyNoInteractions(orderRepository, notificationService, paymentIssueReminderService);
    }

    private PaymentRouteChangeNotificationWorker worker() {
        return new PaymentRouteChangeNotificationWorker(
                orderRepository,
                notificationService,
                paymentIssueReminderService,
                outboxRepository,
                paymentLinkService
        );
    }

    private Delivery delivery(int attemptCount) {
        return new Delivery(22L, 8L, attemptCount, "claim");
    }

    private ManagerPaymentLinkResponse payment() {
        return new ManagerPaymentLinkResponse(
                "token", null, 8L, BigDecimal.valueOf(1_000), 100_000L,
                "WAITING_MANUAL_PAYMENT", "MANUAL_MOBILE_BANK", null,
                "Реквизиты", "Полный текст", "89140000000"
        );
    }
}
