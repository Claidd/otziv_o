package com.hunt.otziv.p_products.status.service;

import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.config.settings.api.PublicationProgressSettings;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.review.service.OrderAggregateMutationLockService;
import com.hunt.otziv.p_products.status.event.OrderStatusChangedEvent;
import com.hunt.otziv.p_products.status.service.OrderStatusNotificationService.PreparedAction;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends the review-check link once the manager's transition has committed, so the request
 * does not wait for WhatsApp (a new link takes 6-8 s to be accepted). The order is already
 * "В проверку" with its durable review-check retry; a confirmed delivery moves it to
 * "На проверке" exactly like the former synchronous send. A failed, unconfirmed or lost
 * send stays with that retry, which reuses this operation id, so the client gets one message.
 */
@Service
@Slf4j
public class OrderReviewCheckDeliveryWorker {
    static final String DELIVERED_REASON = "REVIEW_CHECK_DELIVERED";

    /** Published inside the transition; handled only after its commit. */
    public record Requested(PreparedAction prepared) { }

    private final OrderRepository orders;
    private final OrderAggregateMutationLockService locks;
    private final OrderStatusNotificationService notifications;
    private final PublicationProgressSettings settings;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transaction;
    private final TransactionTemplate readOnly;

    public OrderReviewCheckDeliveryWorker(OrderRepository orders, OrderAggregateMutationLockService locks,
            OrderStatusNotificationService notifications, PublicationProgressSettings settings,
            ApplicationEventPublisher events, PlatformTransactionManager manager) {
        this.orders = orders; this.locks = locks; this.notifications = notifications;
        this.settings = settings; this.events = events;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        readOnly = new TransactionTemplate(manager);
        readOnly.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        readOnly.setReadOnly(true);
    }

    @Async("clientNotificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRequested(Requested requested) {
        deliver(requested == null ? null : requested.prepared());
    }

    void deliver(PreparedAction prepared) {
        if (prepared == null || prepared.orderId() == null) return;
        Long orderId = prepared.orderId();
        // Re-check permission right before the external send; the retry keeps the order otherwise.
        if (!immediateMessagesEnabled()) {
            log.info("Фоновая отправка проверки клиенту пропущена: моментальные сообщения выключены, orderId={}", orderId);
            return;
        }
        if (!stillAwaitingThisDelivery(prepared)) {
            log.info("Фоновая отправка проверки клиенту пропущена: заказ уже сменил статус или цикл, orderId={}", orderId);
            return;
        }
        long startedAt = System.currentTimeMillis();
        ClientMessageSendResult result;
        try {
            result = notifications.dispatchPreparedAction(prepared);
        } catch (RuntimeException unconfirmed) {
            log.warn("Review check delivery unconfirmed: orderId={} error={}", orderId, unconfirmed.getClass().getSimpleName());
            result = null;
        }
        if (result == null) {
            result = ClientMessageSendResult.failed("operation_unknown",
                    "Отправка не подтверждена; сохранённая операция удерживается до сверки");
        }
        boolean moved = false;
        if (result.sent()) {
            try {
                moved = Boolean.TRUE.equals(transaction.execute(status -> moveToInCheck(prepared)));
            } catch (RuntimeException failure) {
                // The receipt is recorded by the provider; the retry recognises this operation id.
                log.error("Review check delivered but the order status was not updated: orderId={}", orderId, failure);
            }
        }
        try {
            notifications.notifyPreparedActionOutcome(prepared, result);
        } catch (RuntimeException alertFailure) {
            log.warn("Review check outcome notification failed: orderId={}", orderId, alertFailure);
        }
        if (moved) {
            log.info("✅ Заказ ID {} переведен в статус 'На проверке' (сообщение отправлено в фоне за {} мс)",
                    orderId, System.currentTimeMillis() - startedAt);
        } else if (!result.sent()) {
            log.warn("⚠️ Заказ ID {} оставлен в статусе 'В проверку': клиентское сообщение не отправлено ({}); сработает повтор",
                    orderId, result.errorCode());
        }
    }

    private boolean immediateMessagesEnabled() {
        try {
            // The "моментальные клиентские сообщения" switch, read fresh and fail-closed.
            return settings.immediatePublicationMessagesEnabled();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private boolean stillAwaitingThisDelivery(PreparedAction prepared) {
        try {
            return Boolean.TRUE.equals(readOnly.execute(status -> orders.findById(prepared.orderId())
                    .map(order -> isAwaiting(order, prepared))
                    .orElse(false)));
        } catch (RuntimeException unavailable) {
            log.warn("Review check delivery postponed to the retry: order unavailable, orderId={}", prepared.orderId(), unavailable);
            return false;
        }
    }

    private static boolean isAwaiting(Order order, PreparedAction prepared) {
        return order.getClientMessageGeneration() == prepared.generation()
                && order.getStatus() != null
                && Objects.equals(order.getStatus().getTitle(), prepared.title());
    }

    /** Same lock order as the manager's transition: aggregate lock, then the order row. */
    private boolean moveToInCheck(PreparedAction prepared) {
        Long orderId = prepared.orderId();
        if (locks.lockIfLive(orderId).isEmpty()) return false;
        Order order = orders.findByIdForMutation(orderId).orElse(null);
        if (!notifications.applyPreparedAction(order, prepared)) return false;
        // Listeners align the client-message queue (close the delivery retry, plan review reminders).
        events.publishEvent(new OrderStatusChangedEvent(orderId, prepared.title(), prepared.successStatus(), DELIVERED_REASON));
        return true;
    }
}
