package com.hunt.otziv.payments.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.status.service.OrderStatusNotificationService;
import com.hunt.otziv.payments.dto.ManagerPaymentLinkResponse;
import com.hunt.otziv.payments.repository.PaymentRouteChangeNotificationOutboxRepository;
import com.hunt.otziv.payments.repository.PaymentRouteChangeNotificationOutboxRepository.Delivery;
import com.hunt.otziv.u_users.model.Manager;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentRouteChangeNotificationWorker {

    private static final String ISSUE_SOURCE = "PAYMENT_ROUTE_CHANGE_DELIVERY";
    private static final Duration DELIVERY_LEASE = Duration.ofMinutes(2);
    private static final Duration MIN_RETRY_DELAY = Duration.ofSeconds(15);
    private static final Duration MAX_RETRY_DELAY = Duration.ofMinutes(15);
    private static final String PROCESSING_OWNER = "payment-route-change:" + UUID.randomUUID();

    private final OrderRepository orderRepository;
    private final OrderStatusNotificationService notificationService;
    private final PaymentIssueReminderService paymentIssueReminderService;
    private final PaymentRouteChangeNotificationOutboxRepository outboxRepository;
    private final PaymentLinkService paymentLinkService;

    /**
     * Persists the notification in the caller's route-change transaction.
     * The unique payment-link key also makes repeated scheduling idempotent.
     */
    public boolean enqueue(Long orderId, Long paymentLinkId, ManagerPaymentLinkResponse payment) {
        if (orderId == null || orderId <= 0
                || paymentLinkId == null || paymentLinkId <= 0
                || payment == null) {
            return false;
        }
        return outboxRepository.enqueue(orderId, paymentLinkId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean send(Long paymentLinkId) {
        if (paymentLinkId == null || paymentLinkId <= 0) {
            return false;
        }
        Optional<Delivery> claimed = outboxRepository.tryAcquire(
                paymentLinkId,
                UUID.randomUUID().toString(),
                PROCESSING_OWNER,
                DELIVERY_LEASE
        );
        if (claimed.isEmpty()) {
            return false;
        }
        Delivery delivery = claimed.get();
        try {
            // This is the same row lock acquired by the route-change command.
            // Holding it through the current-route check and external send
            // prevents a concurrent second replacement from making this
            // message stale between validation and delivery.
            Order order = orderRepository.findByIdForCounterUpdate(delivery.orderId()).orElse(null);
            if (order == null) {
                skipAndNotify(delivery, "Заказ для новых реквизитов не найден");
                return false;
            }
            if (!outboxRepository.isCurrentReplacement(
                    delivery.orderId(),
                    delivery.paymentLinkId()
            )) {
                if (!outboxRepository.markSkipped(delivery, "replacement_payment_link_is_not_current")) {
                    log.warn("Stale route-change notification skip was fenced: paymentLinkId={}",
                            delivery.paymentLinkId());
                }
                return false;
            }
            ManagerPaymentLinkResponse payment =
                    paymentLinkService.paymentRouteChangeNotificationDetails(delivery.paymentLinkId());
            if (payment == null || normalize(payment.copyText()).isBlank()) {
                skipAndNotify(delivery, "Новые реквизиты не сохранены");
                return false;
            }
            Company company = order.getCompany();
            Manager manager = order.getManager() != null
                    ? order.getManager()
                    : company == null ? null : company.getManager();
            String message = "По вашей просьбе способ оплаты изменен. Используйте новые реквизиты.\n\n"
                    + payment.copyText();
            boolean sent = notificationService.sendInformationalMessageToClientChat(
                    order,
                    manager == null ? null : manager.getClientId(),
                    company == null ? null : company.getGroupId(),
                    message,
                    "Новые реквизиты оплаты",
                    payment.telegramCopyTransferNumber()
            );
            if (!sent) {
                failAndNotify(delivery, "Активный клиентский чат недоступен");
                return false;
            }
            if (!outboxRepository.markSent(delivery)) {
                log.warn("Route-change notification success was fenced: paymentLinkId={}",
                        delivery.paymentLinkId());
                return false;
            }
            paymentIssueReminderService.resolveOrderIssue(
                    delivery.orderId(),
                    ISSUE_SOURCE,
                    delivery.paymentLinkId()
            );
            return true;
        } catch (RuntimeException exception) {
            log.error("Не удалось отправить новые реквизиты: orderId={}, paymentLinkId={}",
                    delivery.orderId(), delivery.paymentLinkId(), exception);
            failAndNotify(delivery, exception.getMessage());
            return false;
        }
    }

    private void failAndNotify(Delivery delivery, String reason) {
        String cleanReason = reason(reason);
        if (!outboxRepository.markFailed(
                delivery,
                cleanReason,
                retryDelay(delivery.attemptCount())
        )) {
            log.warn("Route-change notification failure was fenced: paymentLinkId={}",
                    delivery.paymentLinkId());
        }
        notifyFailure(delivery.orderId(), delivery.paymentLinkId(), cleanReason);
    }

    private void skipAndNotify(Delivery delivery, String reason) {
        String cleanReason = reason(reason);
        if (!outboxRepository.markSkipped(delivery, cleanReason)) {
            log.warn("Route-change notification terminal failure was fenced: paymentLinkId={}",
                    delivery.paymentLinkId());
        }
        notifyFailure(delivery.orderId(), delivery.paymentLinkId(), cleanReason);
    }

    private Duration retryDelay(int attemptCount) {
        int exponent = Math.max(0, Math.min(6, attemptCount - 1));
        Duration delay = MIN_RETRY_DELAY.multipliedBy(1L << exponent);
        return delay.compareTo(MAX_RETRY_DELAY) > 0 ? MAX_RETRY_DELAY : delay;
    }

    private void notifyFailure(Long orderId, Long paymentLinkId, String reason) {
        paymentIssueReminderService.notifyOrderIssue(
                orderId,
                ISSUE_SOURCE,
                paymentLinkId,
                "Новые реквизиты не отправлены: заказ №" + orderId,
                "Способ оплаты изменен, но сообщение с новыми реквизитами не доставлено клиенту. "
                        + "Откройте чат и отправьте реквизиты вручную. Причина: "
                        + (reason == null || reason.isBlank() ? "неизвестна" : reason)
        );
    }

    private String reason(String value) {
        String clean = normalize(value);
        if (clean.isBlank()) {
            clean = "notification_delivery_failed";
        }
        return clean.length() <= 512 ? clean : clean.substring(0, 512);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

}
