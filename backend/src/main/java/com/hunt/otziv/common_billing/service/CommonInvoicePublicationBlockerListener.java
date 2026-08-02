package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.p_products.status.event.OrderStatusChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
@Slf4j
public class CommonInvoicePublicationBlockerListener {

    private final CommonInvoicePublicationBlockerService blockerService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderStatusChanged(OrderStatusChangedEvent event) {
        if (event == null || event.orderId() == null || event.orderId() <= 0) {
            return;
        }
        try {
            blockerService.reconcileOrder(event.orderId());
        } catch (RuntimeException e) {
            log.warn("Не удалось пересчитать блокер общего счета после смены статуса заказа {}", event.orderId(), e);
        }
    }
}
