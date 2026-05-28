package com.hunt.otziv.p_products.services;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.mobile_push.service.MobilePushBusinessNotificationService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderCreatedNotificationListenerTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private TelegramService telegramService;

    @Mock
    private MobilePushBusinessNotificationService mobilePushBusinessNotificationService;

    @Test
    void notifyWorkerLoadsCreatedOrderAndSendsNotifications() {
        OrderCreatedNotificationListener listener = new OrderCreatedNotificationListener(
                orderRepository,
                telegramService,
                mobilePushBusinessNotificationService
        );
        Order order = order();

        when(orderRepository.findByIdForOrderDto(55L)).thenReturn(Optional.of(order));

        listener.notifyWorker(new OrderCreatedEvent(55L));

        verify(telegramService).sendMessage(100L, "У вас новый заказ для: Компания");
        verify(mobilePushBusinessNotificationService).notifyWorkerNewOrder(order);
    }

    private Order order() {
        Company company = new Company();
        company.setTitle("Компания");

        User user = new User();
        user.setTelegramChatId(100L);

        Worker worker = new Worker();
        worker.setId(7L);
        worker.setUser(user);

        Order order = new Order();
        order.setId(55L);
        order.setCompany(company);
        order.setWorker(worker);
        return order;
    }
}
