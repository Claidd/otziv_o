package com.hunt.otziv.payments.service;

import com.hunt.otziv.common_billing.api.CommonInvoicePaymentOperations;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.payments.dto.TbankPaymentProfile;
import com.hunt.otziv.payments.tochka.dto.TochkaApiModels.PaymentOperation;
import com.hunt.otziv.payments.tochka.dto.TochkaAcquiringInternetPaymentWebhook;
import com.hunt.otziv.payments.tochka.dto.TochkaPaymentProfile;
import com.hunt.otziv.payments.tochka.dto.TochkaWebhookExpectation;
import com.hunt.otziv.payments.tochka.model.TochkaPaymentMode;
import com.hunt.otziv.payments.tochka.service.TochkaPaymentOperationMapper;
import com.hunt.otziv.payments.tochka.service.TochkaPaymentOperationMapper.ExpectedPayment;
import com.hunt.otziv.payments.tochka.service.TochkaPaymentOperationMapper.MappedPayment;
import com.hunt.otziv.payments.tochka.service.TochkaPaymentProfileResolver;
import com.hunt.otziv.payments.tochka.service.TochkaWebhookJwtVerifier;
import com.hunt.otziv.payments.tochka.service.TochkaWebhookVerificationException;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.model.PaymentMethod;
import com.hunt.otziv.payments.model.PaymentProfile;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import static com.hunt.otziv.logs.util.LogMasking.maskPaymentId;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static com.hunt.otziv.payments.service.PaymentBankObservationService.BankStateObservation;
import static com.hunt.otziv.payments.service.PaymentBankObservationService.ProviderStateObservation;
import static com.hunt.otziv.payments.service.PaymentBankObservationService.SYNCABLE_BANK_STATUSES;
import static com.hunt.otziv.payments.service.PaymentBankObservationService.TochkaStateObservation;

@Service
@Slf4j
@RequiredArgsConstructor
/**
 * Verifies bank events and reconciles existing attempts. Provider observations run
 * outside the database transaction; locked application and failure quarantine use
 * explicit transaction boundaries so a failed apply cannot erase bank evidence.
 */
public class BankPaymentReconciliationWorkflow {

    private final PaymentLinkSettlementService settlementService;

    private final PaymentLinkLifecycleService lifecycleService;

    private final BankInitializationStateService bankInitializationState;

    private final BankObservationApplicationService bankObservationApplication;

    private final PaymentLinkCancellationWorkflow cancellationWorkflow;

    private final PaymentLinkPresenter paymentPresenter;

    private final CommonInvoiceRouteSelector commonInvoiceRouteSelector;

    private final PaymentBankObservationService bankObservations;

    static final String PROVIDER_CONFIRMED_APPLY_FAILED = "provider_confirmed_apply_failed:";

    static final String BANK_STATUS_APPLY_FAILED = "bank_status_apply_failed:";

    private final PaymentLinkRepository paymentLinkRepository;

    private final OrderRepository orderRepository;

    private final PaymentProfileService paymentProfileService;

    private final TochkaPaymentProfileResolver tochkaPaymentProfileResolver;

    private final TochkaPaymentOperationMapper tochkaPaymentOperationMapper;

    private final TochkaWebhookJwtVerifier tochkaWebhookJwtVerifier;

    private final TbankTokenSigner tokenSigner;

    private final CommonInvoicePaymentOperations commonInvoicePayments;

    private final PaymentLinkTransactionExecutor transactionExecutor;

    private ProviderStateObservation observeBankState(PaymentLink link) {
        return bankObservations.observeBankState(link);
    }

    private TochkaPaymentMode expectedTochkaMode(PaymentMethod paymentMethod) {
        return bankObservations.expectedTochkaMode(paymentMethod);
    }

    private void applyObservedBankStateIfCurrent(PaymentLink link, ProviderStateObservation observation, Long lockedOrderId) {
        bankObservationApplication.applyObservedBankStateIfCurrent(link, observation, lockedOrderId);
    }

    private void applyValidatedTochkaState(PaymentLink link, MappedPayment mapped, PaymentProfile entityProfile, TochkaPaymentProfile runtimeProfile) {
        bankObservationApplication.applyValidatedTochkaState(link, mapped, entityProfile, runtimeProfile);
    }

    private boolean shouldIgnoreStaleTochkaStatus(PaymentLink link, PaymentLinkStatus incoming) {
        return bankObservationApplication.shouldIgnoreStaleTochkaStatus(link, incoming);
    }

    private boolean hasOrderBinding(PaymentLink link, Long orderId) {
        return lifecycleService.hasOrderBinding(link, orderId);
    }

    private boolean matchesWebhookBinding(PaymentLink link, Map<String, String> payload) {
        String orderId = normalize(payload.get("OrderId"));
        if (!orderId.isBlank() && !orderId.equals(normalize(link.getTbankOrderId()))) {
            return false;
        }
        String paymentId = normalize(payload.get("PaymentId"));
        String currentPaymentId = normalize(link.getTbankPaymentId());
        return paymentId.isBlank() || currentPaymentId.isBlank() || paymentId.equals(currentPaymentId);
    }

    private void clearBankInitReservation(PaymentLink link) {
        bankInitializationState.clearBankInitReservation(link);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void handleTbankWebhook(Map<String, String> payload) {
        VerifiedWebhookProfile verified = verifyWebhook(payload);
        String orderId = normalize(payload.get("OrderId"));
        String paymentId = normalize(payload.get("PaymentId"));
        Optional<PaymentLink> linkCandidate = !orderId.isBlank() ? paymentLinkRepository.findByTbankOrderIdWithOrder(orderId) : Optional.empty();
        if (linkCandidate.isEmpty() && !paymentId.isBlank()) {
            linkCandidate = paymentLinkRepository.findByTbankPaymentIdWithOrder(paymentId);
        }
        if (linkCandidate.isEmpty()) {
            CommonInvoicePaymentOperations commonBillingService = commonInvoicePayments;
            if (commonBillingService.handleTbankWebhook(payload)) {
                return;
            }
            log.warn("T-Bank webhook ignored: payment link not found for OrderId={}, PaymentId={}", maskPaymentId(orderId), maskPaymentId(paymentId));
            return;
        }
        PaymentLink snapshot = linkCandidate.get();
        Long linkId = snapshot.getId();
        Long canonicalOrderId = snapshot.getOrder() == null ? null : snapshot.getOrder().getId();
        transactionExecutor.required(() -> {
            applyTbankWebhookLocked(linkId, canonicalOrderId, payload, verified);
            return null;
        });
    }

    /**
     * Applies a signed Tochka acquiring webhook to one exact, already-created payment attempt.
     * Signature verification intentionally happens before the first repository lookup.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void handleTochkaWebhook(String rawJwt) {
        TochkaAcquiringInternetPaymentWebhook claims = tochkaWebhookJwtVerifier.verify(rawJwt);
        String paymentLinkId = safeTochkaWebhookLookupId(claims.paymentLinkId());
        String operationId = safeTochkaWebhookLookupId(claims.operationId());
        if (paymentLinkId.isBlank() && operationId.isBlank()) {
            log.info("Signed Tochka test webhook acknowledged without a payment identity");
            return;
        }
        Optional<PaymentLink> linkCandidate = paymentLinkId.isBlank() ? Optional.empty() : paymentLinkRepository.findByTbankOrderIdWithOrder(paymentLinkId);
        if (linkCandidate.isEmpty() && !operationId.isBlank()) {
            linkCandidate = paymentLinkRepository.findByTbankPaymentIdWithOrder(operationId);
        }
        if (linkCandidate.isEmpty()) {
            CommonInvoicePaymentOperations commonBillingService = commonInvoicePayments;
            if (commonBillingService.handleTochkaWebhook(claims)) {
                return;
            }
            log.warn("Signed Tochka webhook ignored: payment link not found for paymentLinkId={}, operationId={}", maskPaymentId(claims.paymentLinkId()), maskPaymentId(claims.operationId()));
            return;
        }
        PaymentLink snapshot = linkCandidate.get();
        Long linkId = snapshot.getId();
        Long orderId = snapshot.getOrder() == null ? null : snapshot.getOrder().getId();
        Long profileId = snapshot.getPaymentProfile() == null ? null : snapshot.getPaymentProfile().getId();
        transactionExecutor.required(() -> {
            applyTochkaWebhookLocked(linkId, orderId, profileId, claims);
            return null;
        });
    }

    private void applyTochkaWebhookLocked(Long linkId, Long orderId, Long profileId, TochkaAcquiringInternetPaymentWebhook claims) {
        if (linkId == null || orderId == null || profileId == null || orderRepository.findByIdForCounterUpdate(orderId).isEmpty()) {
            throw new TochkaWebhookVerificationException("Tochka webhook payment binding disappeared before locking");
        }
        PaymentLink link = paymentLinkRepository.findByIdForUpdate(linkId).orElseThrow(() -> new TochkaWebhookVerificationException("Tochka webhook payment binding disappeared before applying"));
        PaymentProfile entityProfile = link.getPaymentProfile();
        if (!hasOrderBinding(link, orderId) || entityProfile == null || !Objects.equals(profileId, entityProfile.getId())) {
            throw new TochkaWebhookVerificationException("Tochka webhook payment or pinned profile binding changed");
        }
        try {
            if (!PaymentProfile.PROVIDER_TOCHKA.equals(entityProfile.normalizedProvider())) {
                throw new TochkaWebhookVerificationException("Tochka webhook cannot be applied to another provider");
            }
        } catch (IllegalArgumentException failure) {
            throw new TochkaWebhookVerificationException("Tochka webhook pinned profile provider is invalid", failure);
        }
        TochkaPaymentProfile runtimeProfile;
        try {
            runtimeProfile = tochkaPaymentProfileResolver.resolveForExistingPayment(entityProfile);
        } catch (RuntimeException failure) {
            throw new TochkaWebhookVerificationException("Tochka webhook pinned profile cannot be resolved", failure);
        }
        if (!Objects.equals(runtimeProfile.id(), entityProfile.getId()) || !normalize(link.getTbankTerminalKey()).equals(runtimeProfile.merchantId()) || entityProfile.isTestMode() != runtimeProfile.testMode()) {
            throw new TochkaWebhookVerificationException("Tochka webhook pinned profile identity or test mode changed");
        }
        TochkaPaymentMode expectedMode = expectedTochkaMode(link.getPaymentMethod());
        TochkaWebhookExpectation expectedWebhook = new TochkaWebhookExpectation(runtimeProfile.customerCode(), runtimeProfile.merchantId(), BigDecimal.valueOf(link.getAmountKopecks(), 2), normalize(link.getTbankPaymentId()), normalize(link.getTbankOrderId()), expectedMode.code());
        tochkaWebhookJwtVerifier.requireMatches(claims, expectedWebhook);
        MappedPayment mapped = tochkaPaymentOperationMapper.map(tochkaWebhookOperation(claims), new ExpectedPayment(normalize(link.getTbankPaymentId()), normalize(link.getTbankOrderId()), runtimeProfile.customerCode(), runtimeProfile.merchantId(), link.getAmountKopecks(), expectedMode), false);
        if (mapped.paymentMethod() != link.getPaymentMethod()) {
            throw new TochkaWebhookVerificationException("Tochka webhook payment type does not match the persisted payment method");
        }
        if (shouldIgnoreStaleTochkaStatus(link, mapped.status())) {
            log.info("Stale Tochka webhook ignored for terminal payment: linkId={}, current={}, incoming={}", link.getId(), link.getStatus(), mapped.providerStatus());
            return;
        }
        clearBankInitReservation(link);
        applyValidatedTochkaState(link, mapped, entityProfile, runtimeProfile);
        paymentLinkRepository.save(link);
    }

    private PaymentOperation tochkaWebhookOperation(TochkaAcquiringInternetPaymentWebhook claims) {
        return new PaymentOperation(claims.customerCode(), null, claims.paymentType(), null, claims.transactionId(), null, claims.amount(), claims.status(), claims.operationId(), null, claims.merchantId(), null, claims.paymentLinkId(), List.of());
    }

    private String safeTochkaWebhookLookupId(String value) {
        if (value == null || value.isBlank() || value.length() > 256 || !value.equals(value.trim())) {
            return "";
        }
        return value;
    }

    private void applyTbankWebhookLocked(Long linkId, Long orderId, Map<String, String> payload, VerifiedWebhookProfile verified) {
        if (linkId == null || orderId == null || orderRepository.findByIdForCounterUpdate(orderId).isEmpty()) {
            log.warn("T-Bank webhook ignored because canonical payment binding disappeared: linkId={}, orderId={}", linkId, orderId);
            return;
        }
        PaymentLink link = paymentLinkRepository.findByIdForUpdate(linkId).orElse(null);
        if (link == null || !hasOrderBinding(link, orderId) || !matchesWebhookBinding(link, payload)) {
            log.warn("T-Bank webhook ignored after payment binding changed: linkId={}, orderId={}", linkId, orderId);
            return;
        }
        PaymentProfile profile = verified.profile();
        TbankPaymentProfile runtimeProfile = verified.runtimeProfile();
        validateWebhookTerminal(link, runtimeProfile);
        validateWebhookAmount(link, payload);
        String paymentId = normalize(payload.get("PaymentId"));
        link.setTbankPaymentId(paymentId.isBlank() ? link.getTbankPaymentId() : paymentId);
        link.setTbankTerminalKey(runtimeProfile.terminalKey());
        applyPaymentProfile(link, profile);
        String status = normalize(payload.get("Status")).toUpperCase();
        boolean success = "true".equalsIgnoreCase(normalize(payload.get("Success")));
        String errorCode = normalize(payload.get("ErrorCode"));
        if (holdActiveCancelQuarantine(link, status)) {
            paymentLinkRepository.save(link);
            return;
        }
        if (applyCancelRecoveryObservationIfNeeded(link, status)) {
            paymentLinkRepository.save(link);
            return;
        }
        applyBankStatus(link, status, success, errorCode);
        clearResolvedCancelReservation(link, status);
        paymentLinkRepository.save(link);
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

    private void applyBankStatus(PaymentLink link, String status, boolean success, String errorCode) {
        settlementService.applyBankStatus(link, status, success, errorCode);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean reconcileBankLink(Long linkId) {
        return reconcileBankLink(linkId, LocalDateTime.now().minusMinutes(5));
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean reconcileBankLink(Long linkId, LocalDateTime attemptBefore) {
        if (linkId == null || linkId <= 0) {
            return false;
        }
        PaymentLink snapshot = paymentLinkRepository.findByIdWithOrder(linkId).orElse(null);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime eligibleBefore = attemptBefore == null ? now.minusMinutes(5) : attemptBefore;
        if (!isReconciliationEligible(snapshot, eligibleBefore)) {
            return false;
        }
        ProviderStateObservation observation = observeBankState(snapshot);
        Long orderId = snapshot.getOrder() == null ? null : snapshot.getOrder().getId();
        try {
            return transactionExecutor.required(() -> applyReconciliationObservation(linkId, orderId, eligibleBefore, observation));
        } catch (RuntimeException failure) {
            log.error("Provider status apply rolled back: linkId={}, orderId={}, providerStatus={}", linkId, orderId, providerStatus(observation), failure);
            if (quarantineObservedBankApplyFailure(linkId, orderId, eligibleBefore, observation, failure)) {
                return true;
            }
            throw failure;
        }
    }

    /**
     * Persists the failed observation audit only after the state transaction
     * has fully rolled back. For an exact confirmation it also preserves the
     * bank's payment evidence. Keeping this boundary outside the failed
     * transaction prevents an inner rollback-only marker from turning the
     * useful cause into an anonymous UnexpectedRollbackException, and the
     * attempt timestamp rotates a broken row out of scheduler page zero.
     */
    private boolean quarantineObservedBankApplyFailure(Long linkId, Long orderId, LocalDateTime eligibleBefore, ProviderStateObservation observation, RuntimeException failure) {
        if (observation == null) {
            return false;
        }
        boolean providerConfirmed = isProviderConfirmedObservation(observation);
        try {
            return transactionExecutor.required(() -> {
                if (orderId == null || orderRepository.findByIdForCounterUpdate(orderId).isEmpty()) {
                    return false;
                }
                PaymentLink link = paymentLinkRepository.findByIdForUpdate(linkId).orElse(null);
                if (!hasOrderBinding(link, orderId) || !isReconciliationEligible(link, eligibleBefore) || !matchesObservedBankBinding(link, observation, orderId)) {
                    return false;
                }
                LocalDateTime now = LocalDateTime.now();
                link.setBankReconciliationAttemptedAt(now);
                link.setStatus(PaymentLinkStatus.NEEDS_RECONCILIATION);
                String observedStatus = providerStatus(observation);
                if (!observedStatus.isBlank()) {
                    link.setProviderTerminalStatus(observedStatus);
                }
                if (providerConfirmed) {
                    if (link.getPaidAt() == null) {
                        link.setPaidAt(now);
                    }
                    link.setConfirmedAmountKopecks(link.getAmountKopecks());
                }
                clearBankInitReservation(link);
                link.setLastError(limit((providerConfirmed ? PROVIDER_CONFIRMED_APPLY_FAILED : BANK_STATUS_APPLY_FAILED) + " " + paymentFailureReason(failure), 512));
                paymentLinkRepository.save(link);
                return true;
            });
        } catch (RuntimeException quarantineFailure) {
            log.error("Failed to persist bank-status apply quarantine: linkId={}, orderId={}", linkId, orderId, quarantineFailure);
            return false;
        }
    }

    private boolean isProviderConfirmedObservation(ProviderStateObservation observation) {
        if (observation instanceof TochkaStateObservation tochka) {
            return normalize(tochka.failureReason()).isBlank() && tochka.mapped() != null && tochka.mapped().status() == PaymentLinkStatus.CONFIRMED;
        }
        if (observation instanceof BankStateObservation tbank) {
            return tbank.state() != null && tbank.state().success() && "CONFIRMED".equals(normalize(tbank.state().status()).toUpperCase());
        }
        return false;
    }

    private boolean matchesObservedBankBinding(PaymentLink link, ProviderStateObservation observation, Long orderId) {
        return bankObservationApplication.matchesObservedBankBinding(link, observation, orderId);
    }

    String providerStatus(ProviderStateObservation observation) {
        if (observation instanceof TochkaStateObservation tochka && tochka.mapped() != null) {
            return normalize(tochka.mapped().providerStatus()).toUpperCase();
        }
        if (observation instanceof BankStateObservation tbank && tbank.state() != null) {
            return normalize(tbank.state().status()).toUpperCase();
        }
        return "";
    }

    private boolean applyReconciliationObservation(Long linkId, Long orderId, LocalDateTime eligibleBefore, ProviderStateObservation observation) {
        if (orderId == null || orderRepository.findByIdForCounterUpdate(orderId).isEmpty()) {
            return false;
        }
        PaymentLink link = paymentLinkRepository.findByIdForUpdate(linkId).orElse(null);
        if (!hasOrderBinding(link, orderId) || !isReconciliationEligible(link, eligibleBefore)) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        // This field is changed even when the bank state is unchanged or the
        // provider call fails. Page zero therefore rotates instead of starving
        // newer links behind the same oldest rows.
        link.setBankReconciliationAttemptedAt(now);
        PaymentLinkStatus before = link.getStatus();
        applyObservedBankStateIfCurrent(link, observation, orderId);
        paymentLinkRepository.save(link);
        return before != link.getStatus();
    }

    private boolean isReconciliationEligible(PaymentLink link, LocalDateTime eligibleBefore) {
        return link != null && (SYNCABLE_BANK_STATUSES.contains(link.getStatus()) || link.getBankCancelOriginStatus() != null) && !normalize(link.getTbankPaymentId()).isBlank() && (link.getBankReconciliationAttemptedAt() == null || !link.getBankReconciliationAttemptedAt().isAfter(eligibleBefore));
    }

    private String paymentFailureReason(Throwable failure) {
        return settlementService.paymentFailureReason(failure);
    }

    private VerifiedWebhookProfile verifyWebhook(Map<String, String> payload) {
        String terminalKey = normalize(payload.get("TerminalKey"));
        PaymentProfile profile = paymentProfileService.findByTerminalKey(terminalKey).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "TerminalKey не совпадает с настройками"));
        TbankPaymentProfile runtimeProfile = paymentProfileService.toRuntimeForTerminal(profile, terminalKey);
        if (!runtimeProfile.hasCredentials()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Не заданы TerminalKey или Password Т-Банка");
        }
        if (!tokenSigner.matches(payload, runtimeProfile.password(), payload.get("Token"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректная подпись уведомления Т-Банка");
        }
        return new VerifiedWebhookProfile(profile, runtimeProfile);
    }

    private void validateWebhookTerminal(PaymentLink link, TbankPaymentProfile runtimeProfile) {
        String linkTerminal = normalize(link.getTbankTerminalKey());
        String profileTerminal = normalize(runtimeProfile.terminalKey());
        if (!linkTerminal.isBlank() && !linkTerminal.equals(profileTerminal)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "TerminalKey webhook не совпадает с платежной ссылкой");
        }
    }

    private void validateWebhookAmount(PaymentLink link, Map<String, String> payload) {
        bankObservationApplication.validateWebhookAmount(link, payload);
    }

    private void applyPaymentProfile(PaymentLink link, PaymentProfile profile) {
        commonInvoiceRouteSelector.applyPaymentProfile(link, profile);
    }

    private String normalize(String value) {
        return paymentPresenter.normalize(value);
    }

    private String limit(String value, int maxLength) {
        return commonInvoiceRouteSelector.limit(value, maxLength);
    }

    record VerifiedWebhookProfile(PaymentProfile profile, TbankPaymentProfile runtimeProfile) {
    }
}
