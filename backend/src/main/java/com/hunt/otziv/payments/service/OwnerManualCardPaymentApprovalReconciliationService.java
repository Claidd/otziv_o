package com.hunt.otziv.payments.service;

import com.hunt.otziv.manager.service.ManagerAccessService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.payments.model.OwnerManualCardPaymentApprovalStatus;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.model.PaymentMethod;
import com.hunt.otziv.payments.repository.OwnerManualCardPaymentApprovalRepository;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
        var binding = approvals.findBindingById(approvalId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Запрос не найден"));
        access.requireOrderAccess(binding.getOrderId(), authentication);
        String actor = authentication == null ? "" : authentication.getName();
        return close(approvalId, binding, "Сверку выполнил: " + actor);
    }

    /**
     * The same rules without a person: the system closes a request only when the order is already paid by
     * another confirmed payment or by the bank on the very same link. Nothing is credited or sent.
     */
    @Transactional
    public Optional<Result> closeSupersededAutomatically(Long approvalId) {
        var binding = approvals.findBindingById(approvalId).orElse(null);
        if (binding == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(close(approvalId, binding, "Закрыт автоматически: заказ уже оплачен"));
        } catch (ResponseStatusException stillNeeded) {
            return Optional.empty();
        }
    }

    private Result close(Long approvalId, OwnerManualCardPaymentApprovalRepository.ApprovalBinding binding, String closedBy) {
        Order order = orders.findByIdForCounterUpdate(binding.getOrderId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ не найден"));
        PaymentLink original = links.findByIdForUpdate(binding.getPaymentLinkId())
                .orElseThrow(() -> conflict("Исходный счёт не найден"));
        var approval = approvals.findByIdForUpdate(approvalId)
                .orElseThrow(() -> conflict("Запрос изменился"));
        if (!Objects.equals(approval.getOrderId(), order.getId())
                || !Objects.equals(approval.getPaymentLinkId(), original.getId())
                || !Objects.equals(original.getOrderId(), order.getId())) {
            throw conflict("Связь запроса с заказом или счётом изменилась");
        }
        if (approval.getStatus() == OwnerManualCardPaymentApprovalStatus.SUPERSEDED) {
            notifications.closeOwnerApprovalReminders(approvalId);
            return new Result(approvalId, order.getId(), "SUPERSEDED", true);
        }
        if (approval.getStatus() != OwnerManualCardPaymentApprovalStatus.PENDING
                || order.getStatus() == null || !"Оплачено".equals(order.getStatus().getTitle())) {
            throw conflict("Закрыть можно только устаревший запрос по уже оплаченному заказу");
        }
        if (original.getOrder() == null || !Objects.equals(original.getOrder().getId(), order.getId())) {
            throw conflict("Исходный счёт должен быть отменён; действующее подтверждение не закрыто");
        }
        if (paidByBankOnSameLink(original, approval.getAmountKopecks())) {
            approval.setStatus(OwnerManualCardPaymentApprovalStatus.SUPERSEDED);
            String reason = "Запрос закрыт как устаревший: оплата по счёту №" + original.getId()
                    + " подтверждена банком. Повторного зачисления не было. " + closedBy;
            approval.setLastError(reason.substring(0, Math.min(512, reason.length())));
            approvals.save(approval);
            notifications.closeOwnerApprovalReminders(approvalId);
            return new Result(approvalId, order.getId(), "SUPERSEDED", false);
        }
        if (original.getStatus() != PaymentLinkStatus.CANCELED) {
            throw conflict("Исходный счёт должен быть отменён; действующее подтверждение не закрыто");
        }
        PaymentLink replacement = links.findByOrderIdAndStatusInForUpdate(
                        order.getId(), List.of(PaymentLinkStatus.CONFIRMED)).stream()
                .filter(link -> !Objects.equals(link.getId(), original.getId()))
                .filter(link -> confirmedAmount(link) >= approval.getAmountKopecks()
                        && approval.getAmountKopecks() > 0)
                .findFirst().orElseThrow(() -> conflict("Нет другого подтверждённого платежа на сумму запроса"));
        approval.setStatus(OwnerManualCardPaymentApprovalStatus.SUPERSEDED);
        String reason = "Запрос закрыт как устаревший: исходный счёт №" + original.getId()
                + " отменён, заказ оплачен по счёту №" + replacement.getId()
                + ". Повторного зачисления не было. " + closedBy;
        approval.setLastError(reason.substring(0, Math.min(512, reason.length())));
        approvals.save(approval);
        notifications.closeOwnerApprovalReminders(approvalId);
        return new Result(approvalId, order.getId(), "SUPERSEDED", false);
    }

    /** The bank itself confirmed the payment of the link the owner was asked about: the request is moot. */
    private boolean paidByBankOnSameLink(PaymentLink link, long amountKopecks) {
        return link.getStatus() == PaymentLinkStatus.CONFIRMED
                && (link.getPaymentMethod() == PaymentMethod.BANK_FORM || link.getPaymentMethod() == PaymentMethod.SBP_QR)
                && link.getPaidAt() != null
                && link.getManualConfirmedAt() == null
                && link.getTbankPaymentId() != null && !link.getTbankPaymentId().isBlank()
                && amountKopecks > 0
                && confirmedAmount(link) >= amountKopecks;
    }

    private long confirmedAmount(PaymentLink link) {
        return link.getConfirmedAmountKopecks() == null ? link.getAmountKopecks() : link.getConfirmedAmountKopecks();
    }

    private ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }

    public record Result(Long approvalId, Long orderId, String status, boolean alreadyClosed) {}
}
