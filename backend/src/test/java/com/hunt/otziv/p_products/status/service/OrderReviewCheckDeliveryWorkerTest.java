package com.hunt.otziv.p_products.status.service;

import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.config.settings.api.PublicationProgressSettings;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.review.service.OrderAggregateMutationLockService;
import com.hunt.otziv.p_products.status.event.OrderStatusChangedEvent;
import com.hunt.otziv.p_products.status.service.OrderStatusNotificationService.PreparedAction;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The review-check link is sent after commit; only a confirmed delivery moves the order to "На проверке". */
@ExtendWith(MockitoExtension.class)
class OrderReviewCheckDeliveryWorkerTest {
    @Mock private OrderRepository orders;
    @Mock private OrderAggregateMutationLockService locks;
    @Mock private OrderStatusNotificationService notifications;
    @Mock private PublicationProgressSettings settings;
    @Mock private ApplicationEventPublisher events;
    @Mock private PlatformTransactionManager transactions;

    private final PreparedAction prepared = new PreparedAction(5L, "В проверку", "На проверке", 3,
            "action:В проверку:На проверке", "operation-5", null, "client", "group", "Проверьте отзывы",
            null, 77L, "Компания готов - На проверку", List.of());

    @Test
    void aConfirmedDeliveryMovesTheOrderToInCheckAndAnnouncesTheChange() {
        Order order = order("В проверку", 3);
        immediateMessages(true);
        when(orders.findById(5L)).thenReturn(Optional.of(order));
        var delivered = ClientMessageSendResult.sent("WhatsApp", "wamid-1");
        when(notifications.dispatchPreparedAction(prepared)).thenReturn(delivered);
        when(locks.lockIfLive(5L)).thenReturn(Optional.of(order));
        when(orders.findByIdForMutation(5L)).thenReturn(Optional.of(order));
        when(notifications.applyPreparedAction(order, prepared)).thenReturn(true);

        worker().onRequested(new OrderReviewCheckDeliveryWorker.Requested(prepared));

        InOrder flow = inOrder(notifications, locks, orders, events);
        flow.verify(notifications).dispatchPreparedAction(prepared);
        flow.verify(locks).lockIfLive(5L);
        flow.verify(orders).findByIdForMutation(5L);
        flow.verify(notifications).applyPreparedAction(order, prepared);
        flow.verify(events).publishEvent(new OrderStatusChangedEvent(5L, "В проверку", "На проверке",
                OrderReviewCheckDeliveryWorker.DELIVERED_REASON));
        flow.verify(notifications).notifyPreparedActionOutcome(prepared, delivered);
    }

    @Test
    void aFailedSendLeavesTheOrderToTheRetryAndAlertsTheManager() {
        immediateMessages(true);
        when(orders.findById(5L)).thenReturn(Optional.of(order("В проверку", 3)));
        var refused = ClientMessageSendResult.failed("whatsapp_unavailable", "WhatsApp недоступен");
        when(notifications.dispatchPreparedAction(prepared)).thenReturn(refused);

        worker().onRequested(new OrderReviewCheckDeliveryWorker.Requested(prepared));

        verify(notifications, never()).applyPreparedAction(any(), any());
        verifyNoInteractions(locks, events);
        // The manager's Telegram fallback and auth alerts come from the shared outcome notification.
        verify(notifications).notifyPreparedActionOutcome(prepared, refused);
    }

    @Test
    void anUnconfirmedSendIsNeverTreatedAsDelivered() {
        immediateMessages(true);
        when(orders.findById(5L)).thenReturn(Optional.of(order("В проверку", 3)));
        when(notifications.dispatchPreparedAction(prepared)).thenThrow(new IllegalStateException("timeout"));

        worker().onRequested(new OrderReviewCheckDeliveryWorker.Requested(prepared));

        verify(notifications, never()).applyPreparedAction(any(), any());
        verifyNoInteractions(locks, events);
        ArgumentCaptor<ClientMessageSendResult> outcome = ArgumentCaptor.forClass(ClientMessageSendResult.class);
        verify(notifications).notifyPreparedActionOutcome(same(prepared), outcome.capture());
        assertThat(outcome.getValue().sent()).isFalse();
        assertThat(outcome.getValue().errorCode()).isEqualTo("operation_unknown");
    }

    @Test
    void nothingIsSentWhenImmediateMessagesAreOffOrTheOrderHasMovedOn() {
        immediateMessages(false);
        worker().onRequested(new OrderReviewCheckDeliveryWorker.Requested(prepared));
        verifyNoInteractions(notifications, orders);

        immediateMessages(true);
        // A newer transition (another cycle) or a different status: the old link is not sent.
        when(orders.findById(5L)).thenReturn(Optional.of(order("В проверку", 4)),
                Optional.of(order("Коррекция", 3)), Optional.empty());
        worker().onRequested(new OrderReviewCheckDeliveryWorker.Requested(prepared));
        worker().onRequested(new OrderReviewCheckDeliveryWorker.Requested(prepared));
        worker().onRequested(new OrderReviewCheckDeliveryWorker.Requested(prepared));
        verifyNoInteractions(notifications, locks, events);
    }

    @Test
    void aStatusChangedDuringTheSendIsNotOverwritten() {
        Order order = order("В проверку", 3);
        immediateMessages(true);
        when(orders.findById(5L)).thenReturn(Optional.of(order));
        var delivered = ClientMessageSendResult.sent("WhatsApp", "wamid-2");
        when(notifications.dispatchPreparedAction(prepared)).thenReturn(delivered);
        when(locks.lockIfLive(5L)).thenReturn(Optional.of(order));
        when(orders.findByIdForMutation(5L)).thenReturn(Optional.of(order));
        // The manager moved the order meanwhile: the guarded apply refuses and nothing is announced.
        when(notifications.applyPreparedAction(order, prepared)).thenReturn(false);

        worker().onRequested(new OrderReviewCheckDeliveryWorker.Requested(prepared));

        verifyNoInteractions(events);
        verify(notifications).notifyPreparedActionOutcome(prepared, delivered);
    }

    private void immediateMessages(boolean enabled) {
        when(settings.immediatePublicationMessagesEnabled()).thenReturn(enabled);
    }

    private OrderReviewCheckDeliveryWorker worker() {
        return new OrderReviewCheckDeliveryWorker(orders, locks, notifications, settings, events, transactions);
    }

    private static Order order(String status, long generation) {
        Order order = new Order();
        order.setId(5L);
        OrderStatus title = new OrderStatus();
        title.setTitle(status);
        order.setStatus(title);
        order.setClientMessageGeneration(generation);
        return order;
    }
}
