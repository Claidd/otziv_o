package com.hunt.otziv.payments.service;

import static com.hunt.otziv.payments.service.PaymentLinkSettlementService.CONTRACTOR_SOURCE_CONFIRMATION_AUDIT_PREFIX;
import static com.hunt.otziv.payments.service.PaymentLinkSettlementService.PREPAID_WAITING_ORDER_COMPLETION;
import static com.hunt.otziv.payments.service.PaymentLinkCancellationWorkflow.REFUND_OR_REVERSAL_BANK_STATUSES;
import com.hunt.otziv.contractor_payments.service.ContractorActualPaymentAttributionService;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentLiveRoutingService;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentTargetAccessPolicy;
import com.hunt.otziv.manager.service.ManagerAccessService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.payments.dto.AdminPaymentLinkResponse;
import com.hunt.otziv.payments.model.ManualPaymentSource;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.model.PaymentMethod;
import com.hunt.otziv.payments.model.PaymentReceiptStatus;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Applies operator-confirmed transfers to the selected immutable source, checks
 * competing payment evidence, and records manual receipts or an absent transfer.
 * Confirmation locks the order before its sources; ledger application remains
 * in the same transaction through PaymentLinkSettlementService. Receipt-only
 * updates retain their source lock. No provider request is sent.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ManualPaymentConfirmationWorkflow {

    private final PaymentLinkAmountPolicy amountPolicy;

    private final PaymentLinkSettlementService settlementService;

    private final PaymentLinkLifecycleService lifecycleService;

    private final PaymentLinkCancellationWorkflow cancellationWorkflow;

    private final PaymentLinkPresenter paymentPresenter;

    private final CommonInvoiceRouteSelector commonInvoiceRouteSelector;

    static final String MANUAL_UNPAID_CLOSED_AUDIT_PREFIX = "manual_payment_absent_verified";

    static final Set<PaymentLinkStatus> PAID_STATUSES = Set.of(PaymentLinkStatus.AUTHORIZED, PaymentLinkStatus.TEST_CONFIRMED, PaymentLinkStatus.CONFIRMED, PaymentLinkStatus.AMOUNT_MISMATCH);

    private final PaymentLinkRepository paymentLinkRepository;

    private final OrderRepository orderRepository;

    private final ManualPaymentRecipientTelegramNotificationService manualPaymentRecipientTelegramNotificationService;

    private final ManualPaymentTaskService manualPaymentTaskService;

    private final ManualPaymentTaskReceiptIntegrationService taskReceiptIntegrationService;

    private final ManagerAccessService managerAccessService;

    private final ContractorPaymentLiveRoutingService contractorPaymentLiveRoutingService;

    private final ContractorActualPaymentAttributionService actualPaymentAttributionService;

    private final ContractorPaymentTargetAccessPolicy contractorPaymentTargetAccessPolicy;

    private final PaymentLinkTransactionExecutor transactionExecutor;

    private boolean isFrozenContractorRoute(PaymentLink link) {
        return commonInvoiceRouteSelector.isFrozenContractorRoute(link);
    }

    private boolean canRetireStaleLink(PaymentLink link) {
        return lifecycleService.canRetireStaleLink(link);
    }

    private boolean hasStartedBankPayment(PaymentLink link) {
        return lifecycleService.hasStartedBankPayment(link);
    }

    private boolean hasBankInitReservation(PaymentLink link) {
        return lifecycleService.hasBankInitReservation(link);
    }

    private boolean hasOrderBinding(PaymentLink link, Long orderId) {
        return lifecycleService.hasOrderBinding(link, orderId);
    }

    /**
     * A client can report a transfer after an issued manual instruction was
     * retired by TTL or by a later payable correction. Reusing such a row is
     * safe only when it has no payment evidence and its frozen amount is once
     * again exactly the current payable amount. The manager still has to pick
     * the actual recipient before the order is credited.
     */
    boolean isRecoverableExpiredManualRoute(PaymentLink link) {
        return link != null && isManualPayment(link) && link.getStatus() == PaymentLinkStatus.EXPIRED && link.getPaidAt() == null && link.getManualConfirmedAt() == null && normalize(link.getManualConfirmedBy()).isBlank() && (link.getConfirmedAmountKopecks() == null || link.getConfirmedAmountKopecks() <= 0) && link.getReceiptStatus() != PaymentReceiptStatus.MARKED && link.getReceiptStatus() != PaymentReceiptStatus.LEGACY_NOT_REQUIRED && normalize(link.getTbankPaymentId()).isBlank() && !hasBankInitReservation(link) && !hasBankCancelReservation(link) && link.getBankCancelOriginStatus() == null && !isAmountChanged(link);
    }

    private void cancelBadReviewAutoBanAfterCommit(Order order, String reason) {
        settlementService.cancelBadReviewAutoBanAfterCommit(order, reason);
    }

    private boolean hasBankCancelReservation(PaymentLink link) {
        return paymentPresenter.hasBankCancelReservation(link);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AdminPaymentLinkResponse confirmManual(Long linkId, String confirmedBy) {
        contractorPaymentTargetAccessPolicy.requireCanManagePaymentLink(linkId);
        PaymentLink snapshot = paymentLinkRepository.findByIdWithOrder(linkId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежная ссылка не найдена"));
        Long orderId = snapshot.getOrder() == null ? null : snapshot.getOrder().getId();
        transactionExecutor.required(() -> {
            confirmManualLocked(linkId, orderId, confirmedBy);
            return null;
        });
        reconcileContractorPaymentRouteAfterCommit(linkId);
        notifyManualPaymentRecipientAfterCommit(linkId);
        // afterCommit delivery has completed (or durably remained retryable)
        // before the executor returns, so preserve the previous response
        // contract by reading the final notification fields.
        return paymentLinkRepository.findByIdWithOrder(linkId).map(this::toAdminResponse).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежная ссылка не найдена"));
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AdminPaymentLinkResponse confirmContractorPaymentSource(Long linkId, long confirmedTotalKopecks, LocalDateTime effectiveAt, String reason, String confirmedBy) {
        contractorPaymentTargetAccessPolicy.requireCanManagePaymentLink(linkId);
        PaymentLink snapshot = paymentLinkRepository.findByIdWithOrder(linkId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежная ссылка не найдена"));
        Long orderId = snapshot.getOrder() == null ? null : snapshot.getOrder().getId();
        SourceConfirmationResult result = transactionExecutor.required(() -> confirmContractorPaymentSourceLocked(linkId, orderId, confirmedTotalKopecks, effectiveAt, reason, confirmedBy));
        // Each competing source is reconciled by its own immutable id. This
        // prevents a late transfer for A from ever confirming active source B.
        result.sourceIds().forEach(this::reconcileContractorPaymentRouteAfterCommit);
        result.recipientNotificationSourceIds().forEach(this::notifyManualPaymentRecipientAfterCommit);
        return paymentLinkRepository.findByIdWithOrder(linkId).map(this::toAdminResponse).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежная ссылка не найдена"));
    }

    private SourceConfirmationResult confirmContractorPaymentSourceLocked(Long linkId, Long orderId, long confirmedTotalKopecks, LocalDateTime effectiveAt, String reason, String confirmedBy) {
        if (orderId == null || orderRepository.findByIdForCounterUpdate(orderId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ платежной ссылки не найден");
        }
        List<PaymentLink> links = paymentLinkRepository.findByOrderIdForUpdate(orderId);
        PaymentLink source = links.stream().filter(link -> Objects.equals(link.getId(), linkId)).findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежная ссылка не найдена"));
        validateContractorSourceConfirmation(source, confirmedTotalKopecks, effectiveAt, reason);
        long previousTotal = source.getConfirmedAmountKopecks() == null ? 0L : Math.max(0L, source.getConfirmedAmountKopecks());
        if (confirmedTotalKopecks < previousTotal) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Подтвержденная сумма не может уменьшаться; возврат отражается отдельной операцией");
        }
        if (confirmedTotalKopecks == previousTotal) {
            return new SourceConfirmationResult(List.of(source.getId()), List.of());
        }
        List<Long> reconciledSourceIds = new java.util.ArrayList<>();
        for (PaymentLink competing : links) {
            if (Objects.equals(competing.getId(), source.getId()) || isClosedWithoutPaymentSource(competing)) {
                continue;
            }
            if (hasStartedBankPayment(competing) || competing.getStatus() == PaymentLinkStatus.MANUAL_REPORTED || PAID_STATUSES.contains(competing.getStatus()) || competing.getStatus() == PaymentLinkStatus.NEEDS_RECONCILIATION) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "У заказа есть другая ссылка с платежным действием; сначала выполните её сверку");
            }
            if (canRetireStaleLink(competing)) {
                competing.setStatus(PaymentLinkStatus.CANCELED);
                competing.setLastError(limit("Закрыта при подтверждении перевода по источнику " + source.getId(), 512));
                paymentLinkRepository.save(competing);
                contractorPaymentLiveRoutingService.releaseClosedPaymentLink(competing);
                reconciledSourceIds.add(competing.getId());
                continue;
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Другая платежная ссылка заказа имеет неоднозначное состояние");
        }
        LocalDateTime observedAt = effectiveAt == null ? LocalDateTime.now() : effectiveAt;
        source.setConfirmedAmountKopecks(confirmedTotalKopecks);
        source.setPaidAt(observedAt);
        source.setManualConfirmedAt(observedAt);
        source.setManualConfirmedBy(limit(confirmedBy, 160));
        source.setReceiptStatus(PaymentReceiptStatus.PENDING);
        source.setStatus(confirmedTotalKopecks == source.getAmountKopecks() && currentAmountKopecks(source) == source.getAmountKopecks() ? PaymentLinkStatus.CONFIRMED : PaymentLinkStatus.AMOUNT_MISMATCH);
        source.setLastError(limit(CONTRACTOR_SOURCE_CONFIRMATION_AUDIT_PREFIX + "; total=" + confirmedTotalKopecks + "; confirmed_by=" + limit(confirmedBy, 120) + "; reason=" + normalize(reason), 512));
        paymentLinkRepository.save(source);
        reconciledSourceIds.add(source.getId());
        if (source.getStatus() == PaymentLinkStatus.CONFIRMED && canApplyOrderPaymentNow(source.getOrder())) {
            try {
                boolean updated = handlePaymentStatusWithoutPrematureRepeat(source.getOrder(), source.getId());
                if (updated) {
                    cancelBadReviewAutoBanAfterCommit(source.getOrder(), "Перевод подтвержден по конкретному счету");
                }
                syncCommonInvoiceOrderPayment(source, "Перевод подтвержден по конкретному счету");
            } catch (Exception exception) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Перевод сохранен не был: не удалось применить полную оплату заказа", exception);
            }
        } else if (source.getStatus() == PaymentLinkStatus.CONFIRMED) {
            String sourceAudit = normalize(source.getLastError());
            source.setLastError(limit(PREPAID_WAITING_ORDER_COMPLETION + (sourceAudit.startsWith(CONTRACTOR_SOURCE_CONFIRMATION_AUDIT_PREFIX) ? "; " + sourceAudit : ""), 512));
            paymentLinkRepository.save(source);
        }
        List<Long> recipientNotificationSourceIds = source.getStatus() == PaymentLinkStatus.CONFIRMED ? List.of(source.getId()) : List.of();
        return new SourceConfirmationResult(List.copyOf(reconciledSourceIds), recipientNotificationSourceIds);
    }

    private void validateContractorSourceConfirmation(PaymentLink source, long confirmedTotalKopecks, LocalDateTime effectiveAt, String reason) {
        if (!isFrozenContractorRoute(source) || source.getManualSource() != ManualPaymentSource.CONTRACTOR_PAYMENT_PROFILE || source.getPaymentMethod() != PaymentMethod.MANUAL_MOBILE_BANK) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Операция доступна только для зафиксированного платежного профиля специалиста или менеджера");
        }
        if (confirmedTotalKopecks <= 0 || confirmedTotalKopecks > source.getAmountKopecks()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Подтвержденная сумма должна быть в пределах счета");
        }
        if (effectiveAt != null && effectiveAt.isAfter(LocalDateTime.now().plusMinutes(1))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Время поступления не может быть в будущем");
        }
        if (normalize(reason).isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Укажите основание сверки");
        }
        if (REFUND_OR_REVERSAL_BANK_STATUSES.contains(source.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "По источнику уже отражен возврат");
        }
    }

    private boolean isClosedWithoutPaymentSource(PaymentLink link) {
        return link != null && (link.getStatus() == PaymentLinkStatus.CANCELED || link.getStatus() == PaymentLinkStatus.EXPIRED || link.getStatus() == PaymentLinkStatus.FAILED || link.getStatus() == PaymentLinkStatus.REJECTED);
    }

    /**
     * Retires only the selected manual payment instruction after an operator
     * has checked the recipient statement and explicitly asserted that the
     * transfer is absent. This operation deliberately does not mutate the
     * order status and does not apply any payment to a common invoice.
     */
    @Transactional
    public AdminPaymentLinkResponse closeManualAsUnpaid(Long linkId, boolean recipientStatementChecked, boolean paymentAbsent, String note, String actor, Authentication authentication) {
        contractorPaymentTargetAccessPolicy.requireCanManagePaymentLink(linkId);
        String cleanNote = normalize(note);
        String cleanActor = normalize(actor);
        if (!recipientStatementChecked || !paymentAbsent || cleanNote.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Подтвердите проверку выписки получателя, отсутствие перевода и укажите обязательную заметку");
        }
        PaymentLink snapshot = paymentLinkRepository.findByIdWithOrder(linkId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежная ссылка не найдена"));
        Long orderId = snapshot.getOrder() == null ? null : snapshot.getOrder().getId();
        if (orderId == null || orderRepository.findByIdForCounterUpdate(orderId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ платежной ссылки не найден");
        }
        managerAccessService.requireOrderAccess(orderId, authentication);
        PaymentLink link = paymentLinkRepository.findByIdForUpdate(linkId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежная ссылка не найдена"));
        if (!hasOrderBinding(link, orderId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Заказ платежной ссылки изменился");
        }
        ensureManualPayment(link);
        validateManualUnpaidClosable(link);
        taskReceiptIntegrationService.release(link, "Перевод не поступил");
        link.setStatus(PaymentLinkStatus.CANCELED);
        link.setLastError(limit(MANUAL_UNPAID_CLOSED_AUDIT_PREFIX + ": перевод не поступил; checked_by=" + limit(cleanActor.isBlank() ? "admin" : cleanActor, 160) + "; note=" + cleanNote, 512));
        paymentLinkRepository.save(link);
        reconcileContractorPaymentRouteAfterCommit(link.getId());
        log.warn("Manual payment instruction closed after recipient statement verification: linkId={}, orderId={}, actor={}", linkId, orderId, cleanActor);
        return toAdminResponse(link);
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

    private void confirmManualLocked(Long linkId, Long orderId, String confirmedBy) {
        if (orderId == null || orderRepository.findByIdForCounterUpdate(orderId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ платежной ссылки не найден");
        }
        PaymentLink link = paymentLinkRepository.findByIdForUpdate(linkId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежная ссылка не найдена"));
        if (!hasOrderBinding(link, orderId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Заказ платежной ссылки изменился");
        }
        ensureManualPayment(link);
        if (link.getManualSource() == ManualPaymentSource.CONTRACTOR_PAYMENT_PROFILE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Поступление по профилю получателя подтверждается только сверкой конкретного счета");
        }
        if (link.getManualSource() == ManualPaymentSource.MANUAL_TASK || actualPaymentAttributionService.actualRecipientAccountingEnabled()) {
            throw ManualPaymentTaskRouteErrors.actualRecipientRequired();
        }
        validateManualConfirmable(link);
        validateAmountCurrentForManualConfirm(link);
        try {
            if (!canApplyOrderPaymentNow(link.getOrder())) {
                markOrderPrepaid(link);
                prepareSuccessNotificationRetry(link);
                paymentLinkRepository.save(link);
                manualPaymentTaskService.completeIfConfirmedTargetReached(link.getManualPaymentTask());
                return;
            }
            boolean updated = handlePaymentStatusWithoutPrematureRepeat(link.getOrder(), link.getId());
            LocalDateTime now = LocalDateTime.now();
            link.setStatus(PaymentLinkStatus.CONFIRMED);
            link.setPaidAt(now);
            link.setManualConfirmedAt(now);
            link.setManualConfirmedBy(limit(confirmedBy, 160));
            link.setConfirmedAmountKopecks(link.getAmountKopecks());
            link.setReceiptStatus(PaymentReceiptStatus.PENDING);
            link.setLastError(null);
            prepareSuccessNotificationRetry(link);
            paymentLinkRepository.save(link);
            manualPaymentTaskService.completeIfConfirmedTargetReached(link.getManualPaymentTask());
            if (updated) {
                cancelBadReviewAutoBanAfterCommit(link.getOrder(), "Ручная оплата подтверждена");
            }
            syncCommonInvoiceOrderPayment(link, "Ручная оплата заказа");
        } catch (Exception e) {
            link.setStatus(PaymentLinkStatus.FAILED);
            link.setLastError("Manual payment transition failed");
            paymentLinkRepository.save(link);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Не удалось подтвердить ручную оплату", e);
        }
    }

    @Transactional
    public AdminPaymentLinkResponse markManualReceipt(Long linkId, String confirmedBy) {
        contractorPaymentTargetAccessPolicy.requireCanManagePaymentLink(linkId);
        PaymentLink link = findLinkByIdForUpdate(linkId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежная ссылка не найдена"));
        ensureManualPayment(link);
        if (link.getStatus() != PaymentLinkStatus.CONFIRMED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Сначала подтвердите ручную оплату");
        }
        if (normalize(link.getManualConfirmedBy()).isBlank()) {
            link.setManualConfirmedBy(limit(confirmedBy, 160));
        }
        link.setReceiptStatus(PaymentReceiptStatus.MARKED);
        link.setLastError(null);
        paymentLinkRepository.save(link);
        return toAdminResponse(link);
    }

    @Transactional
    public AdminPaymentLinkResponse markManualReceiptLegacyNotRequired(Long linkId, String confirmedBy) {
        contractorPaymentTargetAccessPolicy.requireCanManagePaymentLink(linkId);
        PaymentLink link = findLinkByIdForUpdate(linkId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежная ссылка не найдена"));
        ensureManualPayment(link);
        if (link.getStatus() != PaymentLinkStatus.CONFIRMED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Статус без чека доступен только для подтвержденной ручной оплаты");
        }
        if (link.getPaidAt() == null || link.getPaidAt().isAfter(LocalDateTime.now().minusDays(30))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Без чека можно закрыть только старую ручную оплату старше 30 дней");
        }
        link.setReceiptStatus(PaymentReceiptStatus.LEGACY_NOT_REQUIRED);
        if (normalize(link.getManualConfirmedBy()).isBlank()) {
            link.setManualConfirmedBy(limit(confirmedBy, 160));
        }
        link.setLastError(null);
        paymentLinkRepository.save(link);
        return toAdminResponse(link);
    }

    private boolean canApplyOrderPaymentNow(Order order) {
        return settlementService.canApplyOrderPaymentNow(order);
    }

    private boolean handlePaymentStatusWithoutPrematureRepeat(Order order, Long paymentLinkId) throws Exception {
        return settlementService.handlePaymentStatusWithoutPrematureRepeat(order, paymentLinkId);
    }

    private void syncCommonInvoiceOrderPayment(PaymentLink link, String reason) {
        settlementService.syncCommonInvoiceOrderPayment(link, reason);
    }

    private void markOrderPrepaid(PaymentLink link) {
        settlementService.markOrderPrepaid(link);
    }

    private void prepareSuccessNotificationRetry(PaymentLink link) {
        settlementService.prepareSuccessNotificationRetry(link);
    }

    private void notifyManualPaymentRecipientAfterCommit(PaymentLink link) {
        manualPaymentRecipientTelegramNotificationService.notifyAfterCommit(link);
    }

    private void notifyManualPaymentRecipientAfterCommit(Long paymentLinkId) {
        manualPaymentRecipientTelegramNotificationService.notifyAfterCommit(paymentLinkId);
    }

    private Optional<PaymentLink> findLinkByIdForUpdate(Long linkId) {
        return paymentLinkRepository.findByIdForUpdate(linkId).or(() -> paymentLinkRepository.findByIdWithOrder(linkId));
    }

    private boolean expireIfAmountChanged(PaymentLink link) {
        return lifecycleService.expireIfAmountChanged(link);
    }

    private boolean isAmountChanged(PaymentLink link) {
        return lifecycleService.isAmountChanged(link);
    }

    private long currentAmountKopecks(PaymentLink link) {
        return amountPolicy.currentAmountKopecks(link);
    }

    void ensureManualPayment(PaymentLink link) {
        if (!isManualPayment(link)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Это не ручной платеж");
        }
    }

    void validateManualConfirmable(PaymentLink link) {
        if (link.getStatus() == PaymentLinkStatus.CONFIRMED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ручная оплата уже подтверждена");
        }
        if (link.getStatus() != PaymentLinkStatus.WAITING_MANUAL_PAYMENT && link.getStatus() != PaymentLinkStatus.MANUAL_REPORTED && !isRecoverableExpiredManualRoute(link)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ручная оплата недоступна для подтверждения");
        }
    }

    private void validateManualUnpaidClosable(PaymentLink link) {
        if (link.getStatus() != PaymentLinkStatus.WAITING_MANUAL_PAYMENT && link.getStatus() != PaymentLinkStatus.MANUAL_REPORTED && link.getStatus() != PaymentLinkStatus.EXPIRED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "С результатом «перевод не поступил» можно закрыть только ожидающую или истекшую ручную инструкцию");
        }
        boolean hasPaidEvidence = link.getPaidAt() != null || link.getManualConfirmedAt() != null || !normalize(link.getManualConfirmedBy()).isBlank() || (link.getConfirmedAmountKopecks() != null && link.getConfirmedAmountKopecks() > 0) || link.getReceiptStatus() == PaymentReceiptStatus.MARKED || link.getReceiptStatus() == PaymentReceiptStatus.LEGACY_NOT_REQUIRED || !normalize(link.getTbankPaymentId()).isBlank();
        if (hasPaidEvidence) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "У инструкции уже есть признаки оплаты или банковского платежа; требуется отдельная сверка");
        }
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = Exception.class)
    void validateAmountCurrentForManualConfirm(PaymentLink link) {
        if (expireIfAmountChanged(link) || isAmountChanged(link)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Сумма ручной оплаты изменилась. Создайте новый счет и сверяйте оплату вручную.");
        }
    }

    private AdminPaymentLinkResponse toAdminResponse(PaymentLink link) {
        return paymentPresenter.toAdminResponse(link);
    }

    private boolean isManualPayment(PaymentLink link) {
        return paymentPresenter.isManualPayment(link);
    }

    private String normalize(String value) {
        return paymentPresenter.normalize(value);
    }

    private String limit(String value, int maxLength) {
        return commonInvoiceRouteSelector.limit(value, maxLength);
    }

    private record SourceConfirmationResult(List<Long> sourceIds, List<Long> recipientNotificationSourceIds) {
    }
}
