package com.hunt.otziv.payments.service;

import com.hunt.otziv.payments.dto.TbankGetStateResponse;
import com.hunt.otziv.payments.dto.TbankPaymentProfile;
import com.hunt.otziv.payments.tochka.dto.TochkaPaymentProfile;
import com.hunt.otziv.payments.tochka.service.TochkaPaymentOperationMapper.MappedPayment;
import com.hunt.otziv.payments.tochka.service.TochkaPaymentProfileResolver;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.model.PaymentProfile;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static com.hunt.otziv.payments.service.PaymentLinkCancellationWorkflow.BANK_CANCEL_IN_PROGRESS_PREFIX;
import static com.hunt.otziv.payments.service.PaymentLinkCancellationWorkflow.CONFIRMED_LIKE_BANK_STATUSES;
import static com.hunt.otziv.payments.service.PaymentBankObservationService.BankStateObservation;
import static com.hunt.otziv.payments.service.PaymentBankObservationService.ProviderStateObservation;
import static com.hunt.otziv.payments.service.PaymentBankObservationService.TochkaStateObservation;

/**
 * Applies already observed provider states under the caller's canonical order/link
 * locks. It performs no network I/O and joins the caller's transaction policy.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BankObservationApplicationService {

    private final PaymentLinkSettlementService settlementService;

    private final PaymentLinkLifecycleService lifecycleService;

    private final PaymentLinkCancellationWorkflow cancellationWorkflow;

    private final PaymentLinkPresenter paymentPresenter;

    private final CommonInvoiceRouteSelector commonInvoiceRouteSelector;

    private final PaymentBankObservationService bankObservations;

    private final PaymentLinkRepository paymentLinkRepository;

    private final TochkaPaymentProfileResolver tochkaPaymentProfileResolver;

    private final PaymentLinkReturnOutboxService paymentLinkReturnOutboxService;

    private boolean isTochkaPaymentLink(PaymentLink link) {
        return paymentPresenter.isTochkaPaymentLink(link);
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = Exception.class)
    void applyObservedBankStateIfCurrent(PaymentLink link, ProviderStateObservation observation, Long lockedOrderId) {
        if (observation instanceof BankStateObservation tbankObservation) {
            applyObservedTbankStateIfCurrent(link, tbankObservation, lockedOrderId);
            return;
        }
        if (observation instanceof TochkaStateObservation tochkaObservation) {
            applyObservedTochkaStateIfCurrent(link, tochkaObservation, lockedOrderId);
        }
    }

    private void applyObservedTochkaStateIfCurrent(PaymentLink link, TochkaStateObservation observation, Long lockedOrderId) {
        if (!matchesTochkaObservationBinding(link, observation, lockedOrderId)) {
            return;
        }
        if (!normalize(observation.failureReason()).isBlank()) {
            if (!isFinalStatus(link.getStatus())) {
                link.setStatus(PaymentLinkStatus.NEEDS_RECONCILIATION);
                link.setLastError(observation.failureReason());
            }
            return;
        }
        MappedPayment mapped = observation.mapped();
        if (mapped == null) {
            return;
        }
        if (shouldIgnoreStaleTochkaStatus(link, mapped.status())) {
            log.info("Stale Tochka status ignored for payment: linkId={}, current={}, incoming={}", link.getId(), link.getStatus(), mapped.providerStatus());
            return;
        }
        boolean unchangedSnapshot = link.getStatus() == observation.status();
        boolean monotonicRefundProgress = isTochkaRefundStatus(mapped.status()) && isAllowedTochkaRefundProgress(link.getStatus(), mapped.status());
        boolean alreadyApplied = link.getStatus() == mapped.status();
        if (!unchangedSnapshot && !monotonicRefundProgress && !alreadyApplied) {
            return;
        }
        PaymentProfile entityProfile = link.getPaymentProfile();
        TochkaPaymentProfile runtimeProfile = tochkaPaymentProfileResolver.resolveForExistingPayment(entityProfile);
        if (!Objects.equals(observation.profileId(), entityProfile == null ? null : entityProfile.getId()) || !observation.merchantId().equals(runtimeProfile.merchantId()) || !observation.customerCode().equals(runtimeProfile.customerCode()) || observation.testMode() != runtimeProfile.testMode()) {
            quarantineTochkaState(link, "tochka_profile_changed_after_status_observation");
            return;
        }
        // Never catch a financial transition failure in this joined transaction.
        // A nested @Transactional participant may already have marked it rollback-only;
        // the caller must observe the failure and quarantine it in a fresh transaction.
        applyValidatedTochkaState(link, mapped, entityProfile, runtimeProfile);
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = Exception.class)
    void applyValidatedTochkaState(PaymentLink link, MappedPayment mapped, PaymentProfile entityProfile, TochkaPaymentProfile runtimeProfile) {
        PaymentLinkStatus before = link.getStatus();
        link.setProviderTerminalStatus(mapped.providerStatus());
        link.setTbankTerminalKey(runtimeProfile.merchantId());
        applyPaymentProfile(link, entityProfile);
        if (link.getBankCancelOriginStatus() != null || hasBankCancelReservation(link)) {
            if (isTochkaRefundStatus(mapped.status())) {
                markFinalBankStatus(link, mapped.status(), mapped.providerStatus());
                clearBankCancelContext(link);
            } else {
                link.setStatus(PaymentLinkStatus.NEEDS_RECONCILIATION);
                link.setLastError(limit(BANK_CANCEL_IN_PROGRESS_PREFIX + " tochka_refund_awaiting_terminal_status; observed=" + mapped.providerStatus(), 512));
            }
        } else {
            applyTochkaMappedStatus(link, mapped, runtimeProfile.testMode());
        }
        if (isTochkaRefundStatus(link.getStatus()) && before != link.getStatus()) {
            paymentLinkReturnOutboxService.enqueue(link);
            reconcileContractorPaymentRouteAfterCommit(link.getId());
        }
    }

    private boolean matchesTochkaObservationBinding(PaymentLink link, TochkaStateObservation observation, Long lockedOrderId) {
        return link != null && observation != null && isTochkaPaymentLink(link) && sameObservedLink(link, observation) && lockedOrderId != null && lockedOrderId.equals(observation.orderId()) && link.getOrder() != null && lockedOrderId.equals(link.getOrder().getId()) && link.getAmountKopecks() == observation.amountKopecks() && link.getPaymentMethod() == observation.paymentMethod() && normalize(link.getTbankOrderId()).equals(observation.paymentLinkId()) && normalize(link.getTbankPaymentId()).equals(observation.operationId()) && normalize(link.getTbankTerminalKey()).equals(observation.merchantId()) && Objects.equals(link.getPaymentProfile() == null ? null : link.getPaymentProfile().getId(), observation.profileId());
    }

    private void applyTochkaMappedStatus(PaymentLink link, MappedPayment mapped, boolean providerTestMode) {
        settlementService.applyTochkaMappedStatus(link, mapped, providerTestMode);
    }

    private void quarantineTochkaState(PaymentLink link, String reason) {
        settlementService.quarantineTochkaState(link, reason);
    }

    private boolean isTochkaRefundStatus(PaymentLinkStatus status) {
        return bankObservations.isTochkaRefundStatus(status);
    }

    private boolean isAllowedTochkaRefundProgress(PaymentLinkStatus current, PaymentLinkStatus incoming) {
        if (current == incoming) {
            return true;
        }
        if (incoming == PaymentLinkStatus.REFUNDED) {
            return current == PaymentLinkStatus.PARTIAL_REFUNDED || CONFIRMED_LIKE_BANK_STATUSES.contains(current) || current == PaymentLinkStatus.NEEDS_RECONCILIATION;
        }
        return incoming == PaymentLinkStatus.PARTIAL_REFUNDED && (CONFIRMED_LIKE_BANK_STATUSES.contains(current) || current == PaymentLinkStatus.NEEDS_RECONCILIATION);
    }

    boolean shouldIgnoreStaleTochkaStatus(PaymentLink link, PaymentLinkStatus incoming) {
        PaymentLinkStatus current = link == null ? null : link.getStatus();
        if (current == PaymentLinkStatus.REFUNDED) {
            return incoming != PaymentLinkStatus.REFUNDED;
        }
        if (current == PaymentLinkStatus.PARTIAL_REFUNDED) {
            return incoming != PaymentLinkStatus.PARTIAL_REFUNDED && incoming != PaymentLinkStatus.REFUNDED;
        }
        if (CONFIRMED_LIKE_BANK_STATUSES.contains(current)) {
            return incoming != PaymentLinkStatus.CONFIRMED && !isTochkaRefundStatus(incoming);
        }
        if (current == PaymentLinkStatus.EXPIRED) {
            // Preserve the existing late-confirmation semantics: an exact APPROVED
            // observation can still identify money received after local expiry.
            return incoming != PaymentLinkStatus.CONFIRMED;
        }
        return false;
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = Exception.class)
    void applyObservedTbankStateIfCurrent(PaymentLink link, BankStateObservation observation, Long lockedOrderId) {
        if (link == null || observation == null || !sameObservedLink(link, observation) || lockedOrderId == null || !lockedOrderId.equals(observation.orderId()) || link.getOrder() == null || !lockedOrderId.equals(link.getOrder().getId()) || link.getAmountKopecks() != observation.amountKopecks() || !normalize(link.getTbankOrderId()).equals(observation.tbankOrderId()) || !normalize(link.getTbankPaymentId()).equals(observation.paymentId())) {
            return;
        }
        String incomingStatus = normalize(observation.state().status()).toUpperCase();
        boolean unchangedSnapshot = link.getStatus() == observation.status();
        boolean monotonicRefundProgress = isRefundOrReversalBankStatus(incomingStatus) && !shouldIgnoreStaleBankStatus(link, incomingStatus);
        boolean delayedCancelResolution = "CANCELED".equals(incomingStatus) && link.getBankCancelOriginStatus() != null;
        if (!unchangedSnapshot && !monotonicRefundProgress && !delayedCancelResolution) {
            return;
        }
        PaymentProfile profile = resolvePaymentProfile(link);
        TbankPaymentProfile runtimeProfile = runtimeProfileForLink(profile, link);
        if (!normalize(runtimeProfile.terminalKey()).equals(observation.terminalKey())) {
            log.warn("T-Bank GetState observation ignored after payment profile changed: linkId={}", link.getId());
            return;
        }
        if (!isStateConsistent(link, observation.state(), runtimeProfile)) {
            paymentLinkRepository.save(link);
            return;
        }
        TbankGetStateResponse state = observation.state();
        link.setTbankTerminalKey(runtimeProfile.terminalKey());
        if (!normalize(state.paymentId()).isBlank()) {
            link.setTbankPaymentId(state.paymentId());
        }
        if (normalize(link.getTbankOrderId()).isBlank() && !normalize(state.orderId()).isBlank()) {
            link.setTbankOrderId(state.orderId());
        }
        applyPaymentProfile(link, profile);
        if (holdActiveCancelQuarantine(link, incomingStatus)) {
            paymentLinkRepository.save(link);
            return;
        }
        if (applyCancelRecoveryObservationIfNeeded(link, incomingStatus)) {
            paymentLinkRepository.save(link);
            return;
        }
        // As with Tochka, a financial transition exception must leave this
        // transaction instead of being swallowed after a nested rollback-only.
        applyBankStatus(link, incomingStatus, state.success(), normalize(state.errorCode()));
        clearResolvedCancelReservation(link, incomingStatus);
        paymentLinkRepository.save(link);
    }

    boolean sameObservedLink(PaymentLink link, ProviderStateObservation observation) {
        if (link.getId() != null && observation.linkId() != null) {
            return link.getId().equals(observation.linkId());
        }
        return normalize(link.getToken()).equals(observation.token());
    }

    private boolean hasOrderBinding(PaymentLink link, Long orderId) {
        return lifecycleService.hasOrderBinding(link, orderId);
    }

    private void clearBankCancelContext(PaymentLink link) {
        cancellationWorkflow.clearBankCancelContext(link);
    }

    private boolean hasBankCancelReservation(PaymentLink link) {
        return paymentPresenter.hasBankCancelReservation(link);
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

    /**
     * A non-terminal observation received while Cancel is still in flight
     * cannot prove that the refund failed. Keep the durable quarantine until
     * the request finishes or its lease expires. Explicit refund/reversal
     * states are safe to apply immediately.
     */
    private boolean holdActiveCancelQuarantine(PaymentLink link, String incomingStatus) {
        return cancellationWorkflow.holdActiveCancelQuarantine(link, incomingStatus);
    }

    /**
     * Restores a previously paid local state without replaying order-payment
     * side effects when GetState merely confirms that an ambiguous Cancel did
     * not change the bank payment. Regressive or unknown observations remain
     * quarantined until the bank reports a conclusive state.
     */
    private boolean applyCancelRecoveryObservationIfNeeded(PaymentLink link, String incomingStatus) {
        return cancellationWorkflow.applyCancelRecoveryObservationIfNeeded(link, incomingStatus);
    }

    private void clearResolvedCancelReservation(PaymentLink link, String incomingStatus) {
        cancellationWorkflow.clearResolvedCancelReservation(link, incomingStatus);
    }

    boolean isStateConsistent(PaymentLink link, TbankGetStateResponse state, TbankPaymentProfile runtimeProfile) {
        String responseTerminal = normalize(state.terminalKey());
        if (!responseTerminal.isBlank() && !responseTerminal.equals(runtimeProfile.terminalKey())) {
            link.setLastError("TerminalKey GetState не совпадает с платежной ссылкой");
            log.warn("T-Bank GetState terminal mismatch: linkId={}, expected={}, actual={}", link.getId(), runtimeProfile.terminalKey(), responseTerminal);
            return false;
        }
        if (state.amount() != null && state.amount() != link.getAmountKopecks()) {
            link.setLastError("Сумма GetState не совпадает с платежной ссылкой");
            log.warn("T-Bank GetState amount mismatch: linkId={}, expected={}, actual={}", link.getId(), link.getAmountKopecks(), state.amount());
            return false;
        }
        String responsePaymentId = normalize(state.paymentId());
        if (!responsePaymentId.isBlank() && !responsePaymentId.equals(normalize(link.getTbankPaymentId()))) {
            link.setLastError("PaymentId GetState не совпадает с платежной ссылкой");
            log.warn("T-Bank GetState payment binding mismatch: linkId={}", link.getId());
            return false;
        }
        String responseOrderId = normalize(state.orderId());
        String currentOrderId = normalize(link.getTbankOrderId());
        if (!responseOrderId.isBlank() && !currentOrderId.isBlank() && !responseOrderId.equals(currentOrderId)) {
            link.setLastError("OrderId GetState не совпадает с платежной ссылкой");
            log.warn("T-Bank GetState order binding mismatch: linkId={}", link.getId());
            return false;
        }
        return true;
    }

    private void applyBankStatus(PaymentLink link, String status, boolean success, String errorCode) {
        settlementService.applyBankStatus(link, status, success, errorCode);
    }

    private boolean shouldIgnoreStaleBankStatus(PaymentLink link, String incomingStatus) {
        return settlementService.shouldIgnoreStaleBankStatus(link, incomingStatus);
    }

    private boolean isRefundOrReversalBankStatus(String status) {
        return cancellationWorkflow.isRefundOrReversalBankStatus(status);
    }

    boolean matchesObservedBankBinding(PaymentLink link, ProviderStateObservation observation, Long orderId) {
        if (observation instanceof TochkaStateObservation tochka) {
            return matchesTochkaObservationBinding(link, tochka, orderId);
        }
        if (!(observation instanceof BankStateObservation tbank)) {
            return false;
        }
        TbankGetStateResponse state = tbank.state();
        boolean baseBinding = sameObservedLink(link, tbank) && orderId != null && orderId.equals(tbank.orderId()) && hasOrderBinding(link, orderId) && link.getAmountKopecks() == tbank.amountKopecks() && normalize(link.getTbankPaymentId()).equals(tbank.paymentId()) && normalize(link.getTbankOrderId()).equals(tbank.tbankOrderId()) && normalize(link.getTbankTerminalKey()).equals(tbank.terminalKey());
        if (!baseBinding || state == null) {
            return baseBinding;
        }
        String responsePaymentId = normalize(state.paymentId());
        String responseOrderId = normalize(state.orderId());
        String responseTerminalKey = normalize(state.terminalKey());
        return (state.amount() == null || state.amount() == link.getAmountKopecks()) && (responsePaymentId.isBlank() || responsePaymentId.equals(tbank.paymentId())) && (responseOrderId.isBlank() || responseOrderId.equals(tbank.tbankOrderId())) && (responseTerminalKey.isBlank() || responseTerminalKey.equals(tbank.terminalKey()));
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = Exception.class)
    void validateWebhookAmount(PaymentLink link, Map<String, String> payload) {
        String amount = normalize(payload.get("Amount"));
        if (amount.isBlank()) {
            return;
        }
        try {
            long webhookAmount = Long.parseLong(amount);
            if (webhookAmount != link.getAmountKopecks()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Сумма webhook не совпадает с платежной ссылкой");
            }
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректная сумма webhook", e);
        }
    }

    private PaymentProfile resolvePaymentProfile(PaymentLink link) {
        return bankObservations.resolvePaymentProfile(link);
    }

    private TbankPaymentProfile runtimeProfileForLink(PaymentProfile profile, PaymentLink link) {
        return bankObservations.runtimeProfileForLink(profile, link);
    }

    private void applyPaymentProfile(PaymentLink link, PaymentProfile profile) {
        commonInvoiceRouteSelector.applyPaymentProfile(link, profile);
    }

    private void markFinalBankStatus(PaymentLink link, PaymentLinkStatus status, String providerTerminalStatus) {
        settlementService.markFinalBankStatus(link, status, providerTerminalStatus);
    }

    private boolean isFinalStatus(PaymentLinkStatus status) {
        return settlementService.isFinalStatus(status);
    }

    private String normalize(String value) {
        return paymentPresenter.normalize(value);
    }

    private String limit(String value, int maxLength) {
        return commonInvoiceRouteSelector.limit(value, maxLength);
    }
}
