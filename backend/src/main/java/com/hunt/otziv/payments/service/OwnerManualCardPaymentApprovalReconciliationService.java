package com.hunt.otziv.payments.service;

import com.hunt.otziv.manager.service.ManagerAccessService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.payments.model.OwnerManualCardPaymentApprovalStatus;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.repository.OwnerManualCardPaymentApprovalRepository;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class OwnerManualCardPaymentApprovalReconciliationService {
    private final OwnerManualCardPaymentApprovalRepository approvals;
    private final OrderRepository orders;
    private final PaymentLinkRepository links;
    private final ManagerAccessService access;
    private final ManualCardPaymentReviewNotificationService notifications;

    @Transactional
    public Result closeSuperseded(Long approvalId, Authentication authentication) {
        var approval = approvals.findByIdForUpdate(approvalId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Запрос не найден"));
        access.requireOrderAccess(approval.getOrderId(), authentication);
        Order order = orders.findByIdForCounterUpdate(approval.getOrderId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ не найден"));
        if (approval.getStatus() == OwnerManualCardPaymentApprovalStatus.SUPERSEDED) {
            notifications.closeOwnerApprovalReminders(approvalId);
            return new Result(approvalId, order.getId(), "SUPERSEDED", true);
        }
        if (approval.getStatus() != OwnerManualCardPaymentApprovalStatus.PENDING
                || order.getStatus() == null || !"Оплачено".equals(order.getStatus().getTitle())) {
            throw conflict("Закрыть можно только устаревший запрос по уже оплаченному заказу");
        }
        PaymentLink original = links.findByIdForUpdate(approval.getPaymentLinkId())
                .orElseThrow(() -> conflict("Исходный счёт не найден"));
        if (original.getOrder() == null || !Objects.equals(original.getOrder().getId(), order.getId())
                || original.getStatus() != PaymentLinkStatus.CANCELED) {
            throw conflict("Исходный счёт должен быть отменён; действующее подтверждение не закрыто");
        }
        PaymentLink replacement = links.findByOrderIdAndStatusInForUpdate(
                        order.getId(), List.of(PaymentLinkStatus.CONFIRMED)).stream()
                .filter(link -> !Objects.equals(link.getId(), original.getId()))
                .filter(link -> confirmedAmount(link) >= approval.getAmountKopecks()
                        && approval.getAmountKopecks() > 0)
                .findFirst().orElseThrow(() -> conflict("Нет другого подтверждённого платежа на сумму запроса"));
        approval.setStatus(OwnerManualCardPaymentApprovalStatus.SUPERSEDED);
        String actor = authentication == null ? "" : authentication.getName();
        String reason = "Запрос закрыт как устаревший: исходный счёт №" + original.getId()
                + " отменён, заказ оплачен по счёту №" + replacement.getId()
                + ". Повторного зачисления не было. Сверку выполнил: " + actor;
        approval.setLastError(reason.substring(0, Math.min(512, reason.length())));
        approvals.save(approval);
        notifications.closeOwnerApprovalReminders(approvalId);
        return new Result(approvalId, order.getId(), "SUPERSEDED", false);
    }

    private long confirmedAmount(PaymentLink link) {
        return link.getConfirmedAmountKopecks() == null ? link.getAmountKopecks() : link.getConfirmedAmountKopecks();
    }

    private ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }

    public record Result(Long approvalId, Long orderId, String status, boolean alreadyClosed) {}
}
