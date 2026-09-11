package com.hunt.otziv.payments.service;

import static com.hunt.otziv.payments.service.PaymentLinkCancellationWorkflow.CONFIRMED_LIKE_BANK_STATUSES;
import static com.hunt.otziv.payments.service.PaymentLinkCancellationWorkflow.REFUNDED_STATUSES;
import static com.hunt.otziv.payments.service.PaymentLinkCancellationWorkflow.REFUND_OR_REVERSAL_BANK_STATUSES;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.client_messages.service.PaymentInvoiceRetryScheduler;
import com.hunt.otziv.common_billing.api.CommonInvoicePaymentOperations;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.service.OrderTransactionService;
import com.hunt.otziv.review_recovery.service.ReviewRecoveryGateService;
import com.hunt.otziv.payments.tochka.dto.TochkaPaymentProfile;
import com.hunt.otziv.payments.tochka.service.TochkaPaymentOperationMapper.MappedPayment;
import com.hunt.otziv.payments.tochka.service.TochkaPaymentProfileResolver;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.model.PaymentProfile;
import com.hunt.otziv.payments.model.PaymentReceiptStatus;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import com.hunt.otziv.z_zp.service.PaymentCheckService;
import com.hunt.otziv.z_zp.service.PaymentCheckSourceContext;

/**
 * Owns standalone provider finalization, duplicate/mismatch checks and confirmed
 * prepayment application. State-changing entry points join the transaction whose
 * caller already holds Order -> PaymentLink locks. Prepayment recovery acquires
 * those locks itself and keeps its independent transaction boundary.
 * Join-only operations leave rollback decisions to their caller, including the
 * existing no-rollback recovery paths which persist quarantine before returning
 * an error. They do not add a rollback-only mark when an error is handled there.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentLinkSettlementService {

    private final PaymentLinkAmountPolicy amountPolicy;

    private final PaymentLinkCancellationWorkflow cancellationWorkflow;

    private final PaymentLinkPresenter paymentPresenter;

    private final CommonInvoiceRouteSelector commonInvoiceRouteSelector;

    static final String CONTRACTOR_SOURCE_CONFIRMATION_AUDIT_PREFIX = "contractor_source_confirmation";

    static final String PREPAID_WAITING_ORDER_COMPLETION = "prepaid_waiting_order_completion";

    static final String PREPAID_APPLY_FAILED = "prepaid_apply_failed:";

    private final PaymentLinkRepository paymentLinkRepository;

    private final OrderRepository orderRepository;

    private final ReviewRecoveryGateService reviewRecoveryGateService;

    private final OrderTransactionService orderTransactionService;

    private final PaymentCheckService paymentCheckService;

    private final TbankRuntimeSettingsService runtimeSettingsService;

    private final PaymentProfileService paymentProfileService;

    private final TochkaPaymentProfileResolver tochkaPaymentProfileResolver;

    private final PaymentSuccessNotificationDeliveryService paymentSuccessNotificationDeliveryService;

    private final PaymentInvoiceRetryScheduler paymentInvoiceRetryScheduler;

    private final CommonInvoicePaymentOperations commonInvoicePayments;

    private final PaymentLinkReturnOutboxService paymentLinkReturnOutboxService;

    private final PaymentLinkTransactionExecutor transactionExecutor;

    private boolean isTochkaPaymentLink(PaymentLink link) {
        return paymentPresenter.isTochkaPaymentLink(link);
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = Exception.class)
    void applyTochkaMappedStatus(PaymentLink link, MappedPayment mapped, boolean providerTestMode) {
        switch(mapped.status()) {
            case INITIATED ->
                {
                    if (link.getStatus() == PaymentLinkStatus.CREATED || link.getStatus() == PaymentLinkStatus.INITIATED) {
                        link.setStatus(PaymentLinkStatus.INITIATED);
                        link.setLastError(null);
                    }
                }
            case CONFIRMED ->
                confirmPayment(link, providerTestMode);
            case EXPIRED ->
                markFinalBankStatus(link, PaymentLinkStatus.EXPIRED, mapped.providerStatus());
            case REFUNDED, PARTIAL_REFUNDED ->
                markFinalBankStatus(link, mapped.status(), mapped.providerStatus());
            case NEEDS_RECONCILIATION ->
                quarantineTochkaState(link, "tochka_status_requires_reconciliation: " + mapped.providerStatus());
            default ->
                quarantineTochkaState(link, "tochka_status_not_supported_for_one_stage_flow: " + mapped.providerStatus());
        }
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = Exception.class)
    void quarantineTochkaState(PaymentLink link, String reason) {
        if (link != null && !isFinalStatus(link.getStatus())) {
            link.setStatus(PaymentLinkStatus.NEEDS_RECONCILIATION);
            link.setLastError(limit(reason, 512));
        }
    }

    void cancelBadReviewAutoBanAfterCommit(Order order, String reason) {
        Long orderId = order == null ? null : order.getId();
        if (orderId == null) {
            return;
        }
        Runnable cleanup = () -> {
            try {
                paymentInvoiceRetryScheduler.cancelBadReviewAutoBanInNewTransaction(orderId, reason);
            } catch (RuntimeException e) {
                // Payment has already committed. The scheduler remains
                // idempotent and can be reconciled independently; never turn a
                // successful payment into FAILED because reminder cleanup was
                // temporarily unavailable.
                log.error("Не удалось закрыть расписание авто-бана после оплаты orderId={}", orderId, e);
            }
        };
        if (TransactionSynchronizationManager.isActualTransactionActive() && TransactionSynchronizationManager.isSynchronizationActive()) {
            // Payment mutations hold Order/PaymentLink. The scheduler worker
            // may hold ScheduledState before reading Order, so touch scheduled
            // state only after the payment transaction releases its locks.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {

                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_COMMITTED) {
                        cleanup.run();
                    }
                }
            });
            return;
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            log.warn("Пропущена синхронная очистка авто-бана без transaction synchronization orderId={}", orderId);
            return;
        }
        cleanup.run();
    }

    /**
     * The source row is still locked by the payment mutation, while the
     * contractor reconciler starts by taking the same source lock. Run it only
     * after commit so SHADOW and LIVE allocation states are updated without a
     * self-deadlock. The durable PaymentLink state remains available to the
     * periodic claim worker if this best-effort fast path fails.
     */
    private void reconcileContractorPaymentRouteAfterCommit(Long paymentLinkId) {
        cancellationWorkflow.reconcileContractorPaymentRouteAfterCommit(paymentLinkId);
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = Exception.class)
    void applyBankStatus(PaymentLink link, String status, boolean success, String errorCode) {
        if (shouldIgnoreStaleBankStatus(link, status)) {
            log.info("Stale T-Bank status ignored for terminal payment: linkId={}, current={}, incoming={}", link.getId(), link.getStatus(), status);
            return;
        }
        switch(status) {
            case "CONFIRMED" ->
                confirmPayment(link);
            case "AUTHORIZED" ->
                {
                    if (!isFinalStatus(link.getStatus())) {
                        link.setStatus(PaymentLinkStatus.AUTHORIZED);
                        link.setLastError(null);
                    }
                }
            case "NEW" ->
                {
                    if (link.getStatus() == PaymentLinkStatus.CREATED) {
                        link.setStatus(PaymentLinkStatus.INITIATED);
                    }
                }
            case "REJECTED" ->
                {
                    link.setStatus(PaymentLinkStatus.REJECTED);
                    link.setProviderTerminalStatus("REJECTED");
                    link.setLastError(errorCode);
                }
            case "CANCELED" ->
                markFinalBankStatus(link, PaymentLinkStatus.CANCELED, status);
            case "REVERSED" ->
                markFinalBankStatus(link, PaymentLinkStatus.REVERSED, status);
            case "PARTIAL_REVERSED" ->
                markFinalBankStatus(link, PaymentLinkStatus.PARTIAL_REVERSED, status);
            case "REFUNDED" ->
                markFinalBankStatus(link, PaymentLinkStatus.REFUNDED, status);
            case "PARTIAL_REFUNDED" ->
                markFinalBankStatus(link, PaymentLinkStatus.PARTIAL_REFUNDED, status);
            case "DEADLINE_EXPIRED" ->
                markFinalBankStatus(link, PaymentLinkStatus.EXPIRED, status);
            default ->
                {
                    if (!success && !errorCode.isBlank() && !"0".equals(errorCode)) {
                        if (link.getStatus() == PaymentLinkStatus.NEEDS_RECONCILIATION) {
                            // An unknown/non-terminal webhook response must not release
                            // a payment that the bank has already created from its
                            // reconciliation quarantine. Only an explicit bank status
                            // above is allowed to make a retry/new invoice safe.
                            link.setLastError(limit("bank_status_reconciliation_error: " + errorCode, 512));
                        } else {
                            link.setStatus(PaymentLinkStatus.FAILED);
                            link.setLastError(errorCode);
                        }
                    }
                    log.info("T-Bank status stored without final transition: linkId={}, status={}", link.getId(), status);
                }
        }
        if (REFUNDED_STATUSES.contains(link.getStatus())) {
            paymentLinkReturnOutboxService.enqueue(link);
            // The provider state is now durable. Reconcile after commit even
            // for OWNER/EXTERNAL_TASK task receipts that have no contractor
            // allocation and therefore no periodic allocation claim.
            reconcileContractorPaymentRouteAfterCommit(link.getId());
        }
    }

    private void confirmPayment(PaymentLink link) {
        confirmPayment(link, (Boolean) null);
    }

    private void confirmPayment(PaymentLink link, boolean providerTestPayment) {
        confirmPayment(link, Boolean.valueOf(providerTestPayment));
    }

    private void confirmPayment(PaymentLink link, Boolean providerTestModeOverride) {
        if (link.getStatus() == PaymentLinkStatus.CONFIRMED) {
            rememberCompanyPayerEmail(link);
            prepareSuccessNotificationRetry(link);
            return;
        }
        if (link.getStatus() == PaymentLinkStatus.AMOUNT_MISMATCH) {
            rememberCompanyPayerEmail(link);
            return;
        }
        if (link.getStatus() == PaymentLinkStatus.TEST_CONFIRMED) {
            rememberCompanyPayerEmail(link);
            return;
        }
        if (hasAnotherConfirmedPayment(link)) {
            markDuplicateConfirmedPayment(link);
            return;
        }
        if (link.getStatus() == PaymentLinkStatus.CANCELED || link.getStatus() == PaymentLinkStatus.EXPIRED) {
            markClosedLinkConfirmed(link);
            return;
        }
        if (markAmountMismatchIfNeeded(link)) {
            return;
        }
        boolean providerTestPayment = providerTestModeOverride != null ? providerTestModeOverride : isProviderTestPayment(link);
        if (!runtimeSettingsService.isApplyConfirmedPayments() || providerTestPayment) {
            link.setStatus(PaymentLinkStatus.TEST_CONFIRMED);
            link.setPaidAt(LocalDateTime.now());
            link.setConfirmedAmountKopecks(link.getAmountKopecks());
            link.setLastError(null);
            rememberCompanyPayerEmail(link);
            log.info("T-Bank payment confirmed in test mode without applying order transition: linkId={}, orderId={}", link.getId(), link.getOrder() == null ? null : link.getOrder().getId());
            return;
        }
        if (!canApplyOrderPaymentNow(link.getOrder())) {
            markOrderPrepaid(link);
            prepareSuccessNotificationRetry(link);
            return;
        }
        try {
            boolean updated = handlePaymentStatusWithoutPrematureRepeat(link.getOrder(), link.getId());
            link.setStatus(PaymentLinkStatus.CONFIRMED);
            link.setPaidAt(LocalDateTime.now());
            link.setConfirmedAmountKopecks(link.getAmountKopecks());
            link.setLastError(null);
            rememberCompanyPayerEmail(link);
            prepareSuccessNotificationRetry(link);
            if (updated) {
                cancelBadReviewAutoBanAfterCommit(link.getOrder(), "T-Bank/SBP оплата подтверждена");
            }
            syncCommonInvoiceOrderPayment(link, "T-Bank/SBP оплата заказа");
        } catch (Exception e) {
            link.setStatus(PaymentLinkStatus.FAILED);
            link.setLastError("Order payment transition failed");
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Не удалось перевести заказ в оплату", e);
        }
    }

    private boolean isProviderTestPayment(PaymentLink link) {
        if (!isTochkaPaymentLink(link)) {
            return paymentProfileService.isTestTerminal(link.getTbankTerminalKey());
        }
        PaymentProfile pinnedProfile = link.getPaymentProfile();
        TochkaPaymentProfile runtimeProfile = tochkaPaymentProfileResolver.resolveForExistingPayment(pinnedProfile);
        if (!normalize(link.getTbankTerminalKey()).equals(runtimeProfile.merchantId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "MerchantId платежа Точки не совпадает с закрепленным профилем");
        }
        return runtimeProfile.testMode();
    }

    boolean shouldIgnoreStaleBankStatus(PaymentLink link, String incomingStatus) {
        PaymentLinkStatus current = link == null ? null : link.getStatus();
        String incoming = normalize(incomingStatus).toUpperCase();
        if (CONFIRMED_LIKE_BANK_STATUSES.contains(current)) {
            if ("CANCELED".equals(incoming) && link.getBankCancelOriginStatus() != null) {
                return false;
            }
            return !"CONFIRMED".equals(incoming) && !isRefundOrReversalBankStatus(incoming);
        }
        if (!REFUND_OR_REVERSAL_BANK_STATUSES.contains(current)) {
            return false;
        }
        return !isAllowedRefundProgress(current, incoming);
    }

    private boolean isRefundOrReversalBankStatus(String status) {
        return cancellationWorkflow.isRefundOrReversalBankStatus(status);
    }

    private boolean isAllowedRefundProgress(PaymentLinkStatus current, String incoming) {
        return cancellationWorkflow.isAllowedRefundProgress(current, incoming);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean applyConfirmedPrepaymentIfReady(Long orderId) {
        if (orderId == null || orderId <= 0) {
            return false;
        }
        try {
            return transactionExecutor.required(() -> {
                Order lockedOrder = orderRepository.findByIdForCounterUpdate(orderId).orElse(null);
                return applyConfirmedPrepaymentIfReadyLocked(lockedOrder);
            });
        } catch (RuntimeException failure) {
            recordConfirmedPrepaymentApplyFailure(orderId, failure);
            log.error("Confirmed prepayment recovery failed: orderId={}", orderId, failure);
            return false;
        }
    }

    @Transactional
    public boolean applyConfirmedPrepaymentIfReady(Order order) {
        if (order == null || order.getId() == null) {
            return false;
        }
        Order lockedOrder = orderRepository.findByIdForCounterUpdate(order.getId()).orElse(null);
        return applyConfirmedPrepaymentIfReadyLocked(lockedOrder);
    }

    private boolean applyConfirmedPrepaymentIfReadyLocked(Order order) {
        if (order == null || order.getId() == null) {
            return false;
        }
        Optional<PaymentLink> optionalLink = paymentLinkRepository.findFirstByOrder_IdAndStatusAndLastErrorStartingWithOrderByPaidAtDesc(order.getId(), PaymentLinkStatus.CONFIRMED, PREPAID_WAITING_ORDER_COMPLETION);
        if (optionalLink.isEmpty()) {
            return false;
        }
        PaymentLink link = optionalLink.get();
        if (!canApplyOrderPaymentNow(order)) {
            return false;
        }
        if (markAmountMismatchIfNeeded(link)) {
            paymentLinkRepository.save(link);
            return false;
        }
        try {
            boolean updated = handlePaymentStatusWithoutPrematureRepeat(order, link.getId());
            String linkAudit = normalize(link.getLastError());
            int sourceAuditIndex = linkAudit.indexOf(CONTRACTOR_SOURCE_CONFIRMATION_AUDIT_PREFIX);
            link.setLastError(sourceAuditIndex >= 0 ? linkAudit.substring(sourceAuditIndex) : null);
            paymentLinkRepository.save(link);
            if (updated) {
                cancelBadReviewAutoBanAfterCommit(order, "Предоплата применена после завершения заказа");
            }
            syncCommonInvoiceOrderPayment(link, "Предоплата заказа применена после завершения");
            log.info("Предоплата по ссылке {} применена после завершения заказа {}", link.getId(), order.getId());
            return true;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Не удалось применить предоплату заказа", e);
        }
    }

    private void recordConfirmedPrepaymentApplyFailure(Long orderId, RuntimeException failure) {
        try {
            transactionExecutor.requiredNoRollback(() -> {
                if (orderRepository.findByIdForCounterUpdate(orderId).isEmpty()) {
                    return null;
                }
                Optional<PaymentLink> candidate = paymentLinkRepository.findFirstByOrder_IdAndStatusAndLastErrorStartingWithOrderByPaidAtDesc(orderId, PaymentLinkStatus.CONFIRMED, PREPAID_WAITING_ORDER_COMPLETION);
                candidate.ifPresent(link -> {
                    String audit = normalize(link.getLastError());
                    int previousFailure = audit.indexOf(PREPAID_APPLY_FAILED);
                    if (previousFailure >= 0) {
                        audit = audit.substring(0, previousFailure).stripTrailing();
                        if (audit.endsWith(";")) {
                            audit = audit.substring(0, audit.length() - 1).stripTrailing();
                        }
                    }
                    link.setLastError(limit(audit + "; " + PREPAID_APPLY_FAILED + " " + paymentFailureReason(failure), 512));
                    paymentLinkRepository.save(link);
                });
                return null;
            });
        } catch (RuntimeException quarantineFailure) {
            log.error("Failed to persist confirmed prepayment recovery error: orderId={}", orderId, quarantineFailure);
        }
    }

    String paymentFailureReason(Throwable failure) {
        Throwable current = failure;
        String detail = "";
        while (current != null) {
            if (current instanceof ResponseStatusException response && response.getReason() != null && !response.getReason().isBlank()) {
                detail = response.getReason();
            } else if (current.getMessage() != null && !current.getMessage().isBlank()) {
                detail = current.getMessage();
            }
            current = current.getCause();
        }
        String type = failure == null ? "RuntimeException" : failure.getClass().getSimpleName();
        return detail.isBlank() ? type : type + ": " + detail;
    }

    boolean canApplyOrderPaymentNow(Order order) {
        return order != null && order.getId() != null && (order.isComplete() || order.getAmount() <= order.getCounter()) && !reviewRecoveryGateService.hasActiveRecoveryTasks(order.getId());
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = Exception.class)
    boolean handlePaymentStatusWithoutPrematureRepeat(Order order, Long paymentLinkId) throws Exception {
        CommonInvoicePaymentOperations commonBillingService = commonInvoicePayments;
        Long orderId = order == null ? null : order.getId();
        boolean updated = PaymentCheckSourceContext.withPaymentLink(paymentLinkId, () -> {
            if (orderId == null) {
                return orderTransactionService.handlePaymentStatus(order);
            }
            if (commonBillingService.isOrderInActiveCommonInvoice(orderId)) {
                return orderTransactionService.handlePaymentStatus(order, false);
            }
            return orderTransactionService.handlePaymentStatus(order);
        });
        if (updated) {
            paymentCheckService.assertActiveCheckBoundToPaymentLink(orderId, paymentLinkId);
        }
        return updated;
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = Exception.class)
    void syncCommonInvoiceOrderPayment(PaymentLink link, String reason) {
        CommonInvoicePaymentOperations commonBillingService = commonInvoicePayments;
        Order order = link == null ? null : link.getOrder();
        Long orderId = order == null ? null : order.getId();
        if (orderId == null) {
            return;
        }
        commonBillingService.applyConfirmedOrderPayment(orderId, link.getPaidAt(), reason);
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = Exception.class)
    void markOrderPrepaid(PaymentLink link) {
        link.setStatus(PaymentLinkStatus.CONFIRMED);
        link.setPaidAt(LocalDateTime.now());
        link.setConfirmedAmountKopecks(link.getAmountKopecks());
        link.setReceiptStatus(PaymentReceiptStatus.PENDING);
        link.setLastError(PREPAID_WAITING_ORDER_COMPLETION);
        rememberCompanyPayerEmail(link);
        log.info("Платеж по заказу {} принят как предоплата: linkId={}, amount={}", link.getOrder() == null ? null : link.getOrder().getId(), link.getId(), link.getAmountKopecks());
    }

    private void markClosedLinkConfirmed(PaymentLink link) {
        link.setStatus(PaymentLinkStatus.AMOUNT_MISMATCH);
        link.setPaidAt(LocalDateTime.now());
        link.setConfirmedAmountKopecks(link.getAmountKopecks());
        link.setLastError("Платеж пришел по закрытой ссылке: заказ уже закрыт вручную или ссылка была недоступна");
        rememberCompanyPayerEmail(link);
        log.warn("Payment confirmed for retired link: linkId={}, orderId={}, amount={}", link.getId(), link.getOrder() == null ? null : link.getOrder().getId(), link.getAmountKopecks());
    }

    private boolean markAmountMismatchIfNeeded(PaymentLink link) {
        long currentAmount = currentAmountKopecks(link);
        if (currentAmount == link.getAmountKopecks()) {
            return false;
        }
        link.setStatus(PaymentLinkStatus.AMOUNT_MISMATCH);
        link.setPaidAt(LocalDateTime.now());
        link.setConfirmedAmountKopecks(link.getAmountKopecks());
        link.setLastError("Платеж пришел по устаревшей сумме: оплачено " + amountRubles(link.getAmountKopecks()).stripTrailingZeros().toPlainString() + " руб., актуально " + amountRubles(currentAmount).stripTrailingZeros().toPlainString() + " руб. Заказ не переведен в оплату.");
        rememberCompanyPayerEmail(link);
        log.warn("Payment amount mismatch: linkId={}, orderId={}, paidAmount={}, currentAmount={}", link.getId(), link.getOrder() == null ? null : link.getOrder().getId(), link.getAmountKopecks(), currentAmount);
        return true;
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = Exception.class)
    void prepareSuccessNotificationRetry(PaymentLink link) {
        if (link != null && link.getPaymentSuccessNotifiedAt() == null) {
            link.setPaymentSuccessNotificationRetryEligible(true);
            paymentSuccessNotificationDeliveryService.deliverAfterCommit(link.getId());
        }
    }

    private boolean hasAnotherConfirmedPayment(PaymentLink link) {
        Order order = link == null ? null : link.getOrder();
        Long orderId = order == null ? null : order.getId();
        if (orderId == null) {
            return false;
        }
        LocalDateTime currentLinkCreatedAt = link.getCreatedAt();
        return paymentLinkRepository.findByOrder_IdAndStatusIn(orderId, Set.of(PaymentLinkStatus.CONFIRMED)).stream().anyMatch(existing -> !sameLinkId(existing, link) && (currentLinkCreatedAt == null || existing.getPaidAt() == null || !existing.getPaidAt().isBefore(currentLinkCreatedAt)));
    }

    private void markDuplicateConfirmedPayment(PaymentLink link) {
        link.setStatus(PaymentLinkStatus.AMOUNT_MISMATCH);
        link.setPaidAt(LocalDateTime.now());
        link.setConfirmedAmountKopecks(link.getAmountKopecks());
        link.setLastError("duplicate_confirmed_payment: по заказу уже есть другой подтвержденный платеж; " + "сумма не зачислена повторно, требуется сверка и при необходимости возврат");
        rememberCompanyPayerEmail(link);
        log.error("Duplicate confirmed payment detected: linkId={}, orderId={}, amount={}", link.getId(), link.getOrder() == null ? null : link.getOrder().getId(), link.getAmountKopecks());
    }

    private boolean sameLinkId(PaymentLink left, PaymentLink right) {
        Long leftId = left == null ? null : left.getId();
        Long rightId = right == null ? null : right.getId();
        return leftId != null && leftId.equals(rightId);
    }

    private long currentAmountKopecks(PaymentLink link) {
        return amountPolicy.currentAmountKopecks(link);
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = Exception.class)
    void markFinalBankStatus(PaymentLink link, PaymentLinkStatus status, String providerTerminalStatus) {
        link.setStatus(status);
        link.setProviderTerminalStatus(normalize(providerTerminalStatus).toUpperCase(Locale.ROOT));
        link.setLastError(null);
        scheduleCommonInvoiceStandalonePaymentReversal(link, status);
    }

    private void scheduleCommonInvoiceStandalonePaymentReversal(PaymentLink link, PaymentLinkStatus terminalStatus) {
        if (terminalStatus != PaymentLinkStatus.REVERSED && terminalStatus != PaymentLinkStatus.PARTIAL_REVERSED && terminalStatus != PaymentLinkStatus.REFUNDED && terminalStatus != PaymentLinkStatus.PARTIAL_REFUNDED) {
            return;
        }
        CommonInvoicePaymentOperations commonBillingService = commonInvoicePayments;
        Order order = link == null ? null : link.getOrder();
        if (link == null || link.getId() == null || order == null || order.getId() == null) {
            return;
        }
        Long orderId = order.getId();
        Long paymentLinkId = link.getId();
        Runnable reconciliation = () -> {
            try {
                commonBillingService.applyStandalonePaymentReversal(orderId, paymentLinkId, terminalStatus);
            } catch (RuntimeException ex) {
                // The durable source link and provider status remain available for
                // the next common-invoice operation to fail closed. Do not roll
                // back an already committed provider webhook acknowledgement.
                log.error("Failed to quarantine common invoice after terminal payment reversal: orderId={}, linkId={}, status={}", orderId, paymentLinkId, terminalStatus, ex);
            }
        };
        if (TransactionSynchronizationManager.isActualTransactionActive() && TransactionSynchronizationManager.isSynchronizationActive()) {
            // Provider reconciliation owns PaymentLink first. Running the common
            // invoice path before commit would invert the global Order ->
            // PaymentLink -> CommonInvoice lock order.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {

                @Override
                public void afterCommit() {
                    reconciliation.run();
                }
            });
            return;
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            log.warn("Skipped immediate common-invoice reversal quarantine because transaction synchronization is unavailable: orderId={}, linkId={}", orderId, paymentLinkId);
            return;
        }
        reconciliation.run();
    }

    boolean isFinalStatus(PaymentLinkStatus status) {
        return status == PaymentLinkStatus.TEST_CONFIRMED || status == PaymentLinkStatus.CONFIRMED || status == PaymentLinkStatus.AMOUNT_MISMATCH || status == PaymentLinkStatus.REJECTED || status == PaymentLinkStatus.CANCELED || status == PaymentLinkStatus.REVERSED || status == PaymentLinkStatus.PARTIAL_REVERSED || status == PaymentLinkStatus.REFUNDED || status == PaymentLinkStatus.PARTIAL_REFUNDED || status == PaymentLinkStatus.EXPIRED;
    }

    private BigDecimal amountRubles(long amountKopecks) {
        return paymentPresenter.amountRubles(amountKopecks);
    }

    private void rememberCompanyPayerEmail(PaymentLink link) {
        Order order = link.getOrder();
        Company company = order == null ? null : order.getCompany();
        String payerEmail = normalizeEmail(link.getPayerEmail());
        if (company == null || payerEmail.isBlank()) {
            return;
        }
        company.setLastPayerEmail(payerEmail);
        company.setLastPayerEmailAt(LocalDateTime.now());
    }

    private String normalizeEmail(String email) {
        return normalize(email).toLowerCase();
    }

    private String normalize(String value) {
        return paymentPresenter.normalize(value);
    }

    private String limit(String value, int maxLength) {
        return commonInvoiceRouteSelector.limit(value, maxLength);
    }
}
