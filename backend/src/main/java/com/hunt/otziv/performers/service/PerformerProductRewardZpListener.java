package com.hunt.otziv.performers.service;

import com.hunt.otziv.contractor_payments.service.ContractorCompletionRewardService;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentRuntimeSwitch;
import com.hunt.otziv.p_products.status.event.OrderStatusChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
@Slf4j
public class PerformerProductRewardZpListener {

    private static final String STATUS_PAID = "Оплачено";
    private static final String STATUS_PUBLIC = "Опубликовано";

    private final PerformerProductRewardZpService rewardZpService;
    private final ContractorPaymentRuntimeSwitch runtimeSwitch;
    private final ContractorCompletionRewardService completionRewardService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderPaid(OrderStatusChangedEvent event) {
        if (event == null || event.orderId() == null) {
            return;
        }
        if (runtimeSwitch.rewardAttributionLiveEnabled()) {
            if (STATUS_PUBLIC.equals(event.requestedStatus())
                    || STATUS_PUBLIC.equals(event.newStatus())
                    || STATUS_PAID.equals(event.newStatus())) {
                try {
                    completionRewardService.ensureOrderCompletionAccrual(event.orderId());
                } catch (RuntimeException exception) {
                    log.error(
                            "Не удалось восстановить начисления завершенного заказа: orderId={}, code={}",
                            event.orderId(),
                            exception.getClass().getSimpleName()
                    );
                }
            }
            return;
        }
        if (STATUS_PAID.equals(event.newStatus())) {
            rewardZpService.accrueForPaidOrder(event.orderId());
        }
    }
}
