package com.hunt.otziv.payments.service;

import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.service.CompanyService;
import com.hunt.otziv.contractor_payments.service.ContractorCompletionRewardService;
import com.hunt.otziv.contractor_payments.service.ContractorRewardLedgerService;
import com.hunt.otziv.common_billing.repository.CommonInvoiceOrderRepository;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.status.service.OrderStatusTransitionService;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.z_zp.model.PaymentCheck;
import com.hunt.otziv.z_zp.repository.PaymentCheckRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Restores the payment cycle after a provider has durably confirmed a full
 * refund/reversal. Contractor route accounting is performed by the return
 * reconciler first; this service atomically removes the paid-order check,
 * salary and company totals before reopening the order cycle. It is safe to
 * call repeatedly: createForOrder reuses an active link and the financial
 * rollback is a no-op once the order is already unpaid.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentReturnOrderRecoveryService {

    private static final String STATUS_PAID = "Оплачено";
    private static final String STATUS_NOT_PAID = "Не оплачено";
    private static final String STATUS_REMINDER = "Напоминание";
    private static final String STATUS_TO_PAY = "Выставлен счет";
    private static final String RECOVERY_APPLIED = PaymentReturnRecoveryState.OUTCOME_APPLIED;
    private static final String RECOVERY_STALE_CYCLE = PaymentReturnRecoveryState.OUTCOME_STALE_PAYMENT_CYCLE;
    private static final String RECOVERY_MANUAL = PaymentReturnRecoveryState.OUTCOME_MANUAL_RECONCILIATION;

    private final PaymentLinkRepository paymentLinkRepository;
    private final OrderRepository orderRepository;
    private final OrderStatusTransitionService orderStatusTransitionService;
    private final PaymentLinkService paymentLinkService;
    private final ContractorCompletionRewardService contractorCompletionRewardService;
    private final PaymentCheckRepository paymentCheckRepository;
    private final CompanyService companyService;
    private final ContractorRewardLedgerService contractorRewardLedgerService;
    private final PaymentIssueReminderService paymentIssueReminderService;
    private final CommonInvoiceOrderRepository commonInvoiceOrderRepository;
    private final BusinessAuditService businessAuditService;

    /**
     * Returns the order id when a new payment cycle was opened.  Partial
     * returns, links without settled evidence, missing/archived sources and
     * common-invoice-owned orders are intentionally left for reconciliation.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Long> reopenAfterFullReturn(PaymentLinkReturnOutboxClaim claim) {
        if (claim == null || claim.paymentLinkId() == null
                || !PaymentReturnRecoveryState.isFullReturn(claim.observedStatus())) {
            return Optional.empty();
        }

        Long candidateOrderId = paymentLinkRepository.findOrderIdById(claim.paymentLinkId())
                .orElseThrow(() -> new IllegalStateException(
                        "Платежная ссылка возврата " + claim.paymentLinkId() + " отсутствует в live-таблице"));
        Order order = orderRepository.findByIdForCounterUpdate(candidateOrderId)
                .orElseThrow(() -> new IllegalStateException(
                        "Заказ платежной ссылки возврата " + claim.paymentLinkId() + " не найден"));
        PaymentLink link = paymentLinkRepository.findByIdForUpdate(claim.paymentLinkId())
                .orElseThrow(() -> new IllegalStateException(
                        "Платежная ссылка возврата " + claim.paymentLinkId() + " исчезла после блокировки заказа"));
        Long lockedLinkOrderId = link.getOrder() == null ? null : link.getOrder().getId();
        if (!candidateOrderId.equals(lockedLinkOrderId)
                || !PaymentReturnRecoveryState.isFullReturn(link.getStatus())) {
            throw new IllegalStateException(
                    "Платежная ссылка возврата изменила заказ или terminal-статус во время обработки: linkId="
                            + claim.paymentLinkId());
        }

        if (!PaymentReturnRecoveryState.isValidMarkerTuple(link)) {
            markManualReconciliation(link, link.getReturnRecoveryPaymentCheckId(),
                    "Поврежден или неизвестен маркер обработки возврата; автоматический откат заблокирован");
            return Optional.empty();
        }
        if (PaymentReturnRecoveryState.isTestPayment(link)) {
            if (PaymentReturnRecoveryState.isMarkerEmpty(link)
                    || RECOVERY_MANUAL.equals(link.getReturnRecoveryOutcome())) {
                acceptTestPaymentWithoutFinancialRecovery(link);
            } else if (PaymentReturnRecoveryState.isResolvedOutcome(
                    link.getReturnRecoveryOutcome())) {
                paymentIssueReminderService.resolveOrderIssueInCurrentTransaction(
                        PaymentIssueReminderService.SOURCE_PAYMENT_RETURN_RECONCILIATION,
                        link.getId()
                );
            }
            return Optional.empty();
        }
        if (!PaymentReturnRecoveryState.isMarkerEmpty(link)) {
            if (RECOVERY_APPLIED.equals(link.getReturnRecoveryOutcome())
                    && link.getReturnRecoveryPaymentCheckId() != null) {
                return STATUS_REMINDER.equals(statusTitle(order))
                        ? Optional.of(order.getId())
                        : Optional.empty();
            }
            if (RECOVERY_MANUAL.equals(link.getReturnRecoveryOutcome())) {
                notifyManualReconciliation(link, valueOrDefault(
                        link.getLastError(),
                        "Возврат платежа ожидает ручной сверки"
                ));
                return Optional.empty();
            }
            if (RECOVERY_STALE_CYCLE.equals(link.getReturnRecoveryOutcome())) {
                return Optional.empty();
            }
            if (PaymentReturnRecoveryState.OUTCOME_APPLIED_MANUALLY.equals(
                    link.getReturnRecoveryOutcome())) {
                if (!paymentIssueReminderService.hasOpenOrderIssue(
                        PaymentIssueReminderService.SOURCE_PAYMENT_RETURN_RECONCILIATION,
                        link.getId())) {
                    return Optional.empty();
                }
                String currentStatus = statusTitle(order);
                if (STATUS_PAID.equals(currentStatus)) {
                    paymentIssueReminderService.resolveOrderIssueInCurrentTransaction(
                            PaymentIssueReminderService.SOURCE_PAYMENT_RETURN_RECONCILIATION,
                            link.getId()
                    );
                    return Optional.empty();
                }
                if (!STATUS_REMINDER.equals(currentStatus)
                        && !STATUS_TO_PAY.equals(currentStatus)
                        && !STATUS_NOT_PAID.equals(currentStatus)) {
                    throw new IllegalStateException(
                            "Ручной откат завершен, но заказ не находится в статусе, допускающем повторное выставление");
                }
                return Optional.of(order.getId());
            }
            if (PaymentReturnRecoveryState.isResolvedOutcome(link.getReturnRecoveryOutcome())) {
                return Optional.empty();
            }
            throw new IllegalStateException("Необработанное допустимое состояние маркера возврата");
        }

        // CANCELED is also used for local/unpaid abandonment.  It becomes a
        // financial return only when this exact link carries settled evidence.
        // Leave the tuple empty so a later provider payment/return observation
        // for the same link can still be processed.
        if (link.getStatus() == PaymentLinkStatus.CANCELED
                && !STATUS_PAID.equals(statusTitle(order))
                && !PaymentReturnRecoveryState.hasLinkSpecificSettledEvidence(link)) {
            return Optional.empty();
        }

        if (STATUS_REMINDER.equals(statusTitle(order))) {
            markManualReconciliation(
                    link,
                    null,
                    "Заказ уже открыт повторно, но нет маркера финансового отката; нужна ручная сверка"
            );
            return Optional.empty();
        }
        if (!STATUS_PAID.equals(statusTitle(order))) {
            markRecoveryProcessed(link, null, RECOVERY_STALE_CYCLE);
            return Optional.empty();
        }

        List<PaymentCheck> checks = paymentLinkChecks(order.getId());
        if (checks.size() != 1) {
            markManualReconciliation(
                    link,
                    null,
                    "Активных чеков " + checks.size() + " вместо 1; автоматический откат заблокирован"
            );
            return Optional.empty();
        }
        PaymentCheck activeCheck = checks.getFirst();
        String checkIssue = activePaymentCheckIssue(order, activeCheck);
        if (checkIssue != null) {
            markManualReconciliation(link, activeCheck.getId(), checkIssue);
            return Optional.empty();
        }
        if (activeCheck.getPaymentLinkId() == null) {
            markManualReconciliation(
                    link,
                    activeCheck.getId(),
                    "У активного чека нет привязки к платежной ссылке; автоматический откат заблокирован"
            );
            return Optional.empty();
        }
        if (!java.util.Objects.equals(activeCheck.getPaymentLinkId(), link.getId())) {
            markRecoveryProcessed(link, activeCheck.getId(), RECOVERY_STALE_CYCLE);
            log.warn(
                    "Historical return belongs to another payment cycle and will not change the current check: "
                            + "orderId={}, returnedLinkId={}, activeCheckId={}, activeCheckLinkId={}",
                    order.getId(), link.getId(), activeCheck.getId(), activeCheck.getPaymentLinkId()
            );
            return Optional.empty();
        }
        if (!PaymentReturnRecoveryState.hasLinkSpecificSettledEvidence(link)) {
            markManualReconciliation(
                    link,
                    activeCheck.getId(),
                    "Полный возврат не содержит достоверного подтверждения оплаты exact source; автоматический откат заблокирован"
            );
            return Optional.empty();
        }
        if (commonInvoiceOrderRepository.existsByOrder_Id(order.getId())) {
            markManualReconciliation(
                    link,
                    activeCheck.getId(),
                    "Заказ входит в live-цикл общего счета; автоматический откат standalone-чека заблокирован"
            );
            return Optional.empty();
        }
        if (paymentLinkRepository.existsOtherPaymentBlockingReturn(order.getId(), link.getId())) {
            markManualReconciliation(
                    link,
                    activeCheck.getId(),
                    "У заказа есть другой подтвержденный или неоднозначный платеж; автоматический откат заблокирован"
            );
            return Optional.empty();
        }
        if (paymentLinkRepository.existsNewerManualPaidClosure(
                order.getId(), link.getId(), returnedAt(link))) {
            markManualReconciliation(
                    link,
                    activeCheck.getId(),
                    "После возвращенного платежа зафиксировано более новое ручное закрытие; нужна ручная сверка"
            );
            return Optional.empty();
        }
        Integer returnedAmountSnapshot = activeCheck.getPaidAmount();
        if (returnedAmountSnapshot == null || returnedAmountSnapshot < 0) {
            markManualReconciliation(
                    link,
                    activeCheck.getId(),
                    "В активном чеке нет достоверного снимка количества оплаченных работ; автоматический откат заблокирован"
            );
            return Optional.empty();
        }
        try {
            contractorRewardLedgerService.lockActiveOrderRewardsAndRequireCancellationRepresentable(order.getId());
        } catch (ResponseStatusException deterministicConflict) {
            markManualReconciliation(
                    link,
                    activeCheck.getId(),
                    valueOrDefault(deterministicConflict.getReason(), "Начисления нельзя откатить автоматически")
            );
            return Optional.empty();
        }
        BigDecimal returnedSum = activeCheck.getSum();
        int returnedAmount = returnedAmountSnapshot;
        Company company;
        try {
            company = requireCompanyRollback(order, returnedSum, returnedAmount);
        } catch (IllegalStateException | UsernameNotFoundException deterministicConflict) {
            markManualReconciliation(
                    link,
                    activeCheck.getId(),
                    valueOrDefault(deterministicConflict.getMessage(), "Итоги компании требуют ручной сверки")
            );
            return Optional.empty();
        }

        markRecoveryProcessed(link, activeCheck.getId(), RECOVERY_APPLIED);
        activeCheck.setActive(false);
        paymentCheckRepository.save(activeCheck);
        // V268 protects an active check with an FK to the paid order status.
        // Force the deactivation to SQL before changing the status.
        orderRepository.flush();

        contractorCompletionRewardService.deactivateOrderPaymentAccruals(
                order.getId(),
                "provider_full_return:" + link.getStatus()
        );
        applyCompanyRollback(order, company, returnedSum, returnedAmount);
        try {
            orderStatusTransitionService.changeStatusAfterPaymentReturn(order.getId(), STATUS_REMINDER);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Не удалось вернуть заказ " + order.getId() + " в статус \"Напоминание\"",
                    e
            );
        }

        log.info("Payment cycle reopened after full provider return: orderId={}, linkId={}",
                order.getId(), link.getId());
        return Optional.of(order.getId());
    }

    /**
     * The replacement route is deliberately prepared after the order-status
     * transaction has committed.  createForOrder may fail closed with a 409
     * while payment routes are disabled or a legacy task recipient is still
     * unresolved; letting that happen inside the status transaction would mark
     * it rollback-only even when the exception is caught.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void createReplacementPaymentRoute(Long orderId) {
        if (orderId == null) {
            return;
        }
        try {
            paymentLinkService.createForOrder(orderId);
        } catch (ResponseStatusException e) {
            if (!isDeferredPaymentRoute(e)) {
                throw e;
            }
            log.warn("Payment route deferred after full return: orderId={}, status={}, reason={}",
                    orderId, e.getStatusCode(), e.getReason());
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeManualReturnFollowUp(Long paymentLinkId) {
        if (paymentLinkId == null || paymentLinkId <= 0) {
            return;
        }
        Long orderId = paymentLinkRepository.findOrderIdById(paymentLinkId)
                .orElseThrow(() -> new IllegalStateException(
                        "Платежная ссылка ручного follow-up отсутствует: " + paymentLinkId));
        orderRepository.findByIdForCounterUpdate(orderId)
                .orElseThrow(() -> new IllegalStateException(
                        "Заказ ручного follow-up отсутствует: " + orderId));
        PaymentLink link = paymentLinkRepository.findByIdForUpdate(paymentLinkId)
                .orElseThrow(() -> new IllegalStateException(
                        "Платежная ссылка ручного follow-up исчезла: " + paymentLinkId));
        if (PaymentReturnRecoveryState.OUTCOME_APPLIED_MANUALLY.equals(
                link.getReturnRecoveryOutcome())) {
            paymentIssueReminderService.resolveOrderIssueInCurrentTransaction(
                    PaymentIssueReminderService.SOURCE_PAYMENT_RETURN_RECONCILIATION,
                    paymentLinkId
            );
        }
    }

    private List<PaymentCheck> paymentLinkChecks(Long orderId) {
        List<PaymentCheck> checks = paymentCheckRepository.findByOrderIdAndActiveTrue(orderId);
        return checks == null ? List.of() : checks;
    }

    private String activePaymentCheckIssue(Order order, PaymentCheck check) {
        Long companyId = order.getCompany() == null ? null : order.getCompany().getId();
        Long statusId = order.getStatus() == null ? null : order.getStatus().getId();
        if (!check.isActive()
                || !java.util.Objects.equals(check.getOrderId(), order.getId())
                || check.getSum() == null
                || check.getSum().signum() < 0
                || companyId == null
                || !java.util.Objects.equals(check.getCompanyId(), companyId)
                || statusId == null
                || !java.util.Objects.equals(check.getPaymentStatusGuard(), statusId)) {
            return "Активный чек не согласован с заказом; автоматический откат заблокирован";
        }
        return null;
    }

    private Company requireCompanyRollback(Order order, BigDecimal returnedSum, int returnedAmount) {
        if (order.getCompany() == null || order.getCompany().getId() == null) {
            throw new IllegalStateException("У оплаченного заказа не определена компания");
        }
        Company company = companyService.getCompaniesById(order.getCompany().getId());
        if (company == null) {
            throw new IllegalStateException("Компания оплаченного заказа не найдена");
        }
        if (returnedAmount < 0
                || company.getCounterPay() < returnedAmount
                || safeMoney(company.getSumTotal()).compareTo(returnedSum) < 0) {
            throw new IllegalStateException(
                    "Итоги компании не согласованы с возвращаемой оплатой заказа " + order.getId()
            );
        }
        return company;
    }

    private void applyCompanyRollback(
            Order order,
            Company company,
            BigDecimal returnedSum,
            int returnedAmount
    ) {
        company.setCounterPay(company.getCounterPay() - returnedAmount);
        company.setSumTotal(safeMoney(company.getSumTotal()).subtract(returnedSum));
        companyService.save(company);
        order.setCompany(company);
    }

    private BigDecimal safeMoney(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private void markManualReconciliation(PaymentLink link, Long paymentCheckId, String reason) {
        markRecoveryProcessed(link, paymentCheckId, RECOVERY_MANUAL);
        String message = "payment_return_manual_reconciliation: " + reason;
        link.setLastError(message.length() <= 512 ? message : message.substring(0, 512));
        notifyManualReconciliation(link, reason);
        log.error("{}: linkId={}, orderId={}, paymentCheckId={}",
                reason,
                link.getId(),
                link.getOrder() == null ? null : link.getOrder().getId(),
                paymentCheckId);
    }

    private void acceptTestPaymentWithoutFinancialRecovery(PaymentLink link) {
        String previousOutcome = valueOrDefault(link.getReturnRecoveryOutcome(), "EMPTY");
        String originalManualCause = valueOrDefault(link.getLastError(), "none");
        String reason = "Тестовый платеж исключен из финансового recovery; "
                + "откат заказа, чека и итогов компании не выполнялся";
        LocalDateTime resolvedAt = LocalDateTime.now();
        if (link.getReturnRecoveryProcessedAt() == null) {
            link.setReturnRecoveryProcessedAt(resolvedAt);
        }
        link.setReturnRecoveryOutcome(PaymentReturnRecoveryState.OUTCOME_ACCEPTED_NOOP);
        link.setReturnRecoveryResolvedAt(resolvedAt);
        link.setReturnRecoveryResolvedBy("system:test-payment-return-filter");
        link.setReturnRecoveryResolutionReason(reason);
        String summary = "payment_return_manual_resolution_resolved: outcome="
                + PaymentReturnRecoveryState.OUTCOME_ACCEPTED_NOOP + "; reason=" + reason;
        link.setLastError(summary.length() <= 512 ? summary : summary.substring(0, 512));
        paymentLinkRepository.saveAndFlush(link);

        paymentIssueReminderService.resolveOrderIssueInCurrentTransaction(
                PaymentIssueReminderService.SOURCE_PAYMENT_RETURN_RECONCILIATION,
                link.getId()
        );
        businessAuditService.recordRequiredInCurrentTransaction(
                "PAYMENT_RETURN_TEST_RECOVERY_IGNORED",
                "PAYMENT_LINK",
                link.getId(),
                link.getOrder() == null ? null : link.getOrder().getId(),
                null,
                previousOutcome,
                PaymentReturnRecoveryState.OUTCOME_ACCEPTED_NOOP,
                "originalManualCause=" + originalManualCause
                        + "; test payment excluded from financial order recovery; "
                        + "no order, payment check, company or reward mutation was performed"
        );
        log.info("Test payment return excluded from financial recovery: linkId={}, orderId={}",
                link.getId(), link.getOrder() == null ? null : link.getOrder().getId());
    }

    private void notifyManualReconciliation(PaymentLink link, String reason) {
        Long orderId = link.getOrder() == null ? null : link.getOrder().getId();
        paymentIssueReminderService.ensureOrderIssuePersisted(
                link.getOrder(),
                PaymentIssueReminderService.SOURCE_PAYMENT_RETURN_RECONCILIATION,
                link.getId(),
                "Нужна сверка возврата по заказу №" + orderId,
                "Возврат по платежной ссылке №" + link.getId()
                        + " не применен автоматически. " + valueOrDefault(reason, "Проверьте финансовый цикл вручную.")
        );
    }

    private String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private void markRecoveryProcessed(PaymentLink link, Long paymentCheckId, String outcome) {
        PaymentReturnRecoveryState.markProcessed(link, paymentCheckId, outcome);
    }

    private LocalDateTime returnedAt(PaymentLink link) {
        if (link.getPaidAt() != null) {
            return link.getPaidAt();
        }
        if (link.getManualConfirmedAt() != null) {
            return link.getManualConfirmedAt();
        }
        if (link.getCreatedAt() != null) {
            return link.getCreatedAt();
        }
        return LocalDateTime.MIN;
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
                || normalized.contains("получатель платёжного задания не привязан")
                || normalized.contains("получатель платежного задания не привязан")
                || normalized.contains("оплату нужно сверить вручную")
                || normalized.contains("у заказа уже есть созданный банковский платеж")
                || normalized.contains("у заказа уже есть созданный банковский платёж")
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
