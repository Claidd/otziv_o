package com.hunt.otziv.p_products.status.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.client_messages.service.PublicationProgressPreferenceService;
import com.hunt.otziv.maxbot.service.MaxBotClient;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.service.OrderStatusService;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.whatsapp.service.service.WhatsAppService;
import com.hunt.otziv.whatsapp.service.WhatsAppAuthAlertService;
import java.util.List;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Mock;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
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

    @Mock
    private MaxBotClient maxBotClient;
    @Mock
    private WhatsAppAuthAlertService whatsAppAuthAlertService;

    @Mock
    private PublicationProgressPreferenceService publicationProgressPreferenceService;

    @Test
    void sendMessageToGroupSetsSuccessStatusWhenWhatsAppReturnsOk() {
        OrderStatusNotificationService service = service();
        Order order = orderWithManager(10L, "Компания", 123L);
        OrderStatus success = status("На проверке");

        when(whatsAppService.sendMessageToGroup(org.mockito.ArgumentMatchers.eq("client"), org.mockito.ArgumentMatchers.eq("group"), org.mockito.ArgumentMatchers.eq("message"), org.mockito.ArgumentMatchers.anyString())).thenAnswer(call -> "{\"status\":\"ok\",\"state\":\"SUCCEEDED\",\"operationId\":\""+call.getArgument(3)+"\",\"messageId\":\"fixture-receipt\"}");
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
        verify(whatsAppAuthAlertService).notifyRecovered(
                eq("client"),
                eq("моментальная отправка клиенту"),
                any(),
                any()
        );
        verifyNoInteractions(telegramService);
        verifyNoInteractions(maxBotClient);
    }

    @Test
    void sendMessageToGroupUsesTelegramWhenChatLinkPointsToTelegram() {
        OrderStatusNotificationService service = service();
        Order order = orderWithManager(10L, "Компания", 123L);
        order.getCompany().setUrlChat("https://t.me/shared_owner");
        order.getCompany().setTelegramGroupChatId(-100L);
        OrderStatus success = status("На проверке");

        when(telegramService.sendMessageOnceWithInlineKeyboardMessageId(eq(-100L),eq("message"),org.mockito.ArgumentMatchers.isNull(),org.mockito.ArgumentMatchers.anyList())).thenReturn(java.util.Optional.of(71));
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
        verify(telegramService).sendMessageOnceWithInlineKeyboardMessageId(eq(-100L),eq("message"),org.mockito.ArgumentMatchers.isNull(),org.mockito.ArgumentMatchers.anyList());
        verify(orderRepository).save(order);
        verifyNoInteractions(whatsAppService);
        verifyNoInteractions(maxBotClient);
    }

    @Test
    void telegramPaymentUsesCopyButtonFromFrozenTransferNumber() {
        OrderStatusNotificationService service = service();
        Order order = orderWithManager(10L, "Компания", 123L);
        order.getCompany().setUrlChat("https://t.me/shared_owner");
        order.getCompany().setTelegramGroupChatId(-100L);
        OrderStatus success = status("Выставлен счет");

        when(telegramService.sendMessageOnceWithInlineKeyboardMessageId(eq(-100L),eq("Оплата по карте 2202208238396676"),org.mockito.ArgumentMatchers.isNull(),org.mockito.ArgumentMatchers.anyList())).thenReturn(java.util.Optional.of(71));
        when(orderStatusService.getOrderStatusByTitle("Выставлен счет")).thenReturn(success);

        boolean result = service.sendMessageToGroup(
                "Опубликовано", order, "client", "group",
                "Оплата по карте 2202208238396676", "Выставлен счет", "2202208238396676"
        );

        assertTrue(result);
        verify(telegramService).sendMessageOnceWithInlineKeyboardMessageId(eq(-100L),eq("Оплата по карте 2202208238396676"),org.mockito.ArgumentMatchers.isNull(),org.mockito.ArgumentMatchers.argThat(keyboard -> !keyboard.isEmpty()));
        verify(telegramService, never()).sendMessage(-100L, "Оплата по карте 2202208238396676");
        verify(telegramService, never()).sendMessage(-100L, "2202208238396676");
    }

    @Test
    void whatsappPaymentSendsCopyValueAsSeparateMessage() {
        OrderStatusNotificationService service = service();
        Order order = orderWithManager(10L, "Компания", 123L);
        OrderStatus success = status("Выставлен счет");
        String message = "Оплата по карте 2202208238396676";

        when(whatsAppService.sendMessageToGroup(org.mockito.ArgumentMatchers.eq("client"), org.mockito.ArgumentMatchers.eq("group"), org.mockito.ArgumentMatchers.eq(message), org.mockito.ArgumentMatchers.anyString())).thenAnswer(call -> "{\"status\":\"ok\",\"state\":\"SUCCEEDED\",\"operationId\":\""+call.getArgument(3)+"\",\"messageId\":\"fixture-receipt\"}");
        when(whatsAppService.sendMessageToGroup(org.mockito.ArgumentMatchers.eq("client"), org.mockito.ArgumentMatchers.eq("group"), org.mockito.ArgumentMatchers.eq("2202208238396676"), org.mockito.ArgumentMatchers.anyString())).thenAnswer(call -> "{\"status\":\"ok\",\"state\":\"SUCCEEDED\",\"operationId\":\""+call.getArgument(3)+"\",\"messageId\":\"fixture-receipt\"}");
        when(orderStatusService.getOrderStatusByTitle("Выставлен счет")).thenReturn(success);

        boolean result = service.sendMessageToGroup(
                "Опубликовано", order, "client", "group",
                message, "Выставлен счет", "2202208238396676"
        );

        assertTrue(result);
        InOrder delivery = inOrder(whatsAppService);
        delivery.verify(whatsAppService).sendMessageToGroup(org.mockito.ArgumentMatchers.eq("client"), org.mockito.ArgumentMatchers.eq("group"), org.mockito.ArgumentMatchers.eq(message), org.mockito.ArgumentMatchers.anyString());
        delivery.verify(whatsAppService).sendMessageToGroup(org.mockito.ArgumentMatchers.eq("client"), org.mockito.ArgumentMatchers.eq("group"), org.mockito.ArgumentMatchers.eq("2202208238396676"), org.mockito.ArgumentMatchers.anyString());
        verifyNoInteractions(telegramService);
        verifyNoInteractions(maxBotClient);
    }

    @Test
    void sendMessageToGroupUsesMaxWhenChatLinkPointsToMax() {
        OrderStatusNotificationService service = service();
        Order order = orderWithManager(10L, "Компания", 123L);
        order.getCompany().setUrlChat("max.ru/join/SharedToken123");
        order.getCompany().setTelegramGroupChatId(-100L);
        order.getCompany().setMaxGroupChatId(-200L);
        OrderStatus success = status("Выставлен счет");

        when(maxBotClient.sendMessageToChatOnce(-200L, "message")).thenReturn(new MaxBotClient.SendOnceResult("max-fixture",null));
        when(orderStatusService.getOrderStatusByTitle("Выставлен счет")).thenReturn(success);

        boolean result = service.sendMessageToGroup(
                "Опубликовано",
                order,
                "client",
                "group",
                "message",
                "Выставлен счет"
        );

        assertTrue(result);
        assertSame(success, order.getStatus());
        verify(maxBotClient).sendMessageToChatOnce(-200L, "message");
        verify(orderRepository).save(order);
        verifyNoInteractions(whatsAppService);
        verifyNoInteractions(telegramService);
    }

    @Test
    void maxPaymentSendsCopyValueAsSeparateMessage() {
        OrderStatusNotificationService service = service();
        Order order = orderWithManager(10L, "Компания", 123L);
        order.getCompany().setUrlChat("max.ru/join/SharedToken123");
        order.getCompany().setMaxGroupChatId(-200L);
        OrderStatus success = status("Выставлен счет");
        String message = "Оплата по телефону 89149528806";

        when(maxBotClient.sendMessageToChatOnce(-200L, message)).thenReturn(new MaxBotClient.SendOnceResult("max-fixture",null));
        when(maxBotClient.sendMessageToChatOnce(-200L, "89149528806")).thenReturn(new MaxBotClient.SendOnceResult("max-fixture",null));
        when(orderStatusService.getOrderStatusByTitle("Выставлен счет")).thenReturn(success);

        boolean result = service.sendMessageToGroup(
                "Опубликовано", order, "client", "group",
                message, "Выставлен счет", "89149528806"
        );

        assertTrue(result);
        InOrder delivery = inOrder(maxBotClient);
        delivery.verify(maxBotClient).sendMessageToChatOnce(-200L, message);
        delivery.verify(maxBotClient).sendMessageToChatOnce(-200L, "89149528806");
        verifyNoInteractions(whatsAppService);
        verifyNoInteractions(telegramService);
    }

    @Test
    void copyOnlyFailureKeepsSuccessfulPaymentStatus() {
        OrderStatusNotificationService service = service();
        Order order = orderWithManager(10L, "Компания", 123L);
        OrderStatus success = status("Выставлен счет");

        when(whatsAppService.sendMessageToGroup(org.mockito.ArgumentMatchers.eq("client"), org.mockito.ArgumentMatchers.eq("group"), org.mockito.ArgumentMatchers.eq("Оплата по карте 2202208238396676"), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(call -> "{\"status\":\"ok\",\"state\":\"SUCCEEDED\",\"operationId\":\""+call.getArgument(3)+"\",\"messageId\":\"fixture-receipt\"}");
        when(whatsAppService.sendMessageToGroup(org.mockito.ArgumentMatchers.eq("client"), org.mockito.ArgumentMatchers.eq("group"), org.mockito.ArgumentMatchers.eq("2202208238396676"), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("error");
        when(orderStatusService.getOrderStatusByTitle("Выставлен счет")).thenReturn(success);

        boolean result = service.sendMessageToGroup(
                "Опубликовано",
                order,
                "client",
                "group",
                "Оплата по карте 2202208238396676",
                "Выставлен счет",
                "2202208238396676"
        );

        assertTrue(result);
        assertSame(success, order.getStatus());
        verify(orderRepository).save(order);
    }

    @Test
    void sendMessageToGroupReturnsFalseAndKeepsFallbackStatusWhenActiveWhatsAppFails() {
        OrderStatusNotificationService service = service();
        Order order = orderWithManager(10L, "Компания", 123L);
        order.getCompany().setTelegramGroupChatId(-100L);
        order.getCompany().setMaxGroupChatId(-200L);
        OrderStatus fallback = status("Опубликовано");

        when(whatsAppService.sendMessageToGroup(org.mockito.ArgumentMatchers.eq("client"), org.mockito.ArgumentMatchers.eq("group"), org.mockito.ArgumentMatchers.eq("message"), org.mockito.ArgumentMatchers.anyString())).thenReturn("error");
        when(orderStatusService.getOrderStatusByTitle("Опубликовано")).thenReturn(fallback);

        boolean result = service.sendMessageToGroup(
                "Опубликовано",
                order,
                "client",
                "group",
                "message",
                "Выставлен счет"
        );

        assertFalse(result);
        assertSame(fallback, order.getStatus());
        verify(telegramService, never()).sendMessage(-100L, "message");
        verify(telegramService).sendMessage(
                123L,
                "Компания Опубликован\nhttps://o-ogo.ru/orders/all_orders?status=Опубликовано"
        );
        verifyNoInteractions(maxBotClient);
        verify(orderRepository).save(order);
    }

    @Test
    void sendMessageToGroupKeepsFallbackStatusWhenManagerTelegramUserIsLazy() {
        OrderStatusNotificationService service = service();
        Order order = orderWithLazyManager(10L, "Компания");
        OrderStatus fallback = status("Опубликовано");

        when(orderStatusService.getOrderStatusByTitle("Опубликовано")).thenReturn(fallback);

        boolean result = service.sendMessageToGroup(
                "Опубликовано",
                order,
                "client",
                null,
                "message",
                "Выставлен счет"
        );

        assertFalse(result);
        assertSame(fallback, order.getStatus());
        verifyNoInteractions(telegramService);
        verifyNoInteractions(whatsAppService);
        verifyNoInteractions(maxBotClient);
        verify(orderRepository).save(order);
    }

    @Test
    void sendMessageToGroupAlertsManagerWhenWhatsAppWaitsForQr() {
        OrderStatusNotificationService service = service();
        Order order = orderWithManager(10L, "Компания", 123L);
        OrderStatus fallback = status("Опубликовано");

        when(whatsAppService.sendMessageToGroup(org.mockito.ArgumentMatchers.eq("client"), org.mockito.ArgumentMatchers.eq("group"), org.mockito.ArgumentMatchers.eq("message"), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("{\"status\":\"error\",\"code\":\"whatsapp_not_ready\",\"error\":\"authenticated=false state=qr\"}");
        when(orderStatusService.getOrderStatusByTitle("Опубликовано")).thenReturn(fallback);
        boolean result = service.sendMessageToGroup(
                "Опубликовано",
                order,
                "client",
                "group",
                "message",
                "Выставлен счет"
        );

        assertFalse(result);
        assertSame(fallback, order.getStatus());
        verify(whatsAppAuthAlertService).notifyAuthIssue(
                eq("client"),
                eq("Компания"),
                eq("моментальная отправка клиенту"),
                eq("whatsapp_not_ready"),
                contains("authenticated=false"),
                any(),
                eq(null),
                any()
        );
        verify(telegramService).sendMessage(
                123L,
                "Компания Опубликован\nhttps://o-ogo.ru/orders/all_orders?status=Опубликовано"
        );
        verify(orderRepository).save(order);
    }

    @Test
    void sendMessageToGroupDoesNotAlertManagerDuringWhatsAppWarmup() {
        OrderStatusNotificationService service = service();
        Order order = orderWithManager(10L, "Компания", 123L);
        OrderStatus fallback = status("Опубликовано");

        when(whatsAppService.sendMessageToGroup(org.mockito.ArgumentMatchers.eq("client"), org.mockito.ArgumentMatchers.eq("group"), org.mockito.ArgumentMatchers.eq("message"), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("{\"status\":\"error\",\"code\":\"not_ready\",\"error\":\"authenticated=true state=authenticated hasQr=false client is not ready\"}");
        when(orderStatusService.getOrderStatusByTitle("Опубликовано")).thenReturn(fallback);
        boolean result = service.sendMessageToGroup(
                "Опубликовано",
                order,
                "client",
                "group",
                "message",
                "Выставлен счет"
        );

        assertFalse(result);
        assertSame(fallback, order.getStatus());
        verify(whatsAppAuthAlertService, never()).notifyAuthIssue(any(), any(), any(), any(), any(), any(), any(), any());
        verify(telegramService).sendMessage(
                123L,
                "Компания Опубликован\nhttps://o-ogo.ru/orders/all_orders?status=Опубликовано"
        );
        verify(orderRepository).save(order);
    }

    @Test
    void sendMessageToGroupReturnsFalseAndKeepsFallbackStatusWhenActiveTelegramThrows() {
        OrderStatusNotificationService service = service();
        Order order = orderWithManager(10L, "Компания", 123L);
        order.getCompany().setUrlChat("https://t.me/shared_owner");
        order.getCompany().setTelegramGroupChatId(-100L);
        OrderStatus fallback = status("В проверку");

        when(telegramService.sendMessage(-100L, "message")).thenThrow(new RuntimeException("telegram"));
        when(orderStatusService.getOrderStatusByTitle("В проверку")).thenReturn(fallback);

        boolean result = service.sendMessageToGroup(
                "В проверку",
                order,
                "client",
                "group",
                "message",
                "На проверке"
        );

        assertFalse(result);
        assertSame(fallback, order.getStatus());
        verify(telegramService).sendMessageOnceWithInlineKeyboardMessageId(eq(-100L),eq("message"),org.mockito.ArgumentMatchers.isNull(),org.mockito.ArgumentMatchers.anyList());
        verify(telegramService).sendMessage(
                123L,
                "Компания готов - На проверку\nhttps://o-ogo.ru/orders/all_orders?status=В%20проверку"
        );
        verifyNoInteractions(whatsAppService);
        verifyNoInteractions(maxBotClient);
        verify(orderRepository).save(order);
    }

    @Test
    void sendProgressMessageToClientChatUsesActiveTelegramWithoutChangingOrderStatus() {
        OrderStatusNotificationService service = service();
        Order order = orderWithManager(10L, "Компания", 123L);
        order.getCompany().setUrlChat("https://t.me/shared_owner");
        order.getCompany().setTelegramGroupChatId(-100L);
        OrderStatus currentStatus = status("Публикация");
        order.setStatus(currentStatus);

        when(telegramService.sendMessageOnceWithInlineKeyboardMessageId(eq(-100L),eq("Компания. Опубликован новый отзыв 3 / 10."),org.mockito.ArgumentMatchers.isNull(),org.mockito.ArgumentMatchers.anyList())).thenReturn(java.util.Optional.of(71));

        boolean result = service.sendProgressMessageToClientChat(
                order,
                "client",
                "group",
                "Компания. Опубликован новый отзыв 3 / 10."
        );

        assertTrue(result);
        assertSame(currentStatus, order.getStatus());
        verify(telegramService).sendMessageOnceWithInlineKeyboardMessageId(eq(-100L),eq("Компания. Опубликован новый отзыв 3 / 10."),org.mockito.ArgumentMatchers.isNull(),org.mockito.ArgumentMatchers.anyList());
        verifyNoInteractions(whatsAppService);
        verifyNoInteractions(maxBotClient);
        verifyNoInteractions(orderRepository);
    }

    @Test
    void sendProgressMessageToClientChatAddsOptOutHintForWhatsApp() {
        OrderStatusNotificationService service = service();
        Order order = orderWithManager(10L, "Компания", 123L);
        String message = "Компания. Опубликован новый отзыв 3 / 10.";
        String messageWithHint = message + "\n\nНе хотите получать сообщение о каждом опубликованном отзыве?\nОтправьте команду: отключить уведомления";

        when(publicationProgressPreferenceService.appendPlainOptOutHint(message)).thenReturn(messageWithHint);
        when(whatsAppService.sendMessageToGroup(org.mockito.ArgumentMatchers.eq("client"), org.mockito.ArgumentMatchers.eq("group"), org.mockito.ArgumentMatchers.eq(messageWithHint), org.mockito.ArgumentMatchers.anyString())).thenAnswer(call -> "{\"status\":\"ok\",\"state\":\"SUCCEEDED\",\"operationId\":\""+call.getArgument(3)+"\",\"messageId\":\"fixture-receipt\"}");

        boolean result = service.sendProgressMessageToClientChat(order, "client", "group", message);

        assertTrue(result);
        verify(whatsAppService).sendMessageToGroup(org.mockito.ArgumentMatchers.eq("client"), org.mockito.ArgumentMatchers.eq("group"), org.mockito.ArgumentMatchers.eq(messageWithHint), org.mockito.ArgumentMatchers.anyString());
        verifyNoInteractions(telegramService);
        verifyNoInteractions(maxBotClient);
        verifyNoInteractions(orderRepository);
    }

    @Test
    void sendProgressMessageToClientChatKeepsWhatsAppPlainAfterFirstReview() {
        OrderStatusNotificationService service = service();
        Order order = orderWithManager(10L, "Компания", 123L);
        String message = "Компания. Опубликован новый отзыв 3 / 10.";

        when(whatsAppService.sendMessageToGroup(org.mockito.ArgumentMatchers.eq("client"), org.mockito.ArgumentMatchers.eq("group"), org.mockito.ArgumentMatchers.eq(message), org.mockito.ArgumentMatchers.anyString())).thenAnswer(call -> "{\"status\":\"ok\",\"state\":\"SUCCEEDED\",\"operationId\":\""+call.getArgument(3)+"\",\"messageId\":\"fixture-receipt\"}");

        boolean result = service.sendProgressMessageToClientChat(order, "client", "group", message, false);

        assertTrue(result);
        verify(whatsAppService).sendMessageToGroup(org.mockito.ArgumentMatchers.eq("client"), org.mockito.ArgumentMatchers.eq("group"), org.mockito.ArgumentMatchers.eq(message), org.mockito.ArgumentMatchers.anyString());
        verify(publicationProgressPreferenceService, never()).appendPlainOptOutHint(message);
        verifyNoInteractions(telegramService);
        verifyNoInteractions(maxBotClient);
        verifyNoInteractions(orderRepository);
    }

    @Test
    void sendProgressMessageToClientChatAddsOptOutHintForMax() {
        OrderStatusNotificationService service = service();
        Order order = orderWithManager(10L, "Компания", 123L);
        order.getCompany().setUrlChat("https://max.ru/join/SharedToken123");
        order.getCompany().setMaxGroupChatId(-200L);
        String message = "Компания. Опубликован новый отзыв 3 / 10.";
        String messageWithHint = message + "\n\nНе хотите получать сообщение о каждом опубликованном отзыве?\nОтправьте команду: отключить уведомления";

        when(publicationProgressPreferenceService.appendPlainOptOutHint(message)).thenReturn(messageWithHint);
        when(maxBotClient.sendMessageToChatOnce(-200L, messageWithHint)).thenReturn(new MaxBotClient.SendOnceResult("max-fixture",null));

        boolean result = service.sendProgressMessageToClientChat(order, "client", "group", message);

        assertTrue(result);
        verify(maxBotClient).sendMessageToChatOnce(-200L, messageWithHint);
        verifyNoInteractions(whatsAppService);
        verifyNoInteractions(telegramService);
        verifyNoInteractions(orderRepository);
    }

    @Test
    void sendProgressMessageToClientChatKeepsTelegramPlainAfterFirstReview() {
        OrderStatusNotificationService service = service();
        Order order = orderWithManager(10L, "Компания", 123L);
        order.getCompany().setUrlChat("https://t.me/shared_owner");
        order.getCompany().setTelegramGroupChatId(-100L);
        String message = "Компания. Опубликован новый отзыв 3 / 10.";

        when(telegramService.sendMessageOnceWithInlineKeyboardMessageId(eq(-100L),eq(message),org.mockito.ArgumentMatchers.isNull(),org.mockito.ArgumentMatchers.anyList())).thenReturn(java.util.Optional.of(71));

        boolean result = service.sendProgressMessageToClientChat(order, "client", "group", message, false);

        assertTrue(result);
        verify(telegramService).sendMessageOnceWithInlineKeyboardMessageId(eq(-100L),eq(message),org.mockito.ArgumentMatchers.isNull(),org.mockito.ArgumentMatchers.anyList());
        verify(telegramService, never()).sendPublicationProgressMessage(-100L, message, order.getCompany().getId());
        verifyNoInteractions(whatsAppService);
        verifyNoInteractions(maxBotClient);
        verifyNoInteractions(orderRepository);
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
        var occurrences=org.mockito.Mockito.mock(OrderNotificationOccurrences.class);
        org.mockito.Mockito.lenient().when(occurrences.reserve(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyLong())).thenReturn("fixture-operation");
        var fence=org.mockito.Mockito.mock(com.hunt.otziv.client_messages.service.ClientMessageOperationFence.class);
        org.mockito.Mockito.lenient().when(fence.execute(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.nullable(String.class),org.mockito.ArgumentMatchers.anyString(),any()))
                .thenAnswer(call -> call.<java.util.function.Supplier<com.hunt.otziv.client_messages.dto.ClientMessageSendResult>>getArgument(4).get());
        var delivery=new com.hunt.otziv.client_messages.service.ClientChatMessageSender(whatsAppService,telegramService,maxBotClient,
                com.hunt.otziv.whatsapp.support.WhatsAppOperationsFixture.echo(),fence,publicationProgressPreferenceService);
        return new OrderStatusNotificationService(orderRepository,orderStatusService,telegramService,whatsAppAuthAlertService,occurrences,delivery);
    }

    private Order orderWithManager(Long id, String companyTitle, Long telegramChatId) {
        Order order = new Order();
        order.setId(id);
        order.setClientMessageGeneration(1);
        order.setCompany(company(companyTitle));

        Manager manager = new Manager();
        manager.setUser(user(telegramChatId));
        order.setManager(manager);

        OrderDetails detail = new OrderDetails();
        detail.setOrder(order);
        order.setDetails(List.of(detail));
        return order;
    }

    private Order orderWithLazyManager(Long id, String companyTitle) {
        Order order = new Order();
        order.setId(id);
        order.setClientMessageGeneration(1);
        order.setCompany(company(companyTitle));

        Manager manager = new Manager() {
            @Override
            public User getUser() {
                throw new RuntimeException("Could not initialize proxy - no session");
            }
        };
        order.setManager(manager);

        OrderDetails detail = new OrderDetails();
        detail.setOrder(order);
        order.setDetails(List.of(detail));
        return order;
    }

    private Company company(String title) {
        Company company = new Company();
        company.setTitle(title);
        company.setUrlChat("https://chat.whatsapp.com/AbCdEfGhIjKlMnOpQrStUv");
        return company;
    }

    private User user(Long telegramChatId) {
        User user = new User();
        user.setTelegramChatId(telegramChatId);
        user.setWorkerTelegramGroupChatId(-telegramChatId);
        return user;
    }

    private OrderStatus status(String title) {
        OrderStatus status = new OrderStatus();
        status.setTitle(title);
        return status;
    }
}
