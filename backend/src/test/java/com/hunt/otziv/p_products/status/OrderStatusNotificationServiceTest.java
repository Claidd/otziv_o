package com.hunt.otziv.p_products.status;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.services.service.OrderStatusService;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.whatsapp.service.service.WhatsAppService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderStatusNotificationServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderStatusService orderStatusService;

    @Mock
    private WhatsAppService whatsAppService;

    @Mock
    private TelegramService telegramService;

    @Test
    void sendMessageToGroupSetsSuccessStatusWhenWhatsAppReturnsOk() {
        OrderStatusNotificationService service = service();
        Order order = orderWithManager(10L, "Компания", 123L);
        OrderStatus success = status("На проверке");

        when(whatsAppService.sendMessageToGroup("client", "group", "message")).thenReturn("ok");
        when(orderStatusService.getOrderStatusByTitle("На проверке")).thenReturn(success);

        boolean result = service.sendMessageToGroup(
                "В проверку",
                order,
                "client",
                "group",
                "message",
                "На проверке"
        );

        assertTrue(result);
        assertSame(success, order.getStatus());
        verify(orderRepository).save(order);
        verifyNoInteractions(telegramService);
    }

    @Test
    void sendMessageToGroupFallsBackToTelegramForToCheckWhenWhatsAppFails() {
        OrderStatusNotificationService service = service();
        Order order = orderWithManager(10L, "Компания", 123L);
        OrderStatus fallback = status("В проверку");

        when(whatsAppService.sendMessageToGroup("client", "group", "message")).thenReturn("error");
        when(orderStatusService.getOrderStatusByTitle("В проверку")).thenReturn(fallback);

        boolean result = service.sendMessageToGroup(
                "В проверку",
                order,
                "client",
                "group",
                "message",
                "На проверке"
        );

        assertTrue(result);
        assertSame(fallback, order.getStatus());
        verify(telegramService).sendMessage(
                123L,
                "Компания готов - На проверку\nhttps://o-ogo.ru/orders/all_orders?status=В%20проверку"
        );
        verify(orderRepository).save(order);
    }

    @Test
    void sendMessageToGroupFallsBackToTelegramForPublicWhenWhatsAppFails() {
        OrderStatusNotificationService service = service();
        Order order = orderWithManager(10L, "Компания", 123L);
        OrderStatus fallback = status("Опубликовано");

        when(whatsAppService.sendMessageToGroup("client", "group", "message")).thenReturn(null);
        when(orderStatusService.getOrderStatusByTitle("Опубликовано")).thenReturn(fallback);

        boolean result = service.sendMessageToGroup(
                "Опубликовано",
                order,
                "client",
                "group",
                "message",
                "Выставлен счет"
        );

        assertTrue(result);
        assertSame(fallback, order.getStatus());
        verify(telegramService).sendMessage(
                123L,
                "Компания Опубликован\nhttps://o-ogo.ru/orders/all_orders?status=Опубликовано"
        );
        verify(orderRepository).save(order);
    }

    @Test
    void hasWorkerWithTelegramRequiresWorkerChatDetailsAndCompany() {
        OrderStatusNotificationService service = service();
        Company company = company("Компания");
        Worker worker = new Worker();
        worker.setUser(user(321L));

        Order order = new Order();
        order.setCompany(company);
        order.setWorker(worker);
        order.setDetails(List.of(new OrderDetails()));

        assertTrue(service.hasWorkerWithTelegram(order));

        order.setDetails(List.of());
        assertFalse(service.hasWorkerWithTelegram(order));
    }

    private OrderStatusNotificationService service() {
        return new OrderStatusNotificationService(
                orderRepository,
                orderStatusService,
                whatsAppService,
                telegramService
        );
    }

    private Order orderWithManager(Long id, String companyTitle, Long telegramChatId) {
        Order order = new Order();
        order.setId(id);
        order.setCompany(company(companyTitle));

        Manager manager = new Manager();
        manager.setUser(user(telegramChatId));
        order.setManager(manager);

        OrderDetails detail = new OrderDetails();
        detail.setOrder(order);
        order.setDetails(List.of(detail));
        return order;
    }

    private Company company(String title) {
        Company company = new Company();
        company.setTitle(title);
        return company;
    }

    private User user(Long telegramChatId) {
        User user = new User();
        user.setTelegramChatId(telegramChatId);
        return user;
    }

    private OrderStatus status(String title) {
        OrderStatus status = new OrderStatus();
        status.setTitle(title);
        return status;
    }
}
