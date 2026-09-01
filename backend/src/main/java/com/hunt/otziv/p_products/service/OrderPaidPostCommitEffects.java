package com.hunt.otziv.p_products.service;

import com.hunt.otziv.gamification.service.GamificationEventService;
import com.hunt.otziv.mobile_push.service.MobilePushBusinessNotificationService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrderPaidPostCommitEffects {

    private final OrderRepository orderRepository;
    private final GamificationEventService gamificationEventService;
    private final MobilePushBusinessNotificationService mobilePushBusinessNotificationService;

    public void apply(Long orderId) {
        if (orderId == null) {
            return;
        }

        Order order = orderRepository.findByIdForMutation(orderId).orElse(null);
        if (order == null) {
            log.warn("Post-commit действия оплаты пропущены: заказ {} не найден", orderId);
            return;
        }

        try {
            gamificationEventService.recordOrderPaid(order);
        } catch (RuntimeException exception) {
            // There is no financial transaction on this async thread. The
            // unique ORDER_PAID key plus the gamification backfill/rebuild make
            // this side effect safely recoverable without rolling payment back.
            log.warn("Gamification после оплаты заказа {} не записана", orderId, exception);
        }

        try {
            mobilePushBusinessNotificationService.notifyOwnersOrderPaid(order);
        } catch (RuntimeException exception) {
            // Push is deliberately best-effort and must never affect payment.
            log.warn("Push после оплаты заказа {} не отправлен", orderId, exception);
        }
    }
}
