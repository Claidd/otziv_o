package com.hunt.otziv.p_products.next_order.service;

import com.hunt.otziv.common_billing.service.CommonBillingNextOrderFailureMarker;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.next_order.dto.NextOrderRequestFailedEvent;
import com.hunt.otziv.p_products.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@Slf4j
@RequiredArgsConstructor
public class NextOrderRequestFailureListener {

    private final OrderRepository orderRepository;
    private final CommonBillingNextOrderFailureMarker commonBillingNextOrderFailureMarker;
    private final NextOrderFailureNotifier nextOrderFailureNotifier;

    @Async("nextOrderAutomationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(NextOrderRequestFailedEvent event) {
        if (event == null || event.sourceOrderId() == null) {
            return;
        }

        Order sourceOrder = orderRepository.findByIdForMutation(event.sourceOrderId()).orElse(null);
        if (sourceOrder == null) {
            log.warn("Не удалось уведомить о сбое заявки {}: исходный заказ {} не найден",
                    event.requestId(), event.sourceOrderId());
            return;
        }

        try {
            commonBillingNextOrderFailureMarker.markAttentionForSourceOrder(
                    sourceOrder,
                    event.requestId(),
                    event.cause()
            );
        } catch (RuntimeException exception) {
            log.warn("Общий счет не помечен после сбоя заявки {}", event.requestId(), exception);
        }

        try {
            nextOrderFailureNotifier.notifyManager(
                    sourceOrder,
                    null,
                    "автосоздание следующего заказа по заявке #" + event.requestId(),
                    event.cause()
            );
        } catch (RuntimeException exception) {
            log.warn("Менеджер не уведомлен о сбое заявки {}", event.requestId(), exception);
        }
    }
}
