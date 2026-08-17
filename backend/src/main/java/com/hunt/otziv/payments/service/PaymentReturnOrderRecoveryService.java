package com.hunt.otziv.payments.service;

import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.status.service.OrderStatusTransitionService;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Restores the payment cycle after a provider has durably confirmed a full
 * refund/reversal.  Contractor/task accounting is deliberately performed by
 * the return reconciler first; this service only changes the order cycle and
 * prepares the next payment instruction.  It is safe to call repeatedly:
 * createForOrder reuses an active link and the status transition is a no-op
 * once the order is already unpaid.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentReturnOrderRecoveryService {

    private static final String STATUS_PAID = "Оплачено";
    private static final String STATUS_NOT_PAID = "Не оплачено";
    private static final String STATUS_REMINDER = "Напоминание";
    private static final Set<PaymentLinkStatus> FULL_RETURN_STATUSES = EnumSet.of(
            PaymentLinkStatus.CANCELED,
            PaymentLinkStatus.REVERSED,
            PaymentLinkStatus.REFUNDED
    );

    private final PaymentLinkRepository paymentLinkRepository;
    private final OrderRepository orderRepository;
    private final OrderStatusTransitionService orderStatusTransitionService;
    private final PaymentLinkService paymentLinkService;

    /**
     * Returns the order id when a new payment cycle was opened.  Partial
     * returns, links without settled evidence, missing/archived sources and
     * common-invoice-owned orders are intentionally left for reconciliation.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Long> reopenAfterFullReturn(PaymentLinkReturnOutboxClaim claim) {
        if (claim == null || claim.paymentLinkId() == null
                || !FULL_RETURN_STATUSES.contains(claim.observedStatus())) {
            return Optional.empty();
        }

        PaymentLink link = paymentLinkRepository.findByIdForUpdate(claim.paymentLinkId()).orElse(null);
        if (link == null || !FULL_RETURN_STATUSES.contains(link.getStatus())) {
            return Optional.empty();
        }
        Order order = link.getOrder();
        if (order == null || order.getId() == null) {
            return Optional.empty();
        }

        boolean settledEvidence = STATUS_PAID.equals(statusTitle(order))
                || positive(link.getConfirmedAmountKopecks())
                || link.getPaidAt() != null;
        if (!settledEvidence) {
            // A cancellation before any money was confirmed is not a refund.
            return Optional.empty();
        }

        if (!STATUS_REMINDER.equals(statusTitle(order))) {
            try {
                orderStatusTransitionService.changeStatusForOrder(order.getId(), STATUS_REMINDER);
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Не удалось вернуть заказ " + order.getId() + " в статус \"Напоминание\"",
                        e
                );
            }
        }

        // The old link is terminal. The payment service either reuses an
        // already active replacement or atomically creates a fresh one under
        // the order lock. A safety switch may temporarily disable standalone
        // links, or a common invoice may own this order. The order must still
        // become unpaid in that case; the durable reminder remains ACTIVE and
        // will retry route preparation when it becomes available.
        try {
            paymentLinkService.createForOrder(order.getId());
        } catch (ResponseStatusException e) {
            if (!isDeferredPaymentRoute(e)) {
                throw e;
            }
            log.warn("Payment route deferred after full return: orderId={}, status={}, reason={}",
                    order.getId(), e.getStatusCode(), e.getReason());
        }
        log.info("Payment cycle reopened after full provider return: orderId={}, linkId={}",
                order.getId(), link.getId());
        return Optional.of(order.getId());
    }

    private boolean positive(Long amount) {
        return amount != null && amount > 0;
    }

    private boolean isDeferredPaymentRoute(ResponseStatusException exception) {
        if (exception == null || exception.getStatusCode().value() != 409) {
            return false;
        }
        String reason = exception.getReason();
        if (reason == null) {
            return false;
        }
        String normalized = reason.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("платежные ссылки выключены")
                || normalized.contains("общий счет")
                || normalized.contains("общий счёт");
    }

    private String statusTitle(Order order) {
        return order == null || order.getStatus() == null || order.getStatus().getTitle() == null
                ? ""
                : order.getStatus().getTitle().trim();
    }

    public record PaymentLinkReturnOutboxClaim(
            Long paymentLinkId,
            PaymentLinkStatus observedStatus
    ) {
    }
}
