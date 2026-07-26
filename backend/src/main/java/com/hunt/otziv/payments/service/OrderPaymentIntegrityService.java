package com.hunt.otziv.payments.service;

import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.client_messages.model.ClientMessageScenario;
import com.hunt.otziv.client_messages.model.ScheduledClientMessageState;
import com.hunt.otziv.client_messages.model.ScheduledMessageStateStatus;
import com.hunt.otziv.client_messages.repository.ScheduledClientMessageStateRepository;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.services.service.OrderStatusService;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * A single source of truth for the invariant "a settled order must never enter
 * a second payment cycle".
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderPaymentIntegrityService {

    public static final String ENTITY_TYPE = "ORDER_PAYMENT_INTEGRITY";
    public static final String REPAIR_ERROR_CODE = "duplicate_payment_repaired";
    public static final String SUPPRESSED_ERROR_CODE = "duplicate_payment_suppressed";
    private static final String STATUS_PAID = "Оплачено";
    private static final String RETIRED_LINK_REASON =
            "Повторная платежная ссылка закрыта: заказ уже был полностью оплачен";

    private static final Set<PaymentLinkStatus> DUPLICATE_ACTIVE_LINK_STATUSES = Set.of(
            PaymentLinkStatus.CREATED,
            PaymentLinkStatus.INITIATED,
            PaymentLinkStatus.WAITING_MANUAL_PAYMENT,
            PaymentLinkStatus.MANUAL_REPORTED
    );
    private static final Set<ClientMessageScenario> PAYMENT_SCENARIOS = Set.of(
            ClientMessageScenario.PAYMENT_INVOICE_RETRY,
            ClientMessageScenario.PAYMENT_REMINDER,
            ClientMessageScenario.PAYMENT_OVERDUE_ESCALATION
    );

    private final OrderRepository orderRepository;
    private final PaymentLinkRepository paymentLinkRepository;
    private final ScheduledClientMessageStateRepository scheduledClientMessageStateRepository;
    private final OrderStatusService orderStatusService;
    private final BusinessAuditService businessAuditService;

    public boolean hasSettledPaymentEvidence(Order order) {
        if (order == null) {
            return false;
        }
        String status = order.getStatus() == null ? "" : order.getStatus().getTitle();
        return (order.isComplete() && order.getPayDay() != null) || STATUS_PAID.equals(status);
    }

    public boolean isDuplicatePaymentCycle(Order order) {
        if (!hasSettledPaymentEvidence(order)) {
            return false;
        }
        String status = order.getStatus() == null ? "" : order.getStatus().getTitle();
        return !"".equals(status) && !STATUS_PAID.equals(status);
    }

    public void assertPaymentCycleAllowed(Order order) {
        if (hasSettledPaymentEvidence(order)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Заказ уже полностью оплачен. Повторный счет заблокирован."
            );
        }
    }

    @Transactional
    public RepairResult repair(Long orderId) {
        if (orderId == null || orderId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректный ID заказа");
        }
        Order order = orderRepository.findByIdForCounterUpdate(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ не найден"));
        if (!hasSettledPaymentEvidence(order)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Автопочинка остановлена: в заказе нет надежного подтверждения полной оплаты"
            );
        }

        String oldStatus = order.getStatus() == null ? "" : order.getStatus().getTitle();
        List<PaymentLink> duplicateLinks = paymentLinkRepository.findByOrder_IdAndStatusIn(
                orderId,
                DUPLICATE_ACTIVE_LINK_STATUSES
        );
        for (PaymentLink link : duplicateLinks) {
            link.setStatus(PaymentLinkStatus.EXPIRED);
            link.setExpiresAt(LocalDateTime.now());
            link.setLastError(RETIRED_LINK_REASON);
        }
        if (!duplicateLinks.isEmpty()) {
            paymentLinkRepository.saveAll(duplicateLinks);
        }

        List<ScheduledClientMessageState> paymentStates = scheduledClientMessageStateRepository
                .findByOrderIdIn(List.of(orderId))
                .stream()
                .filter(state -> PAYMENT_SCENARIOS.contains(state.getScenario()))
                .filter(state -> state.getStatus() == ScheduledMessageStateStatus.ACTIVE
                        || state.getStatus() == ScheduledMessageStateStatus.PAUSED)
                .toList();
        LocalDateTime now = LocalDateTime.now();
        for (ScheduledClientMessageState state : paymentStates) {
            state.setStatus(ScheduledMessageStateStatus.DONE);
            state.setNextAttemptAt(null);
            state.setLockedUntil(null);
            state.setLastAttemptAt(now);
            state.setLastErrorCode(REPAIR_ERROR_CODE);
            state.setLastErrorMessage("Очередь закрыта: заказ уже был полностью оплачен");
            state.setConsecutiveFailures(0);
        }
        if (!paymentStates.isEmpty()) {
            scheduledClientMessageStateRepository.saveAll(paymentStates);
        }

        if (!STATUS_PAID.equals(oldStatus)) {
            order.setStatus(orderStatusService.getOrderStatusByTitle(STATUS_PAID));
            orderRepository.save(order);
        }

        RepairResult result = new RepairResult(orderId, oldStatus, duplicateLinks.size(), paymentStates.size());
        businessAuditService.recordSafely(
                "SETTLED_ORDER_STATUS_RESTORED",
                ENTITY_TYPE,
                orderId,
                orderId,
                null,
                oldStatus,
                STATUS_PAID,
                "Денежная операция не выполнялась. Восстановлен статус ранее оплаченного заказа. "
                        + "Закрыто лишних ссылок: " + result.expiredLinks()
                        + "; закрыто очередей: " + result.closedMessageStates()
                        + "; следующий заказ не изменялся"
        );
        log.warn(
                "Settled order status restored without a money operation: orderId={}, oldStatus={}, expiredLinks={}, closedStates={}",
                orderId,
                oldStatus,
                result.expiredLinks(),
                result.closedMessageStates()
        );
        return result;
    }

    public record RepairResult(
            Long orderId,
            String oldStatus,
            int expiredLinks,
            int closedMessageStates
    ) {
    }
}
