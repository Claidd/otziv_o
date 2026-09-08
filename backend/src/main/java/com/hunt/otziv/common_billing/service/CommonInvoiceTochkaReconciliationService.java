package com.hunt.otziv.common_billing.service;

import static com.hunt.otziv.common_billing.service.CommonInvoiceDeliveryService.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceDetailsAssembler.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceInitializationService.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceCancellationService.*;
import static com.hunt.otziv.common_billing.service.CommonInvoicePresenter.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceSettlementService.*;
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoicePaymentRef;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonInvoicePaymentRefRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.model.PaymentProfile;
import com.hunt.otziv.payments.service.PaymentProfileService;
import com.hunt.otziv.payments.service.PaymentUrlPolicy;
import com.hunt.otziv.payments.tochka.dto.TochkaApiModels.PaymentInfoResponse;
import com.hunt.otziv.payments.tochka.dto.TochkaApiModels.PaymentOperation;
import com.hunt.otziv.payments.tochka.dto.TochkaPaymentProfile;
import com.hunt.otziv.payments.tochka.dto.TochkaRefundCommand;
import com.hunt.otziv.payments.tochka.model.TochkaPaymentMode;
import com.hunt.otziv.payments.tochka.service.TochkaClient;
import com.hunt.otziv.payments.tochka.service.TochkaPaymentOperationMapper;
import com.hunt.otziv.payments.tochka.service.TochkaPaymentOperationMapper.ExpectedPayment;
import com.hunt.otziv.payments.tochka.service.TochkaPaymentOperationMapper.MappedPayment;
import com.hunt.otziv.payments.tochka.service.TochkaPaymentProfileResolver;
import com.hunt.otziv.payments.tochka.service.TochkaProviderException;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.CaughtFailureStage.CLOSE_ORDER;
import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.CaughtFailureStage.OPEN_NEXT_ORDER;
import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.TransactionFlow.COMMON_INVOICE_CLOSE;

@Service
@Slf4j
@RequiredArgsConstructor
public class CommonInvoiceTochkaReconciliationService {

    private final CommonInvoiceCancellationService invoiceCancellation;

    private final CommonInvoiceSettlementService settlementService;

    static final java.time.Duration TOCHKA_RECONCILIATION_DELAY = java.time.Duration.ofSeconds(20);

    private final EntityManager entityManager;

    private final CommonInvoiceRepository invoiceRepository;

    private final CommonInvoicePaymentRefRepository paymentRefRepository;

    private final PaymentProfileService paymentProfileService;

    private final TochkaPaymentProfileResolver tochkaPaymentProfileResolver;

    private final TochkaClient tochkaClient;

    private final TochkaPaymentOperationMapper tochkaPaymentOperationMapper;

    /**
     * Reconciles Tochka attempts without ever inventing a local cancel. A CREATED payment remains
     * blocking until GET reports EXPIRED. APPROVED cancellation attempts are refunded once and
     * then observed through GET; an ambiguous Refund result is quarantined instead of retried.
     */
    public int reconcileTochkaPaymentRefs(int limit) {
        List<CommonInvoicePaymentRef> candidates = paymentRefRepository.findProviderReconciliationCandidates(PROVIDER_TOCHKA, Set.of(PAYMENT_REF_INIT_PREPARED, PAYMENT_REF_INIT_CONFLICT, PAYMENT_REF_CURRENT, PAYMENT_REF_CANCEL_PENDING, PAYMENT_REF_CANCELING, PAYMENT_REF_CANCEL_FAILED), LocalDateTime.now().minus(TOCHKA_RECONCILIATION_DELAY), PageRequest.of(0, Math.max(1, limit)));
        int processed = 0;
        for (CommonInvoicePaymentRef candidate : candidates) {
            Long invoiceId = paymentRefInvoiceId(candidate);
            PreparedTochkaReconciliation prepared;
            try {
                prepared = writeTransaction(() -> prepareTochkaReconciliation(candidate.getId(), invoiceId));
            } catch (RuntimeException failure) {
                log.warn("Tochka common payment reconciliation prepare failed: ref={}, invoice={}, reason={}", candidate.getId(), invoiceId, readableException(failure));
                continue;
            }
            if (prepared == null) {
                continue;
            }
            TochkaReconciliationObservation observation = observeTochkaPaymentRef(prepared);
            if (shouldSubmitTochkaRefund(prepared, observation)) {
                try {
                    PreparedTochkaReconciliation beforeClaim = prepared;
                    TochkaReconciliationObservation beforeRefund = observation;
                    PreparedTochkaReconciliation claimed = writeTransaction(() -> claimTochkaRefundSubmission(beforeClaim, beforeRefund));
                    if (claimed == null) {
                        continue;
                    }
                    prepared = claimed;
                    observation = submitClaimedTochkaRefund(prepared, observation);
                } catch (RuntimeException failure) {
                    log.warn("Tochka common refund durable claim failed: ref={}, invoice={}, reason={}", prepared.refId(), prepared.invoiceId(), readableException(failure));
                    continue;
                }
            }
            PreparedTochkaReconciliation finalPrepared = prepared;
            TochkaReconciliationObservation finalObservation = observation;
            try {
                writeTransaction(() -> {
                    finishTochkaReconciliation(finalPrepared, finalObservation);
                    return null;
                });
                processed++;
            } catch (RuntimeException failure) {
                log.warn("Tochka common payment reconciliation apply failed: ref={}, invoice={}, reason={}", finalPrepared.refId(), finalPrepared.invoiceId(), readableException(failure));
            }
        }
        return processed;
    }

    PreparedTochkaReconciliation prepareTochkaReconciliation(Long refId, Long expectedInvoiceId) {
        if (refId == null || expectedInvoiceId == null) {
            return null;
        }
        CommonInvoice invoice = lockedInvoice(expectedInvoiceId).orElse(null);
        CommonInvoicePaymentRef ref = paymentRefRepository.findByIdForUpdate(refId).orElse(null);
        if (invoice == null || ref == null || !Objects.equals(expectedInvoiceId, paymentRefInvoiceId(ref)) || !PROVIDER_TOCHKA.equals(normalizedPaymentProvider(ref))) {
            return null;
        }
        String status = paymentRefStatus(ref);
        if (!Set.of(PAYMENT_REF_INIT_PREPARED, PAYMENT_REF_INIT_CONFLICT, PAYMENT_REF_CURRENT, PAYMENT_REF_CANCEL_PENDING, PAYMENT_REF_CANCELING, PAYMENT_REF_CANCEL_FAILED).contains(status)) {
            return null;
        }
        if (ref.getPaymentProfileId() == null || providerOrderId(ref).isBlank() || providerMerchantId(ref).isBlank() || ref.getAmountKopecks() == null || ref.getAmountKopecks() <= 0 || ref.getProviderExpiresAt() == null || normalize(ref.getProviderPaymentMode()).isBlank()) {
            quarantineTochkaReconciliationCandidate(invoice, ref, "tochka_reconciliation_binding_incomplete", null);
            return null;
        }
        PaymentProfile entityProfile;
        TochkaPaymentProfile runtimeProfile;
        List<TochkaPaymentMode> modes;
        boolean profileMatches;
        try {
            entityProfile = paymentProfileService.lockByIdForRouting(ref.getPaymentProfileId());
            runtimeProfile = tochkaPaymentProfileResolver.resolveForExistingPayment(entityProfile);
            if (runtimeProfile == null) {
                throw new IllegalStateException("Pinned Tochka runtime profile is missing");
            }
            modes = parseTochkaPaymentModes(ref.getProviderPaymentMode());
            profileMatches = paymentProfileService.isTochkaProvider(entityProfile) && Objects.equals(runtimeProfile.id(), ref.getPaymentProfileId()) && Objects.equals(normalize(runtimeProfile.merchantId()), providerMerchantId(ref)) && Objects.equals(runtimeProfile.testMode(), ref.getProviderTestMode());
        } catch (RuntimeException failure) {
            quarantineTochkaReconciliationCandidate(invoice, ref, "tochka_reconciliation_profile_unavailable", failure);
            return null;
        }
        if (!profileMatches) {
            quarantineTochkaReconciliationCandidate(invoice, ref, "tochka_reconciliation_profile_changed", null);
            return null;
        }
        boolean cancellation = Set.of(PAYMENT_REF_CANCEL_PENDING, PAYMENT_REF_CANCELING, PAYMENT_REF_CANCEL_FAILED).contains(status);
        String reason = normalize(ref.getReason());
        boolean refundSubmissionClaimed = isTochkaRefundSubmissionClaimed(reason);
        return new PreparedTochkaReconciliation(ref.getId(), invoice.getId(), status, runtimeProfile, modes, providerOrderId(ref), providerPaymentId(ref), ref.getAmountKopecks(), ref.getProviderExpiresAt(), ref.getCreatedAt(), ref.getUpdatedAt(), cancellation, refundSubmissionClaimed, refundSubmissionClaimed ? tochkaRefundClaimedAt(reason, ref.getUpdatedAt()) : null);
    }

    void quarantineTochkaReconciliationCandidate(CommonInvoice invoice, CommonInvoicePaymentRef ref, String reason, RuntimeException failure) {
        String cleanReason = normalize(reason).isBlank() ? "tochka_reconciliation_unavailable" : normalize(reason);
        ref.setStatus(PAYMENT_REF_CANCEL_FAILED_FINAL);
        setTochkaReasonUnlessRefundClaimed(ref, failure == null ? cleanReason : cleanReason + ":" + readableException(failure));
        paymentRefRepository.save(ref);
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setNextReminderAt(null);
        invoice.setPaymentUrl(null);
        invoice.setLastError(limit(PAYMENT_CANCEL_FAILED_FINAL + ": платежная ссылка Точки требует ручной проверки банка (" + cleanReason + (failure == null ? "" : ": " + readableException(failure)) + ")", 512));
        invoiceRepository.save(invoice);
    }

    TochkaReconciliationObservation observeTochkaPaymentRef(PreparedTochkaReconciliation prepared) {
        try {
            PaymentOperation operation;
            if (prepared.paymentId().isBlank()) {
                LocalDate today = LocalDate.now(MOSCOW_ZONE);
                LocalDate createdDate = prepared.createdAt() == null ? today.minusDays(1) : prepared.createdAt().toLocalDate();
                LocalDate fromDate = createdDate.isBefore(today.minusDays(7)) ? today.minusDays(7) : createdDate.minusDays(1);
                Optional<PaymentOperation> recovered = tochkaClient.findPaymentByPaymentLinkId(prepared.runtimeProfile(), prepared.paymentLinkId(), prepared.amountKopecks(), fromDate, today);
                if (recovered.isEmpty()) {
                    return TochkaReconciliationObservation.missing();
                }
                operation = recovered.get();
            } else {
                PaymentInfoResponse response = tochkaClient.getPaymentInfo(prepared.runtimeProfile(), prepared.paymentId());
                operation = requireSingleTochkaCommonOperation(response, prepared.paymentId());
            }
            String operationId = normalize(operation.operationId());
            TochkaPaymentMode observedMode = observedTochkaPaymentMode(operation.paymentType(), operation.status(), prepared.paymentModes());
            MappedPayment mapped = tochkaPaymentOperationMapper.map(operation, new ExpectedPayment(operationId, prepared.paymentLinkId(), prepared.runtimeProfile().customerCode(), prepared.runtimeProfile().merchantId(), prepared.amountKopecks(), observedMode), false);
            return TochkaReconciliationObservation.observed(operation, mapped);
        } catch (RuntimeException failure) {
            return TochkaReconciliationObservation.failed(failure);
        }
    }

    boolean shouldSubmitTochkaRefund(PreparedTochkaReconciliation prepared, TochkaReconciliationObservation observation) {
        return prepared != null && prepared.cancellation() && !prepared.refundSubmissionClaimed() && observation != null && observation.failure() == null && !observation.notFound() && observation.mapped() != null && observation.mapped().status() == PaymentLinkStatus.CONFIRMED;
    }

    /**
     * Persists an at-most-once refund claim before the irreversible POST. The claim is never
     * cleared or replaced by another retryable marker: after this commit all later workers may
     * only observe GET state or send the case to manual reconciliation.
     */
    PreparedTochkaReconciliation claimTochkaRefundSubmission(PreparedTochkaReconciliation prepared, TochkaReconciliationObservation observation) {
        if (!shouldSubmitTochkaRefund(prepared, observation) || observation.operation() == null) {
            return null;
        }
        CommonInvoice invoice = lockedInvoice(prepared.invoiceId()).orElse(null);
        CommonInvoicePaymentRef ref = paymentRefRepository.findByIdForUpdate(prepared.refId()).orElse(null);
        if (invoice == null || ref == null || !Objects.equals(prepared.invoiceId(), paymentRefInvoiceId(ref)) || !PROVIDER_TOCHKA.equals(normalizedPaymentProvider(ref)) || !prepared.paymentLinkId().equals(providerOrderId(ref)) || !Objects.equals(prepared.amountKopecks(), ref.getAmountKopecks()) || !Set.of(PAYMENT_REF_CANCEL_PENDING, PAYMENT_REF_CANCELING, PAYMENT_REF_CANCEL_FAILED).contains(paymentRefStatus(ref)) || isTochkaRefundSubmissionClaimed(ref.getReason())) {
            return null;
        }
        String operationId = normalize(observation.operation().operationId());
        if (operationId.isBlank() || (!providerPaymentId(ref).isBlank() && !providerPaymentId(ref).equals(operationId))) {
            quarantineTochkaInit(invoice, ref, "tochka_refund_operation_changed");
            return null;
        }
        Optional<CommonInvoicePaymentRef> foreign = paymentRefRepository.findByProviderAndProviderPaymentId(PROVIDER_TOCHKA, operationId).filter(candidate -> !Objects.equals(candidate.getId(), ref.getId()));
        if (foreign.isPresent()) {
            quarantineTochkaInit(invoice, ref, "tochka_refund_operation_collision");
            return null;
        }
        LocalDateTime claimedAt = LocalDateTime.now();
        String claim = TOCHKA_REFUND_SUBMITTING_PREFIX + System.currentTimeMillis() + ":" + randomToken();
        ref.setProviderPaymentId(limit(operationId, 64));
        ref.setProviderStatus(limit(observation.mapped().providerStatus(), 32));
        ref.setStatus(PAYMENT_REF_CANCELING);
        ref.setCancelAttempts(cancelAttempts(ref) + 1);
        ref.setReason(limit(claim, 160));
        paymentRefRepository.save(ref);
        entityManager.flush();
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setNextReminderAt(null);
        invoice.setPaymentUrl(null);
        invoice.setLastError(limit("tochka_refund_submitting: возврат зафиксирован перед отправкой; " + "повторный Refund автоматически выполняться не будет", 512));
        invoiceRepository.save(invoice);
        return new PreparedTochkaReconciliation(prepared.refId(), prepared.invoiceId(), PAYMENT_REF_CANCELING, prepared.runtimeProfile(), prepared.paymentModes(), prepared.paymentLinkId(), operationId, prepared.amountKopecks(), prepared.providerExpiresAt(), prepared.createdAt(), claimedAt, true, true, claimedAt);
    }

    TochkaReconciliationObservation submitClaimedTochkaRefund(PreparedTochkaReconciliation prepared, TochkaReconciliationObservation observed) {
        try {
            tochkaClient.refund(prepared.runtimeProfile(), new TochkaRefundCommand(prepared.paymentId(), prepared.amountKopecks()));
            return observed.withRefundAttempt(true);
        } catch (RuntimeException failure) {
            return observed.withRefundFailure(failure);
        }
    }

    boolean isTochkaRefundSubmissionClaimed(String reason) {
        return settlementService.isTochkaRefundSubmissionClaimed(reason);
    }

    boolean isTochkaCancellationLifecycleStatus(String status) {
        return settlementService.isTochkaCancellationLifecycleStatus(status);
    }

    void setTochkaReasonUnlessRefundClaimed(CommonInvoicePaymentRef ref, String nextReason) {
        settlementService.setTochkaReasonUnlessRefundClaimed(ref, nextReason);
    }

    LocalDateTime tochkaRefundClaimedAt(String reason, LocalDateTime fallback) {
        String clean = normalize(reason);
        if (clean.startsWith(TOCHKA_REFUND_SUBMITTING_PREFIX)) {
            String encoded = clean.substring(TOCHKA_REFUND_SUBMITTING_PREFIX.length());
            int separator = encoded.indexOf(':');
            String epochMillis = separator < 0 ? encoded : encoded.substring(0, separator);
            try {
                return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(Long.parseLong(epochMillis)), ZoneId.systemDefault());
            } catch (RuntimeException ignored) {
                // Legacy or malformed claim remains at-most-once; its row timestamp is the fallback.
            }
        }
        return fallback;
    }

    PaymentOperation requireSingleTochkaCommonOperation(PaymentInfoResponse response, String operationId) {
        List<PaymentOperation> operations = response == null || response.data() == null || response.data().operations() == null ? List.of() : response.data().operations();
        if (operations.size() != 1) {
            throw new TochkaProviderException("Точка вернула неоднозначный ответ при сверке operationId " + maskPaymentId(operationId), false, null);
        }
        return operations.getFirst();
    }

    void finishTochkaReconciliation(PreparedTochkaReconciliation prepared, TochkaReconciliationObservation observation) {
        CommonInvoice invoice = lockedInvoice(prepared.invoiceId()).orElse(null);
        CommonInvoicePaymentRef ref = paymentRefRepository.findByIdForUpdate(prepared.refId()).orElse(null);
        if (invoice == null || ref == null || !Objects.equals(prepared.invoiceId(), paymentRefInvoiceId(ref)) || !PROVIDER_TOCHKA.equals(normalizedPaymentProvider(ref)) || !prepared.paymentLinkId().equals(providerOrderId(ref)) || !Objects.equals(prepared.amountKopecks(), ref.getAmountKopecks())) {
            return;
        }
        if (observation.failure() != null) {
            if (observation.refundAttempted()) {
                // The durable claim was committed before invoking Refund. Even a locally
                // classified failure is treated as potentially submitted: never issue POST again.
                ref.setStatus(PAYMENT_REF_CANCEL_PENDING);
                invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
                invoice.setNextReminderAt(null);
                invoice.setPaymentUrl(null);
                invoice.setLastError(limit("tochka_refund_outcome_unknown: исход возврата неизвестен; автоматический повтор " + "запрещён, дальнейшая обработка только через GET/ручную сверку (" + readableException(observation.failure()) + ")", 512));
            } else {
                setTochkaReasonUnlessRefundClaimed(ref, "tochka_reconciliation_failed:" + readableException(observation.failure()));
                if (prepared.cancellation() || isTochkaRefundSubmissionClaimed(ref.getReason())) {
                    invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
                    invoice.setNextReminderAt(null);
                    invoice.setPaymentUrl(null);
                }
                invoice.setLastError(limit("tochka_reconciliation_failed: " + readableException(observation.failure()), 512));
            }
            paymentRefRepository.save(ref);
            invoiceRepository.save(invoice);
            return;
        }
        if (observation.notFound()) {
            boolean cancellationLifecycle = prepared.cancellation() || isTochkaCancellationLifecycleStatus(paymentRefStatus(ref)) || isTochkaRefundSubmissionClaimed(ref.getReason());
            if (prepared.providerExpiresAt() != null && !prepared.providerExpiresAt().isAfter(LocalDateTime.now())) {
                // Absence from a complete bounded lookup is safe only after our requested TTL,
                // and is used solely for a never-bound ambiguous POST. A known operation still
                // requires an explicit provider terminal status.
                if (prepared.paymentId().isBlank()) {
                    ref.setStatus("EXPIRED");
                    ref.setProviderStatus("NOT_FOUND_AFTER_TTL");
                    ref.setReason("tochka_create_not_found_after_ttl");
                    invoice.setPaymentUrl(null);
                    invoice.setLastError(null);
                }
            } else if (cancellationLifecycle) {
                if (!PAYMENT_REF_CANCEL_FAILED_FINAL.equals(paymentRefStatus(ref))) {
                    ref.setStatus(PAYMENT_REF_CANCEL_PENDING);
                }
                setTochkaReasonUnlessRefundClaimed(ref, "tochka_cancel_recovery_not_found");
                invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
                invoice.setNextReminderAt(null);
                invoice.setPaymentUrl(null);
                invoice.setLastError("tochka_cancel_recovery_not_found: платёж Точки остаётся на ручной сверке");
            } else {
                ref.setStatus(PAYMENT_REF_INIT_CONFLICT);
                setTochkaReasonUnlessRefundClaimed(ref, "tochka_create_recovery_not_found");
                invoice.setPaymentUrl(null);
                invoice.setLastError("tochka_create_recovery_not_found: повторная сверка запланирована");
            }
            paymentRefRepository.save(ref);
            invoiceRepository.save(invoice);
            return;
        }
        PaymentOperation operation = observation.operation();
        MappedPayment mapped = observation.mapped();
        String operationId = normalize(operation.operationId());
        Optional<CommonInvoicePaymentRef> foreign = paymentRefRepository.findByProviderAndProviderPaymentId(PROVIDER_TOCHKA, operationId).filter(other -> !Objects.equals(other.getId(), ref.getId()));
        if (foreign.isPresent()) {
            quarantineTochkaInit(invoice, ref, "tochka_reconciliation_operation_collision");
            return;
        }
        if (!providerPaymentId(ref).isBlank() && !providerPaymentId(ref).equals(operationId)) {
            quarantineTochkaInit(invoice, ref, "tochka_reconciliation_operation_changed");
            return;
        }
        ref.setProviderPaymentId(limit(operationId, 64));
        ref.setProviderStatus(limit(mapped.providerStatus(), 32));
        String safeUrl = PaymentUrlPolicy.safe(operation.paymentLink(), PaymentUrlPolicy.Purpose.TOCHKA_PAYMENT);
        if (!safeUrl.isBlank()) {
            ref.setProviderPaymentUrl(safeUrl);
        }
        paymentRefRepository.save(ref);
        entityManager.flush();
        if (prepared.cancellation()) {
            applyTochkaCancellationObservation(invoice, ref, prepared, observation);
            return;
        }
        applyTochkaPaymentObservation(invoice, ref, mapped, safeUrl, "reconciliation");
    }

    void applyTochkaCancellationObservation(CommonInvoice invoice, CommonInvoicePaymentRef ref, PreparedTochkaReconciliation prepared, TochkaReconciliationObservation observation) {
        MappedPayment mapped = observation.mapped();
        String providerStatus = normalize(mapped.providerStatus()).toUpperCase(Locale.ROOT);
        if (mapped.status() == PaymentLinkStatus.EXPIRED || mapped.status() == PaymentLinkStatus.REFUNDED) {
            ref.setStatus(PAYMENT_REF_CANCELED);
            ref.setReason(limit("tochka_provider_terminal:" + providerStatus, 160));
            paymentRefRepository.save(ref);
            invoice.setPaymentUrl(null);
            if (normalize(invoice.getLastError()).startsWith("tochka_")) {
                invoice.setLastError(null);
            }
            invoiceRepository.save(invoice);
            return;
        }
        if (mapped.status() == PaymentLinkStatus.PARTIAL_REFUNDED) {
            ref.setStatus(PAYMENT_REF_CANCEL_FAILED_FINAL);
            setTochkaReasonUnlessRefundClaimed(ref, "tochka_partial_refund_requires_attention");
            paymentRefRepository.save(ref);
            invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
            invoice.setNextReminderAt(null);
            invoice.setPaymentUrl(null);
            invoice.setLastError(PAYMENT_CANCEL_FAILED_FINAL + ": частичный возврат Точки требует ручной проверки банка");
            invoiceRepository.save(invoice);
            return;
        }
        if (observation.refundSubmitted()) {
            ref.setStatus(PAYMENT_REF_CANCEL_PENDING);
            paymentRefRepository.save(ref);
            invoice.setPaymentUrl(null);
            invoice.setLastError("tochka_refund_requested: ожидается подтверждение возврата");
            invoiceRepository.save(invoice);
            return;
        }
        if (mapped.status() == PaymentLinkStatus.CONFIRMED && prepared.refundSubmissionClaimed()) {
            boolean timedOut = prepared.refundClaimedAt() != null && !prepared.refundClaimedAt().plus(PAYMENT_REF_CANCELING_TIMEOUT).isAfter(LocalDateTime.now());
            ref.setStatus(timedOut ? PAYMENT_REF_CANCEL_FAILED_FINAL : PAYMENT_REF_CANCEL_PENDING);
            paymentRefRepository.save(ref);
            invoice.setPaymentUrl(null);
            if (timedOut) {
                invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
                invoice.setNextReminderAt(null);
                invoice.setLastError(PAYMENT_CANCEL_FAILED_FINAL + ": подтверждение возврата Точки не получено; проверьте банк вручную");
            }
            invoiceRepository.save(invoice);
            return;
        }
        if ("ON-REFUND".equals(providerStatus)) {
            ref.setStatus(PAYMENT_REF_CANCEL_PENDING);
            if (!isTochkaRefundSubmissionClaimed(ref.getReason())) {
                // A refund may have been initiated manually at the bank. Treat the observed
                // provider state as an at-most-once claim so a later APPROVED snapshot cannot
                // trigger a second automatic Refund.
                ref.setReason("tochka_refund_observed_external:" + System.currentTimeMillis());
            }
            paymentRefRepository.save(ref);
            invoice.setPaymentUrl(null);
            invoiceRepository.save(invoice);
            return;
        }
        // CREATED cannot be canceled in Tochka API. It remains blocking until GET reports EXPIRED.
        if (mapped.status() == PaymentLinkStatus.INITIATED) {
            if (!PAYMENT_REF_CANCEL_FAILED_FINAL.equals(paymentRefStatus(ref))) {
                ref.setStatus(PAYMENT_REF_CANCEL_PENDING);
            }
            setTochkaReasonUnlessRefundClaimed(ref, "tochka_cancel_waiting_provider_expiry");
            paymentRefRepository.save(ref);
            invoice.setPaymentUrl(null);
            invoice.setLastError("tochka_cancel_waiting_provider_expiry: смена маршрута заблокирована до EXPIRED");
            invoiceRepository.save(invoice);
            return;
        }
        ref.setStatus(PAYMENT_REF_CANCEL_FAILED_FINAL);
        setTochkaReasonUnlessRefundClaimed(ref, "tochka_cancel_ambiguous:" + providerStatus);
        paymentRefRepository.save(ref);
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setNextReminderAt(null);
        invoice.setPaymentUrl(null);
        invoice.setLastError(limit(PAYMENT_CANCEL_FAILED_FINAL + ": статус Точки " + providerStatus + " требует ручной проверки банка", 512));
        invoiceRepository.save(invoice);
    }

    String maskPaymentId(String paymentId) {
        return CommonInvoicePaymentIdentity.maskPaymentId(paymentId);
    }

    void quarantineTochkaInit(CommonInvoice invoice, CommonInvoicePaymentRef ref, String reason) {
        settlementService.quarantineTochkaInit(invoice, ref, reason);
    }

    List<TochkaPaymentMode> parseTochkaPaymentModes(String value) {
        return settlementService.parseTochkaPaymentModes(value);
    }

    TochkaPaymentMode observedTochkaPaymentMode(String paymentType, String providerStatus, List<TochkaPaymentMode> allowedModes) {
        return settlementService.observedTochkaPaymentMode(paymentType, providerStatus, allowedModes);
    }

    void applyTochkaPaymentObservation(CommonInvoice invoice, CommonInvoicePaymentRef ref, MappedPayment mapped, String observedPaymentUrl, String source) {
        settlementService.applyTochkaPaymentObservation(invoice, ref, mapped, observedPaymentUrl, source);
    }

    Optional<CommonInvoice> lockedInvoice(Long invoiceId) {
        return settlementService.lockedInvoice(invoiceId);
    }

    <T> T writeTransaction(Supplier<T> action) {
        return settlementService.writeTransaction(action);
    }

    String paymentRefStatus(CommonInvoicePaymentRef ref) {
        return settlementService.paymentRefStatus(ref);
    }

    String normalizedPaymentProvider(CommonInvoicePaymentRef ref) {
        return settlementService.normalizedPaymentProvider(ref);
    }

    String providerOrderId(CommonInvoicePaymentRef ref) {
        return CommonInvoicePaymentIdentity.providerOrderId(ref);
    }

    String providerPaymentId(CommonInvoicePaymentRef ref) {
        return settlementService.providerPaymentId(ref);
    }

    String providerMerchantId(CommonInvoicePaymentRef ref) {
        return CommonInvoicePaymentIdentity.providerMerchantId(ref);
    }

    String randomToken() {
        return CommonInvoicePaymentIdentity.randomToken();
    }

    Long paymentRefInvoiceId(CommonInvoicePaymentRef ref) {
        return settlementService.paymentRefInvoiceId(ref);
    }

    int cancelAttempts(CommonInvoicePaymentRef ref) {
        return invoiceCancellation.cancelAttempts(ref);
    }

    String normalize(String value) {
        return settlementService.normalize(value);
    }

    String limit(String value, int max) {
        return settlementService.limit(value, max);
    }

    String readableException(RuntimeException e) {
        return settlementService.readableException(e);
    }

    record PreparedTochkaReconciliation(Long refId, Long invoiceId, String refStatus, TochkaPaymentProfile runtimeProfile, List<TochkaPaymentMode> paymentModes, String paymentLinkId, String paymentId, long amountKopecks, LocalDateTime providerExpiresAt, LocalDateTime createdAt, LocalDateTime updatedAt, boolean cancellation, boolean refundSubmissionClaimed, LocalDateTime refundClaimedAt) {
    }

    record TochkaReconciliationObservation(boolean notFound, PaymentOperation operation, MappedPayment mapped, boolean refundAttempted, boolean refundSubmitted, RuntimeException failure) {

        private static TochkaReconciliationObservation missing() {
            return new TochkaReconciliationObservation(true, null, null, false, false, null);
        }

        private static TochkaReconciliationObservation observed(PaymentOperation operation, MappedPayment mapped) {
            return new TochkaReconciliationObservation(false, operation, mapped, false, false, null);
        }

        private TochkaReconciliationObservation withRefundAttempt(boolean submitted) {
            return new TochkaReconciliationObservation(notFound, operation, mapped, true, submitted, null);
        }

        private TochkaReconciliationObservation withRefundFailure(RuntimeException failure) {
            return new TochkaReconciliationObservation(notFound, operation, mapped, true, false, failure);
        }

        private static TochkaReconciliationObservation failed(RuntimeException failure) {
            return new TochkaReconciliationObservation(false, null, null, false, false, failure);
        }
    }
}
