package com.hunt.otziv.performers.service;

import com.hunt.otziv.p_products.status.event.OrderStatusChangedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class PerformerProductRewardZpListener {

    private static final String STATUS_PAID = "Оплачено";

    private final PerformerProductRewardZpService rewardZpService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderPaid(OrderStatusChangedEvent event) {
        if (event == null || event.orderId() == null || !STATUS_PAID.equals(event.newStatus())) {
            return;
        }
        rewardZpService.accrueForPaidOrder(event.orderId());
    }
}
