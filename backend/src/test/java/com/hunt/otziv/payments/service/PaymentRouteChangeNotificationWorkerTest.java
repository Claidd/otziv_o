package com.hunt.otziv.payments.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.status.service.OrderStatusNotificationService;
import com.hunt.otziv.payments.dto.ManagerPaymentLinkResponse;
import com.hunt.otziv.u_users.model.Manager;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    private Order order;

    @Mock
    private Company company;

    @Mock
    private Manager manager;

    @Test
    void sendsReplacementDetailsToActiveClientChatWithCopyButton() {
        ManagerPaymentLinkResponse payment = payment();
        when(orderRepository.findByIdForMutation(8L)).thenReturn(Optional.of(order));
        when(order.getCompany()).thenReturn(company);
        when(order.getManager()).thenReturn(manager);
        when(manager.getClientId()).thenReturn("client");
        when(company.getGroupId()).thenReturn("group");
        when(notificationService.sendInformationalMessageToClientChat(
                order, "client", "group", "По вашей просьбе способ оплаты изменен. Используйте новые реквизиты.\n\nПолный текст",
                "Новые реквизиты оплаты", "89140000000"
        )).thenReturn(true);
        PaymentRouteChangeNotificationWorker worker = worker();

        worker.send(8L, 22L, payment);

        verifyNoInteractions(paymentIssueReminderService);
    }

    @Test
    void createsManagerAndOwnerReminderWhenNewDetailsAreNotDelivered() {
        ManagerPaymentLinkResponse payment = payment();
        when(orderRepository.findByIdForMutation(8L)).thenReturn(Optional.of(order));
        when(order.getCompany()).thenReturn(company);
        when(order.getManager()).thenReturn(manager);
        when(manager.getClientId()).thenReturn("client");
        when(company.getGroupId()).thenReturn("group");
        when(notificationService.sendInformationalMessageToClientChat(
                order, "client", "group", "По вашей просьбе способ оплаты изменен. Используйте новые реквизиты.\n\nПолный текст",
                "Новые реквизиты оплаты", "89140000000"
        )).thenReturn(false);
        PaymentRouteChangeNotificationWorker worker = worker();

        worker.send(8L, 22L, payment);

        verify(paymentIssueReminderService).notifyOrderIssue(
                eq(8L),
                eq("PAYMENT_ROUTE_CHANGE_DELIVERY"),
                eq(22L),
                eq("Новые реквизиты не отправлены: заказ №8"),
                contains("Откройте чат и отправьте реквизиты вручную")
        );
    }

    private PaymentRouteChangeNotificationWorker worker() {
        return new PaymentRouteChangeNotificationWorker(
                orderRepository, notificationService, paymentIssueReminderService
        );
    }

    private ManagerPaymentLinkResponse payment() {
        return new ManagerPaymentLinkResponse(
                "token", null, 8L, BigDecimal.valueOf(1_000), 100_000L,
                "WAITING_MANUAL_PAYMENT", "MANUAL_MOBILE_BANK", null,
                "Реквизиты", "Полный текст", "89140000000"
        );
    }
}
