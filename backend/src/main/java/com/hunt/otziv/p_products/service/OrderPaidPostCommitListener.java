package com.hunt.otziv.p_products.service;

import com.hunt.otziv.p_products.dto.OrderPaidPostCommitEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class OrderPaidPostCommitListener {

    private final OrderPaidPostCommitEffects effects;

    @Async("orderPaymentPostCommitExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(OrderPaidPostCommitEvent event) {
        if (event != null) {
            effects.apply(event.orderId());
        }
    }
}
