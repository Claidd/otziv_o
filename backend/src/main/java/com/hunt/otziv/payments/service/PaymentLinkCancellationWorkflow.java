package com.hunt.otziv.payments.service;

import static com.hunt.otziv.payments.service.PaymentLinkPresenter.*;
import static com.hunt.otziv.payments.service.CommonInvoiceRouteSelector.*;
import static com.hunt.otziv.payments.service.PaymentBankObservationService.*;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentShadowService;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentTargetAccessPolicy;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.payments.dto.AdminPaymentLinkResponse;
import com.hunt.otziv.payments.dto.TbankCancelCommand;
import com.hunt.otziv.payments.dto.TbankCancelResponse;
import com.hunt.otziv.payments.dto.TbankPaymentProfile;
import com.hunt.otziv.payments.tochka.dto.TochkaApiModels.RefundResponse;
import com.hunt.otziv.payments.tochka.dto.TochkaPaymentProfile;
import com.hunt.otziv.payments.tochka.dto.TochkaRefundCommand;
import com.hunt.otziv.payments.tochka.model.TochkaPaymentMode;
import com.hunt.otziv.payments.tochka.service.TochkaClient;
import com.hunt.otziv.payments.tochka.service.TochkaPaymentProfileResolver;
import com.hunt.otziv.payments.tochka.service.TochkaProviderException;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.model.PaymentMethod;
import com.hunt.otziv.payments.model.PaymentProfile;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import static com.hunt.otziv.logs.util.LogMasking.maskPaymentId;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

/**
 * Owns standalone cancellation/refund claims, immutable provider bindings and recovery transitions.
 * Provider calls run without a database transaction; prepare/apply use short independent transactions.
 * Shared manual-settlement and webhook transitions join their caller's existing locked transaction.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentLinkCancellationWorkflow {

    private final PaymentLinkPresenter paymentPresenter;

    private final CommonInvoiceRouteSelector commonInvoiceRouteSelector;

    private final PaymentBankObservationService bankObservations;

    static final Set<PaymentLinkStatus> REFUNDED_STATUSES = Set.of(PaymentLinkStatus.REVERSED, PaymentLinkStatus.PARTIAL_REVERSED, PaymentLinkStatus.REFUNDED, PaymentLinkStatus.PARTIAL_REFUNDED, PaymentLinkStatus.CANCELED);

    static final Set<PaymentLinkStatus> CONFIRMED_LIKE_BANK_STATUSES = Set.of(PaymentLinkStatus.CONFIRMED, PaymentLinkStatus.TEST_CONFIRMED, PaymentLinkStatus.AMOUNT_MISMATCH);

    static final Set<PaymentLinkStatus> REFUND_OR_REVERSAL_BANK_STATUSES = Set.of(PaymentLinkStatus.REVERSED, PaymentLinkStatus.PARTIAL_REVERSED, PaymentLinkStatus.REFUNDED, PaymentLinkStatus.PARTIAL_REFUNDED);

    static final Duration BANK_CANCEL_LEASE = Duration.ofMinutes(5);

    static final Duration BANK_CANCEL_WATCH = Duration.ofHours(24);

    static final String BANK_CANCEL_IN_PROGRESS_PREFIX = "bank_cancel_in_progress:";

    static final String BANK_CANCEL_AMBIGUOUS_PREFIX = "bank_cancel_ambiguous:";

    private final PaymentLinkRepository paymentLinkRepository;

    private final OrderRepository orderRepository;

    private final TbankClient tbankClient;

    private final TochkaPaymentProfileResolver tochkaPaymentProfileResolver;

    private final TochkaClient tochkaClient;

    private final ContractorPaymentShadowService contractorPaymentShadowService;

    private final PaymentLinkReturnOutboxService paymentLinkReturnOutboxService;

    private final ContractorPaymentTargetAccessPolicy contractorPaymentTargetAccessPolicy;

    private final PaymentLinkTransactionExecutor transactionExecutor;

    private boolean hasBankInitReservation(PaymentLink link) {
        return link != null && !normalize(link.getBankInitNonce()).isBlank();
    }

    private boolean isTochkaPaymentLink(PaymentLink link) {
        return paymentPresenter.isTochkaPaymentLink(link);
    }

    private TochkaPaymentMode expectedTochkaMode(PaymentMethod paymentMethod) {
        return bankObservations.expectedTochkaMode(paymentMethod);
    }

    private boolean isTochkaRefundStatus(PaymentLinkStatus status) {
        return status == PaymentLinkStatus.REFUNDED || status == PaymentLinkStatus.PARTIAL_REFUNDED;
    }

    private boolean hasOrderBinding(PaymentLink link, Long orderId) {
        return link != null && orderId != null && link.getOrder() != null && orderId.equals(link.getOrder().getId());
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AdminPaymentLinkResponse cancel(Long linkId) {
        contractorPaymentTargetAccessPolicy.requireCanManagePaymentLink(linkId);
        PaymentLink snapshot = paymentLinkRepository.findByIdWithOrder(linkId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежная ссылка не найдена"));
        Long orderId = snapshot.getOrder() == null ? null : snapshot.getOrder().getId();
        CancelReservation reservation = transactionExecutor.requiredNoRollback(() -> reserveCancelLocked(linkId, orderId));
        if (reservation.tochkaProfile() != null) {
            try {
                RefundResponse response = tochkaClient.refund(reservation.tochkaProfile(), new TochkaRefundCommand(reservation.paymentId(), reservation.amountKopecks()));
                return transactionExecutor.requiredNoRollback(() -> applyTochkaRefundAccepted(reservation, response));
            } catch (RuntimeException failure) {
                recordTochkaRefundFailure(reservation, failure);
                log.warn("Tochka refund request failed: linkId={}, orderId={}, operationId={}, status={}, reason={}", reservation.linkId(), reservation.orderId(), maskPaymentId(reservation.paymentId()), failure instanceof ResponseStatusException statusException ? statusException.getStatusCode() : HttpStatus.BAD_GATEWAY, tochkaProviderFailureReason(failure));
                throw failure;
            }
        }
        PaymentLinkStatus incoming;
        try {
            TbankCancelResponse response = tbankClient.cancel(reservation.runtimeProfile(), new TbankCancelCommand(reservation.paymentId(), reservation.amountKopecks()));
            validateCancelResponse(reservation, response);
            incoming = statusAfterCancel(response.status());
        } catch (RuntimeException failure) {
            recordAmbiguousCancelFailure(reservation, failure);
            log.warn("T-Bank Cancel outcome is ambiguous: linkId={}, orderId={}, paymentId={}, status={}, reason={}", reservation.linkId(), reservation.orderId(), maskPaymentId(reservation.paymentId()), failure instanceof ResponseStatusException statusException ? statusException.getStatusCode() : HttpStatus.BAD_GATEWAY, providerFailureReason(failure));
            throw failure;
        }
        PaymentLinkStatus observedStatus = incoming;
        return transactionExecutor.requiredNoRollback(() -> applyCancelObservation(reservation, observedStatus));
    }

    private CancelReservation reserveCancelLocked(Long linkId, Long orderId) {
        if (orderId == null || orderRepository.findByIdForCounterUpdate(orderId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Заказ платежной ссылки изменился до возврата");
        }
        PaymentLink link = paymentLinkRepository.findByIdForUpdate(linkId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежная ссылка не найдена"));
        if (!hasOrderBinding(link, orderId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Платежная ссылка сменила заказ до возврата");
        }
        if (hasBankCancelReservation(link)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Предыдущий возврат еще выполняется или требует сверки");
        }
        if (link.getBankCancelOriginStatus() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Предыдущий возврат принят банком и ожидает финальной сверки");
        }
        if (!isRefundable(link)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Платеж не готов к возврату через банк");
        }
        if (hasBankInitReservation(link)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Инициализация платежа еще не завершена");
        }
        return reserveBankCancel(link, orderId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    CancelReservation reserveBankCancel(PaymentLink link, Long orderId) {
        PaymentProfile profile = resolvePaymentProfile(link);
        TochkaPaymentProfile tochkaProfile = isTochkaPaymentLink(link) ? tochkaPaymentProfileResolver.resolveForExistingPayment(profile) : null;
        TbankPaymentProfile runtimeProfile = tochkaProfile == null ? runtimeProfileForLink(profile, link) : null;
        if (tochkaProfile != null) {
            expectedTochkaMode(link.getPaymentMethod());
            if (!normalize(link.getTbankTerminalKey()).equals(tochkaProfile.merchantId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "MerchantId платежа Точки не совпадает с закрепленным профилем");
            }
            if ((link.getStatus() != PaymentLinkStatus.CONFIRMED && link.getStatus() != PaymentLinkStatus.TEST_CONFIRMED && link.getStatus() != PaymentLinkStatus.AMOUNT_MISMATCH) || !"APPROVED".equals(normalize(link.getProviderTerminalStatus()))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Возврат Точки разрешен только для платежа с подтвержденным статусом APPROVED");
            }
        }
        PaymentLinkStatus originalStatus = link.getStatus();
        String nonce = UUID.randomUUID().toString();
        link.setBankCancelNonce(nonce);
        link.setBankCancelLeaseUntil(LocalDateTime.now().plus(BANK_CANCEL_LEASE));
        link.setBankCancelOriginStatus(originalStatus);
        link.setBankCancelOriginError(link.getLastError());
        link.setStatus(PaymentLinkStatus.NEEDS_RECONCILIATION);
        link.setBankReconciliationAttemptedAt(null);
        link.setLastError(limit(BANK_CANCEL_IN_PROGRESS_PREFIX + " previous_status=" + originalStatus.name(), 512));
        paymentLinkRepository.save(link);
        return new CancelReservation(link.getId(), orderId, originalStatus, nonce, normalize(link.getTbankPaymentId()), normalize(link.getTbankOrderId()), link.getAmountKopecks(), normalize(link.getTbankTerminalKey()), link.getPaymentProfile() == null ? null : link.getPaymentProfile().getId(), link.getPaymentMethod(), runtimeProfile, tochkaProfile);
    }

    private AdminPaymentLinkResponse applyTochkaRefundAccepted(CancelReservation reservation, RefundResponse response) {
        PaymentLink link = lockCancelBinding(reservation);
        if (link == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Состояние платежа изменилось во время возврата Точки; требуется сверка");
        }
        if (!reservation.nonce().equals(normalize(link.getBankCancelNonce()))) {
            if (normalize(link.getBankCancelNonce()).isBlank() && isTochkaRefundStatus(link.getStatus())) {
                return toAdminResponse(link);
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Состояние платежа изменилось во время возврата Точки; требуется сверка");
        }
        if (response == null || response.data() == null || !Boolean.TRUE.equals(response.data().isRefund()) || !reservation.paymentId().equals(normalize(response.data().operationId())) || response.data().amount() == null || BigDecimal.valueOf(reservation.amountKopecks(), 2).compareTo(response.data().amount()) != 0) {
            quarantineAmbiguousCancel(link, "tochka_refund_acceptance_identity_mismatch");
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Точка вернула несогласованное подтверждение возврата");
        }
        clearBankCancelAttempt(link);
        link.setBankCancelLeaseUntil(LocalDateTime.now().plus(BANK_CANCEL_WATCH));
        link.setStatus(PaymentLinkStatus.NEEDS_RECONCILIATION);
        link.setBankReconciliationAttemptedAt(null);
        link.setLastError(limit(BANK_CANCEL_IN_PROGRESS_PREFIX + " tochka_refund_accepted; refund_order_id=" + normalize(response.data().orderId()), 512));
        paymentLinkRepository.save(link);
        return toAdminResponse(link);
    }

    private void recordTochkaRefundFailure(CancelReservation reservation, RuntimeException failure) {
        transactionExecutor.required(() -> {
            PaymentLink link = lockCancelBinding(reservation);
            if (link == null || !reservation.nonce().equals(normalize(link.getBankCancelNonce()))) {
                return null;
            }
            boolean outcomeUnknown = !(failure instanceof TochkaProviderException providerFailure) || providerFailure.isOutcomeUnknown();
            if (outcomeUnknown) {
                quarantineAmbiguousCancel(link, "tochka_refund_failed: " + tochkaProviderFailureReason(failure));
                return null;
            }
            link.setStatus(reservation.status());
            link.setLastError(link.getBankCancelOriginError());
            clearBankCancelContext(link);
            link.setBankReconciliationAttemptedAt(null);
            paymentLinkRepository.save(link);
            return null;
        });
    }

    @Transactional(propagation = Propagation.MANDATORY)
    AdminPaymentLinkResponse applyCancelObservation(CancelReservation reservation, PaymentLinkStatus incoming) {
        PaymentLink link = lockCancelBinding(reservation);
        if (link == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Состояние платежа изменилось во время возврата; требуется повторная сверка");
        }
        String currentNonce = normalize(link.getBankCancelNonce());
        boolean ownsReservation = reservation.nonce().equals(currentNonce);
        if (!ownsReservation) {
            if (!currentNonce.isBlank()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Начат другой возврат; ответ предыдущего запроса не применен");
            }
            if (link.getStatus() == incoming || link.getStatus() == PaymentLinkStatus.CANCELED) {
                if (incoming == PaymentLinkStatus.CANCELED && link.getStatus() == PaymentLinkStatus.CANCELED) {
                    link.setProviderTerminalStatus("CANCELED");
                    paymentLinkRepository.save(link);
                }
                return toAdminResponse(link);
            }
            if (REFUND_OR_REVERSAL_BANK_STATUSES.contains(link.getStatus())) {
                if (incoming == PaymentLinkStatus.CANCELED || !isAllowedRefundProgress(link.getStatus(), incoming.name())) {
                    return toAdminResponse(link);
                }
            }
            boolean delayedCanceledResolution = incoming == PaymentLinkStatus.CANCELED && link.getBankCancelOriginStatus() != null;
            if (!REFUND_OR_REVERSAL_BANK_STATUSES.contains(incoming) && !delayedCanceledResolution) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Ответ возврата устарел; сохранено более новое состояние банка");
            }
        }
        PaymentLinkStatus current = link.getStatus() == PaymentLinkStatus.NEEDS_RECONCILIATION ? reservation.status() : link.getStatus();
        PaymentLinkStatus merged = mergeCancelObservation(reservation.status(), current, incoming);
        if (merged == null) {
            quarantineAmbiguousCancel(link, "payment_state_changed_before_cancel_result");
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Статус платежа изменился во время возврата; платеж оставлен на сверке");
        }
        link.setStatus(merged);
        if (merged == PaymentLinkStatus.CANCELED && incoming == PaymentLinkStatus.CANCELED) {
            link.setProviderTerminalStatus("CANCELED");
        }
        clearBankCancelContext(link);
        link.setBankReconciliationAttemptedAt(null);
        link.setLastError(null);
        paymentLinkRepository.save(link);
        if (REFUNDED_STATUSES.contains(merged)) {
            paymentLinkReturnOutboxService.enqueue(link);
            reconcileContractorPaymentRouteAfterCommit(link.getId());
        }
        return toAdminResponse(link);
    }

    private PaymentLink lockCancelBinding(CancelReservation reservation) {
        if (reservation.orderId() == null || orderRepository.findByIdForCounterUpdate(reservation.orderId()).isEmpty()) {
            return null;
        }
        PaymentLink link = paymentLinkRepository.findByIdForUpdate(reservation.linkId()).orElse(null);
        if (!hasOrderBinding(link, reservation.orderId()) || !normalize(link.getTbankPaymentId()).equals(reservation.paymentId()) || !normalize(link.getTbankOrderId()).equals(reservation.tbankOrderId()) || link.getAmountKopecks() != reservation.amountKopecks() || !normalize(link.getTbankTerminalKey()).equals(reservation.terminalKey()) || !Objects.equals(link.getPaymentProfile() == null ? null : link.getPaymentProfile().getId(), reservation.profileId()) || link.getPaymentMethod() != reservation.paymentMethod()) {
            return null;
        }
        return link;
    }

    void recordAmbiguousCancelFailure(CancelReservation reservation, RuntimeException failure) {
        transactionExecutor.required(() -> {
            PaymentLink link = lockCancelBinding(reservation);
            if (link != null && reservation.nonce().equals(normalize(link.getBankCancelNonce()))) {
                quarantineAmbiguousCancel(link, providerFailureReason(failure));
            }
            return null;
        });
    }

    private void quarantineAmbiguousCancel(PaymentLink link, String reason) {
        link.setStatus(PaymentLinkStatus.NEEDS_RECONCILIATION);
        link.setBankReconciliationAttemptedAt(null);
        link.setLastError(limit(BANK_CANCEL_AMBIGUOUS_PREFIX + " " + normalize(reason), 512));
        paymentLinkRepository.save(link);
    }

    private void clearBankCancelAttempt(PaymentLink link) {
        link.setBankCancelNonce(null);
        link.setBankCancelLeaseUntil(null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void clearBankCancelContext(PaymentLink link) {
        clearBankCancelAttempt(link);
        link.setBankCancelOriginStatus(null);
        link.setBankCancelOriginError(null);
    }

    private boolean hasBankCancelReservation(PaymentLink link) {
        return paymentPresenter.hasBankCancelReservation(link);
    }

    /**
     * Merges an explicit Cancel result with webhooks that arrived while the
     * provider request was in flight. A final refund may advance a concurrent
     * confirmation/partial refund, while a delayed less-specific result never
     * overwrites a more advanced refund state.
     */
    private PaymentLinkStatus mergeCancelObservation(PaymentLinkStatus snapshot, PaymentLinkStatus current, PaymentLinkStatus incoming) {
        if (current == incoming) {
            return current;
        }
        if (current == snapshot) {
            return incoming;
        }
        if (REFUND_OR_REVERSAL_BANK_STATUSES.contains(current)) {
            if (incoming == PaymentLinkStatus.CANCELED) {
                return current;
            }
            return isAllowedRefundProgress(current, incoming.name()) ? incoming : current;
        }
        if (CONFIRMED_LIKE_BANK_STATUSES.contains(current) && REFUND_OR_REVERSAL_BANK_STATUSES.contains(incoming)) {
            return incoming;
        }
        if (current == PaymentLinkStatus.CANCELED) {
            return current;
        }
        return null;
    }

    /**
     * The source row is still locked by the payment mutation, while the
     * contractor reconciler starts by taking the same source lock. Run it only
     * after commit so SHADOW and LIVE allocation states are updated without a
     * self-deadlock. The durable PaymentLink state remains available to the
     * periodic claim worker if this best-effort fast path fails.
     */
    void reconcileContractorPaymentRouteAfterCommit(Long paymentLinkId) {
        if (paymentLinkId == null) {
            return;
        }
        Runnable reconcile = () -> {
            try {
                contractorPaymentShadowService.reconcilePaymentLinkId(paymentLinkId);
            } catch (RuntimeException exception) {
                log.error("Не удалось сразу сверить назначение платежной ссылки linkId={}, code={}", paymentLinkId, exception.getClass().getSimpleName());
            }
        };
        if (TransactionSynchronizationManager.isActualTransactionActive() && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {

                @Override
                public void afterCommit() {
                    reconcile.run();
                }
            });
            return;
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            log.warn("Пропущена немедленная сверка платежной ссылки без transaction synchronization linkId={}", paymentLinkId);
            return;
        }
        reconcile.run();
    }

    private String providerFailureReason(RuntimeException failure) {
        if (failure instanceof ResponseStatusException statusException) {
            String reason = normalize(statusException.getReason());
            return reason.isBlank() ? "T-Bank provider call failed" : reason;
        }
        return failure == null || normalize(failure.getMessage()).isBlank() ? "T-Bank provider call failed" : normalize(failure.getMessage());
    }

    private String tochkaProviderFailureReason(RuntimeException failure) {
        return bankObservations.tochkaProviderFailureReason(failure);
    }

    /**
     * A non-terminal observation received while Cancel is still in flight
     * cannot prove that the refund failed. Keep the durable quarantine until
     * the request finishes or its lease expires. Explicit refund/reversal
     * states are safe to apply immediately.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    boolean holdActiveCancelQuarantine(PaymentLink link, String incomingStatus) {
        String status = normalize(incomingStatus).toUpperCase(Locale.ROOT);
        boolean initiatedCancelAuthoritativeState = link != null && link.getBankCancelOriginStatus() == PaymentLinkStatus.INITIATED && ("CONFIRMED".equals(status) || "AUTHORIZED".equals(status) || "REJECTED".equals(status) || "DEADLINE_EXPIRED".equals(status));
        if (!hasBankCancelReservation(link) || isExplicitCancelTerminalStatus(incomingStatus) || initiatedCancelAuthoritativeState || link.getBankCancelLeaseUntil() == null || !link.getBankCancelLeaseUntil().isAfter(LocalDateTime.now())) {
            return false;
        }
        link.setStatus(PaymentLinkStatus.NEEDS_RECONCILIATION);
        link.setLastError(limit(BANK_CANCEL_IN_PROGRESS_PREFIX + " awaiting_explicit_bank_result; observed=" + normalize(incomingStatus).toUpperCase(), 512));
        return true;
    }

    /**
     * Restores a previously paid local state without replaying order-payment
     * side effects when GetState merely confirms that an ambiguous Cancel did
     * not change the bank payment. Regressive or unknown observations remain
     * quarantined until the bank reports a conclusive state.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    boolean applyCancelRecoveryObservationIfNeeded(PaymentLink link, String incomingStatus) {
        PaymentLinkStatus origin = link.getBankCancelOriginStatus();
        if (origin == null) {
            return false;
        }
        String status = normalize(incomingStatus).toUpperCase();
        if (CONFIRMED_LIKE_BANK_STATUSES.contains(origin)) {
            if ("CONFIRMED".equals(status)) {
                restoreCancelOriginAndContinueWatch(link, origin);
                return true;
            }
            if (isExplicitCancelTerminalStatus(status)) {
                return false;
            }
            keepCancelRecoveryQuarantined(link, status);
            return true;
        }
        if (origin == PaymentLinkStatus.AUTHORIZED) {
            if ("AUTHORIZED".equals(status)) {
                restoreCancelOriginAndContinueWatch(link, origin);
                return true;
            }
            if ("CONFIRMED".equals(status) || isExplicitCancelTerminalStatus(status) || "REJECTED".equals(status) || "DEADLINE_EXPIRED".equals(status)) {
                return false;
            }
            keepCancelRecoveryQuarantined(link, status);
            return true;
        }
        if (origin == PaymentLinkStatus.INITIATED) {
            // A manual-card settlement may cancel a provider NEW session. An
            // explicit terminal observation is authoritative and must release
            // the quarantine. CONFIRMED is applied by the regular bank path,
            // which closes the order from provider evidence and therefore
            // prevents a second manual credit. NEW/unknown remains ambiguous.
            if ("CANCELED".equals(status) || "REJECTED".equals(status) || "DEADLINE_EXPIRED".equals(status) || "CONFIRMED".equals(status) || "AUTHORIZED".equals(status) || isRefundOrReversalBankStatus(status)) {
                return false;
            }
            keepCancelRecoveryQuarantined(link, status);
            return true;
        }
        keepCancelRecoveryQuarantined(link, status);
        return true;
    }

    private void restoreCancelOriginAndContinueWatch(PaymentLink link, PaymentLinkStatus origin) {
        link.setStatus(origin);
        link.setLastError(link.getBankCancelOriginError());
        LocalDateTime now = LocalDateTime.now();
        if (hasBankCancelReservation(link) || link.getBankCancelLeaseUntil() == null) {
            link.setBankCancelNonce(null);
            link.setBankCancelLeaseUntil(now.plus(BANK_CANCEL_WATCH));
            return;
        }
        if (!link.getBankCancelLeaseUntil().isAfter(now)) {
            clearBankCancelContext(link);
        }
    }

    private void keepCancelRecoveryQuarantined(PaymentLink link, String observedStatus) {
        link.setStatus(PaymentLinkStatus.NEEDS_RECONCILIATION);
        link.setLastError(limit(BANK_CANCEL_AMBIGUOUS_PREFIX + " inconclusive_bank_status=" + normalize(observedStatus), 512));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void clearResolvedCancelReservation(PaymentLink link, String incomingStatus) {
        if (!hasBankCancelReservation(link) && link.getBankCancelOriginStatus() == null) {
            return;
        }
        String status = normalize(incomingStatus).toUpperCase();
        if (isExplicitCancelTerminalStatus(status) || "REJECTED".equals(status) || "DEADLINE_EXPIRED".equals(status)) {
            clearBankCancelContext(link);
            return;
        }
        if ("CONFIRMED".equals(status) || "AUTHORIZED".equals(status)) {
            link.setBankCancelOriginStatus(link.getStatus());
            link.setBankCancelOriginError(link.getLastError());
            if (hasBankCancelReservation(link) || link.getBankCancelLeaseUntil() == null) {
                link.setBankCancelNonce(null);
                link.setBankCancelLeaseUntil(LocalDateTime.now().plus(BANK_CANCEL_WATCH));
            }
        }
    }

    private boolean isExplicitCancelTerminalStatus(String status) {
        String normalizedStatus = normalize(status).toUpperCase();
        return "CANCELED".equals(normalizedStatus) || isRefundOrReversalBankStatus(normalizedStatus);
    }

    boolean isRefundOrReversalBankStatus(String status) {
        return "REVERSED".equals(status) || "PARTIAL_REVERSED".equals(status) || "REFUNDED".equals(status) || "PARTIAL_REFUNDED".equals(status);
    }

    boolean isAllowedRefundProgress(PaymentLinkStatus current, String incoming) {
        if (current.name().equals(incoming)) {
            return true;
        }
        return switch(current) {
            case PARTIAL_REVERSED ->
                "REVERSED".equals(incoming) || "PARTIAL_REFUNDED".equals(incoming) || "REFUNDED".equals(incoming);
            case REVERSED ->
                "PARTIAL_REFUNDED".equals(incoming) || "REFUNDED".equals(incoming);
            case PARTIAL_REFUNDED ->
                "REFUNDED".equals(incoming);
            case REFUNDED ->
                false;
            default ->
                false;
        };
    }

    void validateCancelResponse(CancelReservation reservation, TbankCancelResponse response) {
        if (response == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Т-Банк вернул пустой ответ на Cancel");
        }
        String errorCode = normalize(response.errorCode());
        if (!response.success() || (!errorCode.isBlank() && !"0".equals(errorCode))) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, response.errorText());
        }
        String responseTerminal = normalize(response.terminalKey());
        if (!responseTerminal.isBlank() && !responseTerminal.equals(normalize(reservation.runtimeProfile().terminalKey()))) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "TerminalKey Cancel не совпадает с платежной ссылкой");
        }
        String responsePaymentId = normalize(response.paymentId());
        if (!responsePaymentId.isBlank() && !responsePaymentId.equals(reservation.paymentId())) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "PaymentId Cancel не совпадает с платежной ссылкой");
        }
        String responseOrderId = normalize(response.orderId());
        String linkOrderId = reservation.tbankOrderId();
        if (!responseOrderId.isBlank() && !linkOrderId.isBlank() && !responseOrderId.equals(linkOrderId)) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "OrderId Cancel не совпадает с платежной ссылкой");
        }
        if (response.amount() != null && response.amount() != reservation.amountKopecks()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Сумма Cancel не совпадает с платежной ссылкой");
        }
    }

    private AdminPaymentLinkResponse toAdminResponse(PaymentLink link) {
        return paymentPresenter.toAdminResponse(link);
    }

    private boolean isRefundable(PaymentLink link) {
        return paymentPresenter.isRefundable(link);
    }

    private PaymentProfile resolvePaymentProfile(PaymentLink link) {
        return bankObservations.resolvePaymentProfile(link);
    }

    private TbankPaymentProfile runtimeProfileForLink(PaymentProfile profile, PaymentLink link) {
        return bankObservations.runtimeProfileForLink(profile, link);
    }

    PaymentLinkStatus statusAfterCancel(String status) {
        return switch(normalize(status).toUpperCase()) {
            case "REFUNDED" ->
                PaymentLinkStatus.REFUNDED;
            case "PARTIAL_REFUNDED" ->
                PaymentLinkStatus.PARTIAL_REFUNDED;
            case "REVERSED" ->
                PaymentLinkStatus.REVERSED;
            case "PARTIAL_REVERSED" ->
                PaymentLinkStatus.PARTIAL_REVERSED;
            case "CANCELED" ->
                PaymentLinkStatus.CANCELED;
            default ->
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Т-Банк вернул неподтвержденный статус возврата");
        };
    }

    private String normalize(String value) {
        return paymentPresenter.normalize(value);
    }

    private String limit(String value, int maxLength) {
        return commonInvoiceRouteSelector.limit(value, maxLength);
    }

    record CancelReservation(Long linkId, Long orderId, PaymentLinkStatus status, String nonce, String paymentId, String tbankOrderId, long amountKopecks, String terminalKey, Long profileId, PaymentMethod paymentMethod, TbankPaymentProfile runtimeProfile, TochkaPaymentProfile tochkaProfile) {
    }
}
