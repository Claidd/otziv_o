package com.hunt.otziv.common_billing.service;

import static com.hunt.otziv.common_billing.service.CommonInvoiceReviewApprovalWorkflow.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceArchiveWorkflow.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceBoardWorkflow.*;
import static com.hunt.otziv.common_billing.service.CommonBillingAccountWorkflow.*;
import static com.hunt.otziv.common_billing.service.CommonBillingCompanyReconciliationWorkflow.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceMembershipWorkflow.*;
import static com.hunt.otziv.common_billing.service.CommonInvoicePaymentRouteWorkflow.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceManualPaymentWorkflow.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceTochkaReconciliationService.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceDeliveryService.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceDetailsAssembler.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceInitializationService.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceCancellationService.*;
import static com.hunt.otziv.common_billing.service.CommonInvoicePresenter.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceSettlementService.*;
import com.hunt.otziv.common_billing.dto.CommonInvoiceDetailsResponse;
import com.hunt.otziv.common_billing.dto.CommonInvoicePaymentInitCheckRequest;
import com.hunt.otziv.common_billing.dto.ManualPaymentConfirmationRequest;
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceOrder;
import com.hunt.otziv.common_billing.model.CommonInvoicePaymentRef;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonInvoiceOrderRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoicePaymentRefRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentAllocation;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentLiveRoutingService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.service.StandaloneBankPaymentPolicy;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Principal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.CaughtFailureStage.CLOSE_ORDER;
import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.CaughtFailureStage.OPEN_NEXT_ORDER;
import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.TransactionFlow.COMMON_INVOICE_CLOSE;

/** Owns operator recovery decisions and their evidence fences; uncertainty is never treated as unpaid proof. */
@Service
@Slf4j
@RequiredArgsConstructor
public class CommonInvoiceRecoveryWorkflow {

    private final CommonInvoiceReviewApprovalWorkflow invoiceReviewApprovalWorkflow;

    private final CommonInvoiceMembershipWorkflow invoiceMembershipWorkflow;

    private final CommonInvoiceManualPaymentWorkflow manualPaymentWorkflow;

    private final CommonInvoiceDeliveryService invoiceDelivery;

    private final CommonInvoiceDetailsAssembler invoiceDetailsAssembler;

    private final CommonInvoiceInitializationService invoiceInitialization;

    private final CommonInvoiceSettlementService settlementService;

    static final Set<String> PAYMENT_INIT_TLS_RECOVERY_ALLOWED_REF_STATUSES = Set.of(PAYMENT_REF_INIT_PREPARED, PAYMENT_REF_INIT_CONFLICT, PAYMENT_REF_CANCELED, "REJECTED", "EXPIRED", "REFUNDED", "REVERSED");

    static final Set<String> PAYMENT_INIT_MANUAL_BLOCKING_REF_STATUSES = Set.of(PAYMENT_REF_CURRENT, PAYMENT_REF_CANCEL_PENDING, PAYMENT_REF_CANCELING, PAYMENT_REF_CANCEL_FAILED, PAYMENT_REF_CANCEL_FAILED_FINAL, PAYMENT_REF_CONFIRMED, PAYMENT_REF_PREPAID, PAYMENT_REF_APPLYING, PAYMENT_REF_APPLIED);

    static final String PAYMENT_INIT_TLS_SAFE_ARCHIVED_REASON_PREFIX = "payment_init_tls_failed_before_http_request";

    static final String PAYMENT_INIT_MANUALLY_CHECKED_REASON = "payment_init_manually_checked";

    static final String PAYMENT_INIT_MANUALLY_CHECKED_BY_PREFIX = PAYMENT_INIT_MANUALLY_CHECKED_REASON + "_by=";

    static final String PAYMENT_CANCEL_MANUALLY_CHECKED_BY_PREFIX = "payment_cancel_manually_checked_by=";

    static final Set<String> RESOLVABLE_TECHNICAL_TAIL_ERROR_PREFIXES = Set.of("disabled:", "empty:", "merged_into:", "manual_fix:");

    private final EntityManager entityManager;

    private final CommonInvoiceRepository invoiceRepository;

    private final CommonInvoiceOrderRepository invoiceOrderRepository;

    private final CommonInvoicePaymentRefRepository paymentRefRepository;

    private final com.hunt.otziv.payments.api.StandalonePaymentOperations standalonePaymentOperations;

    private final ContractorPaymentLiveRoutingService contractorPaymentLiveRoutingService;

    public CommonInvoiceDetailsResponse sendInvoice(Long invoiceId, boolean manual) {
        return invoiceDelivery.sendInvoice(invoiceId, manual);
    }

    CommonInvoiceDetailsResponse sendInvoice(Long invoiceId, boolean manual, boolean paymentRouteChanged) {
        return invoiceDelivery.sendInvoice(invoiceId, manual, paymentRouteChanged);
    }

    CommonInvoiceDetailsResponse invoiceAfterOrderPrelude(Long invoiceId) {
        return manualPaymentWorkflow.invoiceAfterOrderPrelude(invoiceId);
    }

    public CommonInvoiceDetailsResponse approveReviewOrders(Long invoiceId) {
        return invoiceReviewApprovalWorkflow.approveReviewOrders(invoiceId);
    }

    CommonInvoiceDetailsResponse approveReviewOrders(Long invoiceId, List<CommonInvoiceOrder> items) {
        return invoiceReviewApprovalWorkflow.approveReviewOrders(invoiceId, items);
    }

    @Transactional
    public CommonInvoiceDetailsResponse retryAttention(Long invoiceId) {
        CommonInvoice invoice = lockedInvoice(invoiceId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        ensureCommonInvoiceNeedsAttention(invoice);
        if (attentionError(invoice).startsWith(CommonBillingPublicationApprovalFailureMarker.ERROR_PREFIX)) {
            List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
            resolveAttentionByCurrentItems(invoice, items);
            return approveReviewOrders(invoiceId, items);
        }
        ensureAttentionCanBeRetried(invoice);
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        closePaidInvoice(invoice, items);
        return invoiceAfterOrderPrelude(invoiceId);
    }

    @Transactional
    public CommonInvoiceDetailsResponse resolveAttention(Long invoiceId) {
        CommonInvoice invoice = lockedInvoice(invoiceId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        ensureCommonInvoiceNeedsAttention(invoice);
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        ensureNoUnresolvedPaymentRefsForGenericAttentionResolution(invoice);
        ensureAttentionCanBeResolved(invoice, items);
        resolveAttentionByCurrentItems(invoice, items);
        return invoiceAfterOrderPrelude(invoiceId);
    }

    @Transactional
    public CommonInvoiceDetailsResponse confirmFinalPaymentCancelCheck(Long invoiceId) {
        CommonInvoice invoice = lockedInvoice(invoiceId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        ensureCommonInvoiceNeedsAttention(invoice);
        List<CommonInvoicePaymentRef> refs = paymentRefRepository.findByInvoiceIdForUpdate(invoiceId);
        List<CommonInvoicePaymentRef> finalRefs = refs.stream().filter(ref -> PAYMENT_REF_CANCEL_FAILED_FINAL.equals(paymentRefStatus(ref))).toList();
        if (!attentionError(invoice).startsWith(PAYMENT_CANCEL_FAILED_FINAL) && finalRefs.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "У общего счета нет финальной ошибки банковской платежной ссылки");
        }
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        ensureNoRecordedFullPaymentWithOpenItems(invoice, items);
        for (CommonInvoicePaymentRef ref : finalRefs) {
            ref.setStatus(PAYMENT_REF_ARCHIVED);
            ref.setReason(manualPaymentCancelCheckAuditReason(ref.getReason()));
        }
        if (!finalRefs.isEmpty()) {
            paymentRefRepository.saveAll(finalRefs);
            Authentication authentication = currentAuthentication();
            log.warn("Common invoice {} final provider refs manually checked by {}: refs={}", invoice.getId(), authentication == null ? "unknown" : normalize(authentication.getName()), finalRefs.stream().map(CommonInvoicePaymentRef::getId).toList());
        }
        List<CommonInvoicePaymentRef> stillUnresolved = refs.stream().filter(ref -> PAYMENT_INIT_NEW_ATTEMPT_BLOCKING_REF_STATUSES.contains(paymentRefStatus(ref))).toList();
        if (!stillUnresolved.isEmpty()) {
            invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
            invoice.setNextReminderAt(null);
            invoice.setLastError(limit(PAYMENT_CANCEL_FAILED_FINAL + ": после ручной проверки остались незавершенные банковские операции", 512));
            invoiceRepository.save(invoice);
            return invoiceAfterOrderPrelude(invoiceId);
        }
        resolveAttentionByCurrentItems(invoice, items);
        return invoiceAfterOrderPrelude(invoiceId);
    }

    @Transactional
    public CommonInvoiceDetailsResponse confirmPaymentInitCheck(Long invoiceId) {
        return confirmPaymentInitCheck(invoiceId, null);
    }

    @Transactional
    public CommonInvoiceDetailsResponse confirmPaymentInitCheck(Long invoiceId, CommonInvoicePaymentInitCheckRequest request) {
        LockedInvoicePaymentPrelude paymentPrelude = lockedInvoiceAfterStandalonePaymentPrelude(invoiceId);
        CommonInvoice invoice = paymentPrelude.invoice();
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        ensureCommonInvoiceNeedsAttention(invoice);
        if (!isPaymentInitManualCheckAttention(invoice)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "У общего счета нет ручной проверки создания T-Bank ссылки");
        }
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        ensureNoRecordedFullPaymentWithOpenItems(invoice, items);
        List<CommonInvoicePaymentRef> refs = paymentRefRepository.findByInvoiceIdForUpdate(invoice.getId());
        ensurePaymentEvidenceSnapshotMatches(invoice, refs, request);
        if (isMigrationPaymentRegistryAttention(invoice)) {
            ensureMigrationPaymentRegistryCanBeManuallyResolved(invoice, refs, paymentPrelude.paymentLinksByOrder());
        }
        resolvePreparedPaymentInitAfterManualCheck(invoice);
        resolveAttentionByCurrentItems(invoice, items);
        return invoiceAfterOrderPrelude(invoiceId);
    }

    /**
     * Confirms bank-statement evidence against the immutable common-invoice
     * source selected for one contractor recipient. This endpoint deliberately
     * accepts a cumulative total, so retries and partial transfers are
     * idempotent and never get attributed to a newer successor source.
     */
    @Transactional
    public CommonInvoiceDetailsResponse confirmContractorPaymentSource(Long invoiceId, long confirmedTotalKopecks, LocalDateTime effectiveAt, String reason, Principal principal) {
        LockedInvoicePaymentPrelude paymentPrelude = lockedInvoiceAfterStandalonePaymentPrelude(invoiceId);
        CommonInvoice invoice = paymentPrelude.invoice();
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        validateContractorCommonSourceConfirmation(invoice, confirmedTotalKopecks, effectiveAt, reason);
        ContractorPaymentAllocation sourceAllocation = contractorPaymentLiveRoutingService.validatedCommonConfirmationSource(invoiceId, invoice.getContractorAllocationId());
        if (confirmedTotalKopecks > sourceAllocation.getAmountKopecks()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Подтвержденная сумма превышает сумму источника");
        }
        retireUnstartedSuccessorForLateSourceOrThrow(invoice);
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        if (items.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "В общем счете нет позиций для сверки");
        }
        long sourcePaidBaselineKopecks = Math.max(0L, sourceAllocation.getSourcePaidBaselineKopecks());
        long previousSourceTotal = Math.max(0L, invoice.getPaidKopecks() - sourcePaidBaselineKopecks);
        if (confirmedTotalKopecks < previousSourceTotal) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Подтвержденная сумма не может уменьшаться; возврат отражается отдельной операцией");
        }
        if (confirmedTotalKopecks == previousSourceTotal && hasExactContractorSourceEvidence(invoice)) {
            return invoiceAfterOrderPrelude(invoiceId);
        }
        long invoiceConfirmedTotal;
        try {
            invoiceConfirmedTotal = Math.addExact(sourcePaidBaselineKopecks, confirmedTotalKopecks);
        } catch (ArithmeticException overflow) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Подтвержденная сумма некорректна");
        }
        if (invoiceConfirmedTotal > invoice.getAmountKopecks()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Подтвержденная сумма превышает сумму счета");
        }
        LocalDateTime observedAt = effectiveAt == null ? LocalDateTime.now() : effectiveAt;
        String actor = principal == null ? "" : normalize(principal.getName());
        String auditReason = CONTRACTOR_COMMON_SOURCE_CONFIRMATION_AUDIT_PREFIX + " source_total=" + confirmedTotalKopecks + "; оператор=" + limit(actor, 120) + "; основание=" + normalize(reason);
        mergeInvoicePaymentMethod(invoice, PAYMENT_METHOD_MANUAL);
        invoice.setManualPaidBy(actor);
        invoice.setManualPaymentComment(limit(auditReason, 1000));
        invoice.setManualConfirmedAt(observedAt);
        invoice.setPaidAt(observedAt);
        invoice.setPaidKopecks(invoiceConfirmedTotal);
        if (invoiceConfirmedTotal < invoice.getAmountKopecks()) {
            invoice.setStatus(CommonInvoiceStatus.PARTIALLY_PAID);
            invoice.setNextReminderAt(nextAutomaticPaymentReminderAt(LocalDateTime.now()));
            invoice.setLastError(null);
            invoiceRepository.save(invoice);
            scheduleContractorShadowReconcile(invoiceId);
            return invoiceAfterOrderPrelude(invoiceId);
        }
        ManualPaymentConfirmationRequest evidence = new ManualPaymentConfirmationRequest(auditReason, "");
        applyManualPaymentEvidence(invoice, items, evidence, principal);
        invoice.setManualPaymentComment(limit(auditReason, 1000));
        invoiceRepository.save(invoice);
        closePaidInvoice(invoice, items);
        return invoiceAfterOrderPrelude(invoiceId);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CommonInvoiceDetailsResponse repairStandalonePaymentRouteConflict(Long invoiceId) {
        CommonInvoice snapshot = invoiceRepository.findByIdWithAccount(invoiceId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        ensureCommonInvoiceVisibleForCurrentUser(snapshot);
        if (!attentionError(snapshot).startsWith(STANDALONE_PAYMENT_ROUTE_CONFLICT)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ошибка отдельного платежного маршрута уже изменилась; обновите общий счет");
        }
        List<Long> orderIds = invoiceOrderRepository.findOrderIdsByInvoiceId(invoiceId).stream().filter(Objects::nonNull).distinct().toList();
        for (Long orderId : orderIds) {
            standalonePaymentOperations.reconcileActiveOrder(orderId);
        }
        CommonInvoiceDetailsResponse repaired = writeTransaction(() -> repairStandalonePaymentRouteConflictLocked(invoiceId));
        if (repaired != null && repaired.summary() != null && (CommonInvoiceStatus.READY.name().equals(repaired.summary().status()) || CommonInvoiceStatus.PARTIALLY_PAID.name().equals(repaired.summary().status()))) {
            return sendInvoice(invoiceId, true);
        }
        return repaired;
    }

    /**
     * Retires an empty collecting shell only when it has no positions and no
     * payment history whatsoever. This recovers an invoice left behind after
     * all auto-created next orders were safely deleted.
     */
    @Transactional
    public CommonInvoiceDetailsResponse disableEmptyInvoice(Long invoiceId) {
        CommonInvoice invoice = lockedInvoice(invoiceId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        if (!items.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Общий счет уже содержит заказы; обновите карточку контроля");
        }
        if (invoice.getStatus() != CommonInvoiceStatus.COLLECTING && invoice.getStatus() != CommonInvoiceStatus.READY) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Автозакрытие доступно только для пустого счета в сборе");
        }
        boolean hasPaymentEvidence = invoice.getAmountKopecks() != 0 || invoice.getPaidKopecks() != 0 || invoice.getSentAt() != null || invoice.getPaidAt() != null || invoice.getClosedAt() != null || invoice.getClientReportedAt() != null || invoice.getManualConfirmedAt() != null || !normalize(invoice.getManualPaidBy()).isBlank() || !normalize(invoice.getManualPaymentComment()).isBlank() || !normalize(invoice.getManualPaymentReceiptUrl()).isBlank() || !attentionError(invoice).isBlank() || !normalize(invoice.getPaymentSuccessNotificationError()).isBlank() || hasCurrentCommonPaymentRoute(invoice) || hasFrozenCommonPaymentRoute(invoice);
        if (hasPaymentEvidence || !paymentRefRepository.findByInvoiceIdForUpdate(invoiceId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Пустой счет содержит платежные признаки и требует ручной сверки");
        }
        invoice.setStatus(CommonInvoiceStatus.DISABLED);
        invoice.setAmountKopecks(0);
        invoice.setPaidKopecks(0);
        invoice.setNextReminderAt(null);
        invoice.setLastError("empty: в общем счете нет заказов");
        invoiceRepository.save(invoice);
        return invoiceDetails(invoice, List.of());
    }

    CommonInvoiceDetailsResponse repairStandalonePaymentRouteConflictLocked(Long invoiceId) {
        LockedInvoicePaymentPrelude paymentPrelude = lockedInvoiceAfterStandalonePaymentPrelude(invoiceId);
        CommonInvoice invoice = paymentPrelude.invoice();
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        ensureCommonInvoiceNeedsAttention(invoice);
        if (!attentionError(invoice).startsWith(STANDALONE_PAYMENT_ROUTE_CONFLICT)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ошибка отдельного платежного маршрута уже изменилась; обновите общий счет");
        }
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        Set<PaymentLink> appliedStandalonePayments = synchronizeConfirmedStandalonePaymentsOrThrow(invoice, items, paymentPrelude.paymentLinksByOrder());
        closeProvablyUnstartedStandaloneRoutesOrThrow(paymentLinksRequiringCommonInvoiceRouteCheck(paymentPrelude.paymentLinksByOrder(), items, appliedStandalonePayments), invoiceId);
        resolveAttentionByCurrentItems(invoice, items);
        return invoiceAfterOrderPrelude(invoiceId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CommonInvoiceDetailsResponse recoverUnsentPaymentInitTlsFailure(Long invoiceId) {
        LockedInvoicePaymentPrelude paymentPrelude = lockedInvoiceAfterStandalonePaymentPrelude(invoiceId);
        CommonInvoice invoice = paymentPrelude.invoice();
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        ensureCommonInvoiceNeedsAttention(invoice);
        if (!isDefinitelyUnsentPaymentInitTlsFailure(invoice)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Автопочинка доступна только для TLS-сбоя, который произошел до отправки запроса в T-Bank");
        }
        ensureInvoiceHasNoCurrentProviderEvidence(invoice);
        List<CommonInvoicePaymentRef> refs = paymentRefRepository.findByInvoiceIdForUpdate(invoiceId);
        ensureUnsentTlsPaymentRefsCanBeRecovered(invoice, refs);
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        ensureNoCompetingStandalonePaymentLinks(paymentPrelude.paymentLinksByOrder());
        List<CommonInvoicePaymentRef> recoverableRefs = refs.stream().filter(this::isPreparedPaymentRef).toList();
        recoverableRefs.forEach(ref -> {
            String previousReason = normalize(ref.getReason());
            ref.setStatus(PAYMENT_REF_ARCHIVED);
            ref.setReason(limit(PAYMENT_INIT_TLS_SAFE_ARCHIVED_REASON_PREFIX + (previousReason.isBlank() ? "" : "; previous=" + previousReason), 160));
        });
        paymentRefRepository.saveAll(recoverableRefs);
        resolveAttentionByCurrentItems(invoice, items);
        return invoiceAfterOrderPrelude(invoiceId);
    }

    @Transactional
    public CommonInvoiceDetailsResponse resolveTechnicalTail(Long invoiceId) {
        CommonInvoice invoice = lockedInvoice(invoiceId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        ensureCommonInvoiceTechnicalTailCanBeResolved(invoice, items);
        invoice.setLastError(null);
        invoice.setNextReminderAt(null);
        invoiceRepository.save(invoice);
        return invoiceAfterOrderPrelude(invoiceId);
    }

    @Transactional
    public void resolveWhatsappGroupTail(Long invoiceId) {
        CommonInvoice invoice = lockedInvoice(invoiceId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        String error = attentionError(invoice);
        if (!(error.startsWith("whatsapp_group_missing") || error.contains("whatsapp-групп"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ошибка WhatsApp общего счета уже изменилась; обновите карточку контроля");
        }
        invoice.setLastError(null);
        invoiceRepository.save(invoice);
    }

    @Transactional
    public CommonInvoiceDetailsResponse resolvePaymentSuccessNotification(Long invoiceId) {
        CommonInvoice invoice = lockedInvoice(invoiceId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        if (normalize(invoice.getPaymentSuccessNotificationError()).isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "У общего счета нет ошибки уведомления об оплате");
        }
        invoice.setPaymentSuccessNotificationError(null);
        if (invoice.getPaymentSuccessNotifiedAt() == null) {
            invoice.setPaymentSuccessNotifiedAt(LocalDateTime.now());
        }
        invoiceRepository.save(invoice);
        return invoiceAfterOrderPrelude(invoiceId);
    }

    void resolveAttentionByCurrentItems(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        recalculateInvoice(invoice, items);
        if (items.isEmpty()) {
            invoice.setStatus(CommonInvoiceStatus.DISABLED);
            invoice.setNextReminderAt(null);
            invoice.setLastError("empty: в общем счете нет заказов");
            invoiceRepository.save(invoice);
            return;
        }
        boolean allPaid = !items.isEmpty() && items.stream().allMatch(CommonInvoiceOrder::isPaid);
        if (allPaid || remainingKopecks(invoice) <= 0) {
            invoice.setPaidKopecks(invoice.getAmountKopecks());
            if (invoice.getPaidAt() == null) {
                invoice.setPaidAt(LocalDateTime.now());
            }
            markInvoicePaidClosed(invoice);
        } else if (invoice.getPaidKopecks() > 0) {
            invoice.setStatus(CommonInvoiceStatus.PARTIALLY_PAID);
        } else if (isInvoiceReady(invoice.getId())) {
            invoice.setStatus(CommonInvoiceStatus.READY);
            markInvoiceOrdersPublished(items);
        } else {
            invoice.setStatus(CommonInvoiceStatus.COLLECTING);
        }
        invoice.setLastError(null);
        invoiceRepository.save(invoice);
    }

    @Transactional
    public CommonInvoiceDetailsResponse applyLatePayment(Long invoiceId) {
        LockedInvoicePaymentPrelude paymentPrelude = lockedInvoiceAfterStandalonePaymentPrelude(invoiceId);
        CommonInvoice invoice = paymentPrelude.invoice();
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        ensureCommonInvoiceNeedsAttention(invoice);
        if (!isLatePaymentAttention(invoice)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "У общего счета нет позднего банковского платежа для распределения");
        }
        List<CommonInvoicePaymentRef> refs = paymentRefRepository.findByInvoiceIdAndStatusForUpdate(invoiceId, PAYMENT_REF_CONFIRMED);
        long availableKopecks = refs.stream().map(CommonInvoicePaymentRef::getAmountKopecks).filter(amount -> amount != null && amount > 0).mapToLong(Long::longValue).sum();
        if (availableKopecks <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Не найдена подтвержденная сумма старой банковской ссылки");
        }
        String latePaymentMethod = latePaymentMethod(refs);
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        refreshInvoiceAmounts(invoice, items);
        List<CommonInvoiceOrder> sortedItems = items.stream().filter(item -> !item.isPaid()).sorted(Comparator.comparing(item -> {
            Order order = item.getOrder();
            return order == null || order.getId() == null ? Long.MAX_VALUE : order.getId();
        })).toList();
        long plannedRemainderKopecks = availableKopecks;
        List<CommonInvoiceOrder> plannedItems = new ArrayList<>();
        for (CommonInvoiceOrder item : sortedItems) {
            long itemAmount = Math.max(0, item.getAmountKopecks());
            if (itemAmount <= 0 || itemAmount > plannedRemainderKopecks) {
                break;
            }
            plannedItems.add(item);
            plannedRemainderKopecks -= itemAmount;
            if (plannedRemainderKopecks == 0) {
                break;
            }
        }
        if (plannedRemainderKopecks != 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Сумма позднего банковского платежа не распределяется по неоплаченным заказам целиком. " + "Ничего не изменено; нужна ручная сверка");
        }
        // From this point every mutation is one atomic transaction. A close failure is allowed to
        // escape so Spring rolls back both order state and the CONFIRMED -> APPLYING ref transition;
        // retaining a partially consumed CONFIRMED amount would make a repeated call double-spend it.
        setPaymentRefsStatus(refs, PAYMENT_REF_APPLYING);
        Set<Long> closedOrderIds = new HashSet<>();
        for (CommonInvoiceOrder item : plannedItems) {
            try {
                closeOrderAsPaidWithoutNextOrder(item.getOrder());
                if (item.getOrder() != null && item.getOrder().getId() != null) {
                    closedOrderIds.add(item.getOrder().getId());
                }
                item.setPaid(true);
                item.setUnpaid(false);
                item.setPaidAt(LocalDateTime.now());
                item.setPaymentMethod(latePaymentMethod);
            } catch (Exception e) {
                log.warn("Не удалось закрыть заказ {} поздним платежом старой ссылки общего счета {}", item.getOrder() == null ? null : item.getOrder().getId(), invoiceId, e);
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Поздний банковский платеж не применен: заказ " + orderFailureLabel(item) + " не удалось закрыть. Все изменения отменены; повторите после устранения ошибки", e);
            }
        }
        invoiceOrderRepository.saveAll(items);
        mergeInvoicePaymentMethod(invoice, latePaymentMethod);
        refreshInvoiceAmounts(invoice, items);
        finishLatePaymentApply(invoice, refs, items, closedOrderIds);
        return invoiceAfterOrderPrelude(invoiceId);
    }

    Optional<CommonInvoice> lockedInvoice(Long invoiceId) {
        return settlementService.lockedInvoice(invoiceId);
    }

    /**
     * Establishes the same lock order used by standalone payment mutations:
     * Order aggregates, their PaymentLink rows, and only then the common invoice.
     * Membership is checked again while all locks are held so a pre-lock snapshot
     * can never authorize or reconcile a different invoice composition.
     */
    LockedInvoicePaymentPrelude lockedInvoiceAfterStandalonePaymentPrelude(Long invoiceId) {
        return invoiceDelivery.lockedInvoiceAfterStandalonePaymentPrelude(invoiceId);
    }

    Map<Long, List<PaymentLink>> paymentLinksRequiringCommonInvoiceRouteCheck(Map<Long, List<PaymentLink>> paymentLinksByOrder, Collection<CommonInvoiceOrder> items, Set<PaymentLink> appliedStandalonePayments) {
        return settlementService.paymentLinksRequiringCommonInvoiceRouteCheck(paymentLinksByOrder, items, appliedStandalonePayments);
    }

    int closeProvablyUnstartedStandaloneRoutesOrThrow(Map<Long, List<PaymentLink>> paymentLinksByOrder, Long invoiceId) {
        return invoiceInitialization.closeProvablyUnstartedStandaloneRoutesOrThrow(paymentLinksByOrder, invoiceId);
    }

    Set<PaymentLink> synchronizeConfirmedStandalonePaymentsOrThrow(CommonInvoice invoice, List<CommonInvoiceOrder> items, Map<Long, List<PaymentLink>> paymentLinksByOrder) {
        return settlementService.synchronizeConfirmedStandalonePaymentsOrThrow(invoice, items, paymentLinksByOrder);
    }

    /**
     * An UNPAID marker is the expected source state for a bad-review successor,
     * not evidence that money moved. Only durable settlement/evidence fields
     * make cloning that position unsafe.
     */
    boolean hasBadReviewSuccessorPaymentEvidence(CommonInvoiceOrder item) {
        return invoiceMembershipWorkflow.hasBadReviewSuccessorPaymentEvidence(item);
    }

    boolean hasCurrentCommonPaymentRoute(CommonInvoice invoice) {
        return settlementService.hasCurrentCommonPaymentRoute(invoice);
    }

    void deleteUnsentBadReviewSuccessor(CommonInvoice successor, List<CommonInvoiceOrder> successorItems) {
        Long successorId = successor.getId();
        Long predecessorId = successor.getSupersedesInvoice() == null ? null : successor.getSupersedesInvoice().getId();
        if (predecessorId == null || invoiceRepository.existsBySupersedesInvoice_Id(successorId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Можно удалить только последний неотправленный дополнительный цикл");
        }
        lockedInvoice(predecessorId).orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Предыдущий цикл общего счета не найден"));
        Set<Long> successorOrderIds = successorItems.stream().map(CommonInvoiceOrder::getOrder).filter(Objects::nonNull).map(Order::getId).filter(Objects::nonNull).collect(Collectors.toSet());
        List<CommonInvoiceOrder> predecessorItems = invoiceOrderRepository.findByInvoiceIdWithOrders(predecessorId).stream().filter(item -> item.getOrder() != null && successorOrderIds.contains(item.getOrder().getId())).toList();
        if (predecessorItems.size() != successorOrderIds.size() || predecessorItems.stream().anyMatch(CommonInvoiceOrder::isActiveMembership)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Состав цикла изменился. Обновите данные и повторите действие");
        }
        int detachedLinks = invoiceOrderRepository.deleteByInvoiceId(successorId);
        if (detachedLinks != successorItems.size()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Состав цикла изменился");
        }
        predecessorItems.forEach(item -> item.setActiveMembership(true));
        invoiceOrderRepository.saveAll(predecessorItems);
        entityManager.flush();
        paymentRefRepository.deleteByInvoiceId(successorId);
        invoiceRepository.deleteById(successorId);
        log.info("Удален неотправленный дополнительный цикл {}. Активное членство вернуто в цикл {}", successorId, predecessorId);
    }

    boolean isFrozenLiveContractorSource(CommonInvoice invoice) {
        return settlementService.isFrozenLiveContractorSource(invoice);
    }

    boolean hasExactContractorSourceEvidence(CommonInvoice invoice) {
        return settlementService.hasExactContractorSourceEvidence(invoice);
    }

    void validateContractorCommonSourceConfirmation(CommonInvoice invoice, long confirmedTotalKopecks, LocalDateTime effectiveAt, String reason) {
        if (!isFrozenLiveContractorSource(invoice)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Операция доступна только для зафиксированного платежного профиля специалиста или менеджера");
        }
        if (confirmedTotalKopecks <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Подтвержденная сумма должна быть положительной");
        }
        if (effectiveAt != null && effectiveAt.isAfter(LocalDateTime.now().plusMinutes(1))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Время поступления не может быть в будущем");
        }
        if (normalize(reason).isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Укажите основание сверки");
        }
        if (invoice.getStatus() == CommonInvoiceStatus.PAID || invoice.getStatus() == CommonInvoiceStatus.BAN || invoice.getStatus() == CommonInvoiceStatus.DISABLED || invoice.getStatus() == CommonInvoiceStatus.ARCHIVED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Общий счет уже закрыт и требует отдельной сверки");
        }
    }

    /**
     * A late transfer for predecessor A may retire only a provably unstarted
     * successor B. Once B was sent, reported, routed or otherwise gained
     * payment evidence, choosing a recipient automatically is unsafe.
     */
    void retireUnstartedSuccessorForLateSourceOrThrow(CommonInvoice source) {
        if (source == null || source.getId() == null) {
            return;
        }
        List<CommonInvoice> successors = invoiceRepository.findSuccessorsForUpdate(source.getId());
        if (successors == null || successors.isEmpty()) {
            return;
        }
        if (successors.size() != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Цепочка дополнительных счетов неоднозначна; нужна ручная сверка");
        }
        CommonInvoice successor = successors.get(0);
        List<CommonInvoiceOrder> successorItems = invoiceOrderRepository.findByInvoiceIdWithOrders(successor.getId());
        if (successorWasStarted(successor, successorItems)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Следующий счет уже был выдан клиенту или содержит платежные признаки; сначала выполните его ручную сверку");
        }
        deleteUnsentBadReviewSuccessor(successor, successorItems);
    }

    boolean successorWasStarted(CommonInvoice successor, List<CommonInvoiceOrder> items) {
        if (successor == null) {
            return true;
        }
        String operation = normalize(successor.getLastError());
        boolean invoiceEvidence = successor.getSentAt() != null || successor.getClientReportedAt() != null || successor.getManualConfirmedAt() != null || successor.getPaidAt() != null || successor.getPaidKopecks() > 0 || successor.getContractorAllocationId() != null || isMessageSendInProgress(operation) || PAYMENT_INIT_IN_PROGRESS.equals(operation) || hasFrozenCommonPaymentRoute(successor) || hasCurrentCommonPaymentRoute(successor) || paymentRefRepository.existsByInvoice_Id(successor.getId());
        return invoiceEvidence || (items != null && items.stream().anyMatch(this::hasBadReviewSuccessorPaymentEvidence));
    }

    <T> T writeTransaction(Supplier<T> action) {
        return settlementService.writeTransaction(action);
    }

    ResponseStatusException invoiceMembershipChanged(String detail) {
        return settlementService.invoiceMembershipChanged(detail);
    }

    boolean isInvoiceReady(Long invoiceId) {
        return invoiceMembershipWorkflow.isInvoiceReady(invoiceId);
    }

    boolean isMessageSendInProgress(String state) {
        return invoiceInitialization.isMessageSendInProgress(state);
    }

    void ensureCommonInvoiceNeedsAttention(CommonInvoice invoice) {
        manualPaymentWorkflow.ensureCommonInvoiceNeedsAttention(invoice);
    }

    void ensureAttentionCanBeRetried(CommonInvoice invoice) {
        String error = attentionError(invoice);
        if (isMigrationPaymentRegistryAttention(invoice)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Платеж из миграционного реестра нельзя повторять до ручной сверки T-Bank");
        }
        if (isLatePaymentAttention(invoice)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "По старой T-Bank ссылке пришел поздний платеж. Его нельзя автоматически применить: нужна ручная сверка суммы.");
        }
        if (error.startsWith(PAYMENT_CANCEL_FAILED_FINAL)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Банковскую платежную ссылку не удалось завершить автоматически. Проверьте банк вручную.");
        }
        if (!error.startsWith("close_failed") && !error.startsWith("next_order_failed")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Эту ручную проверку нельзя закрывать повторным автозакрытием заказов. Проверьте причину и используйте ручное разрешение.");
        }
    }

    void ensureAttentionCanBeResolved(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        String error = attentionError(invoice);
        if (isMigrationPaymentRegistryAttention(invoice)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Миграционный платежный карантин нельзя закрыть без ручной сверки T-Bank");
        }
        if (isLatePaymentAttention(invoice)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Поздний платеж по старой T-Bank ссылке нельзя закрыть без распределения оплаты вручную");
        }
        if (error.startsWith(PAYMENT_CANCEL_FAILED_FINAL)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Банковскую платежную ссылку не удалось завершить автоматически. Проверьте банк вручную.");
        }
        if (isPaymentInitManualCheckAttention(invoice)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Создание T-Bank ссылки требует ручной сверки банка.");
        }
        boolean allItemsPaid = items != null && !items.isEmpty() && items.stream().allMatch(CommonInvoiceOrder::isPaid);
        if (error.startsWith("close_failed") && !allItemsPaid) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Платеж получен, но часть заказов еще не закрыта. Исправьте причину и нажмите \"Повторить\".");
        }
        ensureNoRecordedFullPaymentWithOpenItems(invoice, items);
    }

    void ensureNoUnresolvedPaymentRefsForGenericAttentionResolution(CommonInvoice invoice) {
        if (invoice == null || invoice.getId() == null) {
            return;
        }
        List<CommonInvoicePaymentRef> unresolved = paymentRefRepository.findByInvoiceIdForUpdate(invoice.getId()).stream().filter(ref -> PAYMENT_INIT_NEW_ATTEMPT_BLOCKING_REF_STATUSES.contains(paymentRefStatus(ref))).toList();
        if (!unresolved.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "У общего счета осталась незавершенная банковская операция. " + "Закройте ее через явную ручную проверку банка.");
        }
    }

    void ensureCommonInvoiceTechnicalTailCanBeResolved(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        if (invoice == null || invoice.getStatus() != CommonInvoiceStatus.DISABLED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Можно закрыть только отключенный технический хвост общего счета");
        }
        if (!isResolvableTechnicalTail(invoice)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Эту ошибку общего счета нельзя закрыть как технический хвост. Нужно исправить причину.");
        }
        boolean hasUnpaidPosition = items != null && items.stream().anyMatch(item -> !item.isPaid());
        if (hasUnpaidPosition) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "В отключенном общем счете остались неоплаченные позиции. Нельзя скрывать его из контроля.");
        }
    }

    boolean isResolvableTechnicalTail(CommonInvoice invoice) {
        String error = attentionError(invoice);
        if (error.isBlank()) {
            return false;
        }
        return RESOLVABLE_TECHNICAL_TAIL_ERROR_PREFIXES.stream().anyMatch(error::startsWith);
    }

    void ensureNoRecordedFullPaymentWithOpenItems(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        boolean allItemsPaid = items != null && !items.isEmpty() && items.stream().allMatch(CommonInvoiceOrder::isPaid);
        boolean fullPaymentRecorded = invoice != null && invoice.getAmountKopecks() > 0 && invoice.getPaidKopecks() >= invoice.getAmountKopecks();
        if (fullPaymentRecorded && !allItemsPaid) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "У счета зафиксирована полная оплата, но не все позиции закрыты. Нельзя очищать проверку без закрытия заказов.");
        }
    }

    boolean isLatePaymentAttention(CommonInvoice invoice) {
        String error = attentionError(invoice);
        return error.startsWith("late_tbank_payment") || error.startsWith("late_payment_");
    }

    boolean isPaymentInitManualCheckAttention(CommonInvoice invoice) {
        return invoiceDetailsAssembler.isPaymentInitManualCheckAttention(invoice);
    }

    boolean isMigrationPaymentRegistryAttention(CommonInvoice invoice) {
        return settlementService.isMigrationPaymentRegistryAttention(invoice);
    }

    boolean isManuallyConfirmableMigrationPaymentRegistryAttention(CommonInvoice invoice) {
        return invoiceDetailsAssembler.isManuallyConfirmableMigrationPaymentRegistryAttention(invoice);
    }

    boolean isDefinitelyUnsentPaymentInitTlsFailure(CommonInvoice invoice) {
        return CommonPaymentInitFailureClassifier.isPersistedTlsBeforeHttpFailure(invoice == null ? null : invoice.getLastError());
    }

    void ensureInvoiceHasNoCurrentProviderEvidence(CommonInvoice invoice) {
        boolean hasProviderEvidence = !normalize(invoice.getTbankOrderId()).isBlank() || !normalize(invoice.getTbankPaymentId()).isBlank() || !normalize(invoice.getTbankTerminalKey()).isBlank() || invoice.getTbankPaymentAmountKopecks() != null || invoice.getTbankPaymentCreatedAt() != null || !normalize(invoice.getPaymentUrl()).isBlank();
        if (hasProviderEvidence) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Автопочинка остановлена: в общем счете уже есть реквизиты платежа T-Bank");
        }
    }

    void ensureUnsentTlsPaymentRefsCanBeRecovered(CommonInvoice invoice, List<CommonInvoicePaymentRef> refs) {
        List<CommonInvoicePaymentRef> safeRefs = refs == null ? List.of() : refs;
        if (safeRefs.stream().anyMatch(ref -> !isTlsRecoveryAllowedPaymentRef(ref))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Автопочинка остановлена: платежный реестр содержит активное или неизвестное состояние");
        }
        List<CommonInvoicePaymentRef> unresolved = safeRefs.stream().filter(this::isPreparedPaymentRef).toList();
        if (unresolved.size() != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Автопочинка остановлена: не найден единственный незавершенный TLS-запрос");
        }
        CommonInvoicePaymentRef ref = unresolved.getFirst();
        String expectedReason = CommonPaymentInitFailureClassifier.isExactKnownLegacyTlsFailure(invoice == null ? null : invoice.getLastError()) ? CommonPaymentInitFailureClassifier.LEGACY_TLS_BEFORE_HTTP_REF_REASON : CommonPaymentInitFailureClassifier.TLS_BEFORE_HTTP_REF_REASON;
        boolean exactPreRequestFailure = expectedReason.equals(normalize(ref.getReason())) && !normalize(ref.getTbankOrderId()).isBlank() && normalize(ref.getTbankPaymentId()).isBlank() && !normalize(ref.getTbankTerminalKey()).isBlank() && ref.getAmountKopecks() != null && ref.getAmountKopecks() > 0;
        if (!exactPreRequestFailure) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Автопочинка остановлена: в платежном реестре есть неполные или неоднозначные данные");
        }
    }

    boolean isTlsRecoveryAllowedPaymentRef(CommonInvoicePaymentRef ref) {
        String status = paymentRefStatus(ref);
        if (PAYMENT_REF_ARCHIVED.equals(status)) {
            return isStrictlySafeArchivedPaymentRef(ref);
        }
        return PAYMENT_INIT_TLS_RECOVERY_ALLOWED_REF_STATUSES.contains(status);
    }

    boolean isStrictlySafeArchivedPaymentRef(CommonInvoicePaymentRef ref) {
        String reason = normalize(ref == null ? null : ref.getReason()).toLowerCase(Locale.ROOT);
        if (reason.startsWith(PAYMENT_INIT_MANUALLY_CHECKED_BY_PREFIX) || PAYMENT_INIT_MANUALLY_CHECKED_REASON.equals(reason)) {
            return true;
        }
        return reason.startsWith(PAYMENT_INIT_TLS_SAFE_ARCHIVED_REASON_PREFIX) && normalize(ref == null ? null : ref.getTbankPaymentId()).isBlank();
    }

    void ensureNoCompetingStandalonePaymentLinks(Map<Long, List<PaymentLink>> paymentLinksByOrder) {
        for (Map.Entry<Long, List<PaymentLink>> entry : (paymentLinksByOrder == null ? Map.<Long, List<PaymentLink>>of() : paymentLinksByOrder).entrySet()) {
            boolean competing = entry.getValue().stream().anyMatch(StandaloneBankPaymentPolicy::blocksCommonInvoiceTlsRecovery);
            if (competing) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Автопочинка остановлена: у заказа #" + entry.getKey() + " есть отдельный незавершенный платеж. Сначала сверьте и закройте его вручную.");
            }
        }
    }

    void ensureMigrationPaymentRegistryCanBeManuallyResolved(CommonInvoice invoice, List<CommonInvoicePaymentRef> refs, Map<Long, List<PaymentLink>> paymentLinksByOrder) {
        if (!isManuallyConfirmableMigrationPaymentRegistryAttention(invoice)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Этот тип миграционного платежного конфликта нельзя закрыть из карточки счета");
        }
        if (refs.stream().anyMatch(ref -> !PREPARED_PAYMENT_REF_LIFECYCLE_STATUSES.contains(paymentRefStatus(ref)))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ручная сверка остановлена: платежный реестр содержит неизвестное состояние");
        }
        if (refs.stream().anyMatch(ref -> PAYMENT_INIT_MANUAL_BLOCKING_REF_STATUSES.contains(paymentRefStatus(ref)))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ручная сверка остановлена: платеж активен, отменяется или уже применен");
        }
        String invoiceOrderId = normalize(invoice.getTbankOrderId());
        String invoicePaymentId = normalize(invoice.getTbankPaymentId());
        String invoiceTerminalKey = normalize(invoice.getTbankTerminalKey());
        Long invoiceAmount = invoice.getTbankPaymentAmountKopecks();
        if (invoiceOrderId.isBlank() || invoicePaymentId.isBlank() || invoiceTerminalKey.isBlank() || invoiceAmount == null || invoiceAmount <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ручная сверка остановлена: сохраненные реквизиты T-Bank неполны");
        }
        List<CommonInvoicePaymentRef> unresolved = refs.stream().filter(this::isPreparedPaymentRef).toList();
        if (unresolved.size() != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ручная сверка остановлена: найдено неоднозначное число незавершенных платежей");
        }
        CommonInvoicePaymentRef ref = unresolved.getFirst();
        boolean completeReconciliationEvidence = !normalize(ref.getTbankOrderId()).isBlank() && !normalize(ref.getTbankTerminalKey()).isBlank() && invoiceTerminalKey.equals(normalize(ref.getTbankTerminalKey())) && ref.getAmountKopecks() != null && invoiceAmount.equals(ref.getAmountKopecks());
        if (!completeReconciliationEvidence) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ручная сверка остановлена: в платежном реестре недостаточно данных для проверки T-Bank");
        }
        ensureNoCompetingStandalonePaymentLinks(paymentLinksByOrder);
    }

    void ensurePaymentEvidenceSnapshotMatches(CommonInvoice invoice, List<CommonInvoicePaymentRef> refs, CommonInvoicePaymentInitCheckRequest request) {
        String supplied = normalize(request == null ? null : request.evidenceToken());
        String current = normalize(paymentEvidenceToken(invoice, filterPaymentRefEvidenceRows(refs)));
        if (supplied.isBlank() || current.isBlank() || !MessageDigest.isEqual(supplied.getBytes(StandardCharsets.UTF_8), current.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Платежные данные изменились или открыты в устаревшей карточке. Обновите счет и повторите сверку.");
        }
    }

    boolean isPreparedPaymentRef(CommonInvoicePaymentRef ref) {
        return invoiceDetailsAssembler.isPreparedPaymentRef(ref);
    }

    String paymentRefStatus(CommonInvoicePaymentRef ref) {
        return settlementService.paymentRefStatus(ref);
    }

    String normalizedPaymentProvider(CommonInvoicePaymentRef ref) {
        return settlementService.normalizedPaymentProvider(ref);
    }

    String latePaymentMethod(List<CommonInvoicePaymentRef> refs) {
        Set<String> methods = (refs == null ? List.<CommonInvoicePaymentRef>of() : refs).stream().filter(Objects::nonNull).map(this::normalizedPaymentProvider).map(provider -> PROVIDER_TOCHKA.equals(provider) ? PAYMENT_METHOD_TOCHKA : PAYMENT_METHOD_TBANK).collect(Collectors.toSet());
        if (methods.size() > 1) {
            return PAYMENT_METHOD_MIXED;
        }
        return methods.stream().findFirst().orElse(PAYMENT_METHOD_TBANK);
    }

    String attentionError(CommonInvoice invoice) {
        return settlementService.attentionError(invoice);
    }

    void finishLatePaymentApply(CommonInvoice invoice, List<CommonInvoicePaymentRef> refs, List<CommonInvoiceOrder> items, Set<Long> closedOrderIds) {
        boolean allPaid = !items.isEmpty() && items.stream().allMatch(CommonInvoiceOrder::isPaid);
        setPaymentRefsStatus(refs, PAYMENT_REF_APPLIED);
        if (allPaid) {
            closePaidInvoice(invoice, items, closedOrderIds);
            return;
        }
        invoice.setStatus(CommonInvoiceStatus.PARTIALLY_PAID);
        invoice.setLastError(null);
        invoice.setNextReminderAt(nextAutomaticPaymentReminderAt(LocalDateTime.now()));
        invoiceRepository.save(invoice);
    }

    void closePaidInvoice(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        settlementService.closePaidInvoice(invoice, items);
    }

    void closePaidInvoice(CommonInvoice invoice, List<CommonInvoiceOrder> items, Set<Long> alreadyClosedOrderIds) {
        settlementService.closePaidInvoice(invoice, items, alreadyClosedOrderIds);
    }

    void closePaidInvoice(CommonInvoice invoice, List<CommonInvoiceOrder> items, Set<Long> alreadyClosedOrderIds, Runnable finalAttribution) {
        settlementService.closePaidInvoice(invoice, items, alreadyClosedOrderIds, finalAttribution);
    }

    void markInvoicePaidClosed(CommonInvoice invoice) {
        settlementService.markInvoicePaidClosed(invoice);
    }

    void closeOrderAsPaidWithoutNextOrder(Order order) throws Exception {
        settlementService.closeOrderAsPaidWithoutNextOrder(order);
    }

    void closeOrderAsPaidWithoutNextOrder(Order order, boolean standalonePaymentAlreadyConfirmed) throws Exception {
        settlementService.closeOrderAsPaidWithoutNextOrder(order, standalonePaymentAlreadyConfirmed);
    }

    String orderFailureLabel(CommonInvoiceOrder item) {
        return settlementService.orderFailureLabel(item);
    }

    String orderFailureLabel(Order order) {
        return settlementService.orderFailureLabel(order);
    }

    void recalculateInvoice(CommonInvoice invoice) {
        settlementService.recalculateInvoice(invoice);
    }

    void recalculateInvoice(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        settlementService.recalculateInvoice(invoice, items);
    }

    void refreshInvoiceAmounts(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        settlementService.refreshInvoiceAmounts(invoice, items);
    }

    void setPaymentRefsStatus(List<CommonInvoicePaymentRef> refs, String status) {
        settlementService.setPaymentRefsStatus(refs, status);
    }

    CommonInvoiceDetailsResponse invoiceDetails(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        return invoiceDetailsAssembler.invoiceDetails(invoice, items);
    }

    List<CommonInvoicePaymentRef> filterPaymentRefEvidenceRows(List<CommonInvoicePaymentRef> paymentRefs) {
        return invoiceDetailsAssembler.filterPaymentRefEvidenceRows(paymentRefs);
    }

    String paymentEvidenceToken(CommonInvoice invoice, List<CommonInvoicePaymentRef> paymentRefs) {
        return invoiceDetailsAssembler.paymentEvidenceToken(invoice, paymentRefs);
    }

    void ensureCommonInvoiceVisibleForCurrentUser(CommonInvoice invoice) {
        invoiceDelivery.ensureCommonInvoiceVisibleForCurrentUser(invoice);
    }

    Authentication currentAuthentication() {
        return invoiceDelivery.currentAuthentication();
    }

    void applyManualPaymentEvidence(CommonInvoice invoice, List<CommonInvoiceOrder> items, ManualPaymentConfirmationRequest request, Principal principal) {
        manualPaymentWorkflow.applyManualPaymentEvidence(invoice, items, request, principal);
    }

    void applyManualPaymentEvidence(CommonInvoice invoice, CommonInvoiceOrder item, ManualPaymentConfirmationRequest request, Principal principal) {
        manualPaymentWorkflow.applyManualPaymentEvidence(invoice, item, request, principal);
    }

    void applyManualPaymentEvidence(CommonInvoiceOrder item, ManualPaymentConfirmationRequest request, String actor) {
        manualPaymentWorkflow.applyManualPaymentEvidence(item, request, actor);
    }

    void mergeInvoicePaymentMethod(CommonInvoice invoice, String method) {
        settlementService.mergeInvoicePaymentMethod(invoice, method);
    }

    void scheduleContractorShadowReconcile(Long invoiceId) {
        settlementService.scheduleContractorShadowReconcile(invoiceId);
    }

    boolean hasFrozenCommonPaymentRoute(CommonInvoice invoice) {
        return manualPaymentWorkflow.hasFrozenCommonPaymentRoute(invoice);
    }

    LocalDateTime nextAutomaticPaymentReminderAt(LocalDateTime from) {
        return settlementService.nextAutomaticPaymentReminderAt(from);
    }

    void markInvoiceOrdersPublished(Long invoiceId) {
        settlementService.markInvoiceOrdersPublished(invoiceId);
    }

    void markInvoiceOrdersPublished(List<CommonInvoiceOrder> items) {
        settlementService.markInvoiceOrdersPublished(items);
    }

    long remainingKopecks(CommonInvoice invoice) {
        return settlementService.remainingKopecks(invoice);
    }

    void resolvePreparedPaymentInitAfterManualCheck(CommonInvoice invoice) {
        if (invoice == null || invoice.getId() == null) {
            return;
        }
        String previousAttention = normalize(invoice.getLastError());
        if (paymentRefRepository.existsByInvoice_IdAndStatusIn(invoice.getId(), PAYMENT_INIT_MANUAL_BLOCKING_REF_STATUSES)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Нельзя завершить ручную сверку: платеж еще активен, отменяется или уже подтвержден");
        }
        List<CommonInvoicePaymentRef> unresolved = new ArrayList<>();
        unresolved.addAll(paymentRefRepository.findByInvoiceIdAndStatusForUpdate(invoice.getId(), PAYMENT_REF_INIT_PREPARED));
        unresolved.addAll(paymentRefRepository.findByInvoiceIdAndStatusForUpdate(invoice.getId(), PAYMENT_REF_INIT_CONFLICT));
        if (unresolved.stream().anyMatch(this::canCancelInitializedPaymentRef)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Нельзя завершить ручную сверку: найден созданный платеж, который сначала нужно отменить");
        }
        for (CommonInvoicePaymentRef ref : unresolved) {
            ref.setStatus(PAYMENT_REF_ARCHIVED);
            ref.setReason(manualPaymentInitCheckAuditReason(ref.getReason()));
        }
        if (!unresolved.isEmpty()) {
            paymentRefRepository.saveAll(unresolved);
        }
        if (normalize(invoice.getTbankOrderId()).isBlank() && normalize(invoice.getTbankPaymentId()).isBlank()) {
            return;
        }
        Optional<CommonInvoicePaymentRef> existing = lockedPaymentRefByProviderBinding(invoice.getTbankOrderId(), invoice.getTbankPaymentId());
        if (existing.isPresent() && !Objects.equals(invoice.getId(), paymentRefInvoiceId(existing.get()))) {
            throw invoiceMembershipChanged("T-Bank ссылка для ручной сверки принадлежит другому счету");
        }
        CommonInvoicePaymentRef ref = existing.orElseGet(() -> {
            CommonInvoicePaymentRef created = new CommonInvoicePaymentRef();
            created.setInvoice(invoice);
            return created;
        });
        copyCurrentPaymentBindingToRef(invoice, ref);
        ref.setStatus(PAYMENT_REF_ARCHIVED);
        boolean alreadyAudited = unresolved.stream().anyMatch(unresolvedRef -> Objects.equals(unresolvedRef.getId(), ref.getId()));
        if (!alreadyAudited) {
            ref.setReason(manualPaymentInitCheckAuditReason(previousAttention));
        }
        paymentRefRepository.save(ref);
        flushPaymentRefProviderEvidence(ref);
        clearCurrentPaymentRef(invoice);
        Authentication authentication = currentAuthentication();
        log.warn("Common invoice {} payment-init evidence manually reconciled by {}", invoice.getId(), authentication == null ? "unknown" : normalize(authentication.getName()));
    }

    String manualPaymentInitCheckAuditReason(String previousReason) {
        Authentication authentication = currentAuthentication();
        String actor = authentication == null ? "unknown" : normalize(authentication.getName());
        String previous = normalize(previousReason);
        return limit(PAYMENT_INIT_MANUALLY_CHECKED_BY_PREFIX + limit(actor.isBlank() ? "unknown" : actor, 40) + (previous.isBlank() ? "" : "; previous=" + previous), 160);
    }

    String manualPaymentCancelCheckAuditReason(String previousReason) {
        Authentication authentication = currentAuthentication();
        String actor = authentication == null ? "unknown" : normalize(authentication.getName());
        String previous = normalize(previousReason);
        return limit(PAYMENT_CANCEL_MANUALLY_CHECKED_BY_PREFIX + limit(actor.isBlank() ? "unknown" : actor, 40) + (previous.isBlank() ? "" : "; previous=" + previous), 160);
    }

    Long paymentRefInvoiceId(CommonInvoicePaymentRef ref) {
        return settlementService.paymentRefInvoiceId(ref);
    }

    void flushPaymentRefProviderEvidence(CommonInvoicePaymentRef ref) {
        settlementService.flushPaymentRefProviderEvidence(ref);
    }

    Optional<CommonInvoicePaymentRef> lockedPaymentRefByProviderBinding(String tbankOrderId, String tbankPaymentId) {
        return settlementService.lockedPaymentRefByProviderBinding(tbankOrderId, tbankPaymentId);
    }

    void copyCurrentPaymentBindingToRef(CommonInvoice invoice, CommonInvoicePaymentRef ref) {
        settlementService.copyCurrentPaymentBindingToRef(invoice, ref);
    }

    boolean canCancelInitializedPaymentRef(CommonInvoicePaymentRef ref) {
        return settlementService.canCancelInitializedPaymentRef(ref);
    }

    void clearCurrentPaymentRef(CommonInvoice invoice) {
        settlementService.clearCurrentPaymentRef(invoice);
    }

    String normalize(String value) {
        return settlementService.normalize(value);
    }

    String limit(String value, int max) {
        return settlementService.limit(value, max);
    }
}
