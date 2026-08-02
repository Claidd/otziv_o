package com.hunt.otziv.client_messages.service;

import com.hunt.otziv.p_products.status.event.OrderStatusChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@Slf4j
@RequiredArgsConstructor
public class ClientMessageOrderStatusChangedListener {

    private final ScheduledClientMessageService scheduledClientMessageService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void ensureClientMessageQueue(OrderStatusChangedEvent event) {
        if (event == null || event.orderId() == null || event.orderId() <= 0) {
            return;
        }

        try {
            boolean createdOrMatched = scheduledClientMessageService.ensureClientMessageStateAfterOrderStatusChange(event.orderId());
            if (createdOrMatched) {
                log.info(
                        "Очередь автоответчика проверена после смены статуса заказа {}: '{}' -> '{}'",
                        event.orderId(),
                        event.oldStatus(),
                        event.newStatus()
                );
            }
        } catch (RuntimeException e) {
            log.warn(
                    "Не удалось проверить очередь автоответчика после смены статуса заказа {}: '{}' -> '{}'",
                    event.orderId(),
                    event.oldStatus(),
                    event.newStatus(),
                    e
            );
        }
    }
}
