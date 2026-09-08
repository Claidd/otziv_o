package com.hunt.otziv.p_products.status.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.client_messages.api.ClientMessageDelivery;
import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.service.OrderStatusService;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.whatsapp.service.WhatsAppAuthAlertService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OrderStatusPreparedActionTest {
    @ParameterizedTest @CsvSource({"В проверку, На проверке", "Опубликовано, Выставлен счет"})
    void preparationDoesNotSendAndDispatchDoesNotReadOrMutateEntities(String before, String after) {
        var orders = mock(OrderRepository.class);
        var statuses = mock(OrderStatusService.class);
        var telegram = mock(TelegramService.class);
        var alerts = mock(WhatsAppAuthAlertService.class);
        var occurrences = mock(OrderNotificationOccurrences.class);
        var sender = mock(ClientMessageDelivery.class);
        var service = new OrderStatusNotificationService(orders, statuses, telegram, alerts, occurrences, sender);
        String kind = "action:" + before + ":" + after;
        when(occurrences.reserve(5L, kind, 4)).thenReturn("previous-order-operation");
        var order = Order.builder().id(5L).clientMessageGeneration(4).status(OrderStatus.builder().title(before).build())
                .company(Company.builder().id(7L).title("Company").telegramGroupChatId(-1007L).urlChat("https://t.me/company").build()).build();
        var action = service.prepareAction(before, order, "client", "group", "К оплате: 1200 руб.", after, "+79991112233");
        verifyNoInteractions(sender, orders, statuses, telegram, alerts);
        order.getCompany().setTelegramGroupChatId(-1009L);
        order.getCompany().setTitle("Changed");
        when(sender.deliverWithOperationId(any(), any(), any(), any(), any(), any())).thenReturn(ClientMessageSendResult.sent("Telegram", "42"));
        assertThat(service.dispatchPreparedAction(action).sent()).isTrue();
        verify(sender).deliverWithOperationId(eq(action.target()), eq("client"), eq("group"), eq("К оплате: 1200 руб."),
                argThat(copy -> copy.copyText().equals("+79991112233")), eq("previous-order-operation"));
        assertThat(action.target().telegramChatId()).isEqualTo(-1007L);
        assertThat(action.target().title()).isEqualTo("Company");
        verifyNoInteractions(orders, statuses, telegram, alerts);
        var next = OrderStatus.builder().title(after).build();
        when(statuses.getOrderStatusByTitle(after)).thenReturn(next);
        assertThat(service.applyPreparedAction(order, action)).isTrue();
        assertThat(order.getStatus()).isSameAs(next);
        verify(occurrences).confirm(5L, kind, "previous-order-operation");
        verify(orders).save(order);
    }

    @ParameterizedTest @CsvSource({"5, Опубликовано", "4, Оплачено"})
    void lateReceiptCannotOverwriteNewGenerationOrStatus(long generation, String currentStatus) {
        var orders = mock(OrderRepository.class);
        var statuses = mock(OrderStatusService.class);
        var occurrences = mock(OrderNotificationOccurrences.class);
        var service = new OrderStatusNotificationService(orders, statuses, mock(TelegramService.class),
                mock(WhatsAppAuthAlertService.class), occurrences, mock(ClientMessageDelivery.class));
        var action = new OrderStatusNotificationService.PreparedAction(5L, "Опубликовано", "Выставлен счет", 4,
                "action", "original-operation", null, null, null, "1200 руб.", null, null, null, java.util.List.of());
        var order = Order.builder().id(5L).clientMessageGeneration(generation).status(OrderStatus.builder().title(currentStatus).build()).build();
        assertThat(service.applyPreparedAction(order, action)).isFalse();
        verify(occurrences).confirm(5L, "action", "original-operation");
        verifyNoInteractions(orders, statuses);
    }
}
