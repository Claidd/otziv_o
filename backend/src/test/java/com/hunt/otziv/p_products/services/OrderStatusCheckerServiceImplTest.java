package com.hunt.otziv.p_products.services;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.client_messages.PaymentInvoiceRetryScheduler;
import com.hunt.otziv.config.email.EmailService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.services.service.OrderStatusService;
import com.hunt.otziv.p_products.status.OrderPaymentMessageBuilder;
import com.hunt.otziv.p_products.status.OrderStatusNotificationService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.whatsapp.service.service.WhatsAppService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderStatusCheckerServiceImplTest {

    @Mock
    private OrderStatusService orderStatusService;

    @Mock
    private EmailService emailService;

    @Mock
    private WhatsAppService whatsAppService;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderStatusNotificationService orderStatusNotificationService;

    @Mock
    private OrderPaymentMessageBuilder orderPaymentMessageBuilder;

    @Mock
    private PaymentInvoiceRetryScheduler paymentInvoiceRetryScheduler;

    @Test
    void validateCounterConsistencySynchronizesExpectedSingleReviewChangeWithoutEmail() {
        Order order = order(42L, 1);

        service().validateCounterConsistency(order, 2);

        assertEquals(2, order.getCounter());
        verify(orderRepository).save(order);
        verify(emailService, never()).sendSimpleEmail(
                eq("2.12nps@mail.ru"),
                eq("Исправлен счетчик заказа"),
                contains("Было: 1. Стало: 2")
        );
    }

    @Test
    void validateCounterConsistencyNotifiesWhenMismatchExceedsSingleReviewChange() {
        Order order = order(45L, 0);

        service().validateCounterConsistency(order, 2);

        assertEquals(2, order.getCounter());
        verify(orderRepository).save(order);
        verify(emailService).sendSimpleEmail(
                eq("2.12nps@mail.ru"),
                eq("Исправлен счетчик заказа"),
                contains("Было: 0. Стало: 2")
        );
    }

    @Test
    void validateCounterConsistencyDoesNothingWhenCounterAlreadyMatches() {
        Order order = order(43L, 2);

        service().validateCounterConsistency(order, 2);

        assertEquals(2, order.getCounter());
        verifyNoInteractions(orderRepository, emailService);
    }

    @Test
    void validateCounterConsistencyKeepsRepairWhenEmailFails() {
        Order order = order(44L, 0);
        doThrow(new RuntimeException("smtp")).when(emailService)
                .sendSimpleEmail(eq("2.12nps@mail.ru"), eq("Исправлен счетчик заказа"), contains("Стало: 3"));

        assertDoesNotThrow(() -> service().validateCounterConsistency(order, 3));

        assertEquals(3, order.getCounter());
        verify(orderRepository).save(order);
    }

    @Test
    void checkAndMarkOrderCompletedSchedulesPaymentInvoiceRetryWhenClientMessageFails() throws Exception {
        Order order = payableOrder(50L);

        when(orderPaymentMessageBuilder.publishedOrderPaymentMessage(order)).thenReturn("счет");
        when(orderStatusNotificationService.sendMessageToClientChat(
                eq("Опубликовано"),
                eq(order),
                eq("client-50"),
                eq("group-50"),
                eq("счет"),
                eq("Выставлен счет")
        )).thenReturn("Опубликовано");

        service().checkAndMarkOrderCompleted(order);

        verify(paymentInvoiceRetryScheduler).scheduleRetry(order);
    }

    @Test
    void checkAndMarkOrderCompletedDoesNotScheduleRetryWhenInvoiceWasSent() throws Exception {
        Order order = payableOrder(51L);

        when(orderPaymentMessageBuilder.publishedOrderPaymentMessage(order)).thenReturn("счет");
        when(orderStatusNotificationService.sendMessageToClientChat(
                eq("Опубликовано"),
                eq(order),
                eq("client-51"),
                eq("group-51"),
                eq("счет"),
                eq("Выставлен счет")
        )).thenReturn("Выставлен счет");

        service().checkAndMarkOrderCompleted(order);

        verify(paymentInvoiceRetryScheduler, never()).scheduleRetry(order);
    }

    private OrderStatusCheckerServiceImpl service() {
        return new OrderStatusCheckerServiceImpl(
                emailService,
                orderRepository,
                orderStatusNotificationService,
                orderPaymentMessageBuilder,
                paymentInvoiceRetryScheduler
        );
    }

    private Order order(Long id, int counter) {
        User user = new User();
        user.setFio("Специалист");

        Worker worker = new Worker();
        worker.setUser(user);

        Company company = new Company();
        company.setTitle("Компания");

        Order order = new Order();
        order.setId(id);
        order.setCounter(counter);
        order.setWorker(worker);
        order.setCompany(company);
        return order;
    }

    private Order payableOrder(Long id) {
        Order order = order(id, 1);
        order.setAmount(1);

        Manager manager = new Manager();
        manager.setClientId("client-" + id);
        order.setManager(manager);
        order.getCompany().setGroupId("group-" + id);
        return order;
    }
}
