package com.hunt.otziv.payments.service;

import com.hunt.otziv.p_products.review.event.OrderPayableChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentLinkOrderPayableChangedListener {

    private final PaymentLinkService paymentLinkService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onOrderPayableChanged(OrderPayableChangedEvent event) {
        if (event == null || event.orderId() == null || event.orderId() <= 0) {
            return;
        }
        try {
            paymentLinkService.refreshLinkedOrderAmount(event.orderId());
        } catch (RuntimeException e) {
            log.warn("Не удалось обновить обычный счёт после изменения суммы заказа {}", event.orderId(), e);
        }
    }
}
