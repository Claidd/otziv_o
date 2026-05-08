package com.hunt.otziv.p_products.services;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.config.email.EmailService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.services.service.OrderStatusService;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
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

@ExtendWith(MockitoExtension.class)
class OrderStatusCheckerServiceImplTest {

    @Mock
    private OrderStatusService orderStatusService;

    @Mock
    private TelegramService telegramService;

    @Mock
    private EmailService emailService;

    @Mock
    private WhatsAppService whatsAppService;

    @Mock
    private OrderRepository orderRepository;

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

    private OrderStatusCheckerServiceImpl service() {
        return new OrderStatusCheckerServiceImpl(
                orderStatusService,
                telegramService,
                emailService,
                whatsAppService,
                orderRepository
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
}
