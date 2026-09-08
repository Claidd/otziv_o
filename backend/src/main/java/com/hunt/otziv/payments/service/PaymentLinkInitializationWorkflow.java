package com.hunt.otziv.payments.service;

import com.hunt.otziv.contractor_payments.service.ContractorPaymentTargetAccessPolicy;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.payments.config.TbankPaymentProperties;
import com.hunt.otziv.payments.dto.AdminPaymentLinkResponse;
import com.hunt.otziv.payments.dto.PublicPaymentInitResponse;
import com.hunt.otziv.payments.dto.TbankGetQrCommand;
import com.hunt.otziv.payments.dto.TbankGetQrResponse;
import com.hunt.otziv.payments.dto.TbankInitCommand;
import com.hunt.otziv.payments.dto.TbankInitResponse;
import com.hunt.otziv.payments.dto.TbankPaymentProfile;
import com.hunt.otziv.payments.tochka.dto.TochkaApiModels.CreatePaymentResponse;
import com.hunt.otziv.payments.tochka.dto.TochkaApiModels.PaymentOperation;
import com.hunt.otziv.payments.tochka.dto.TochkaCreatePaymentCommand;
import com.hunt.otziv.payments.tochka.dto.TochkaPaymentProfile;
import com.hunt.otziv.payments.tochka.model.TochkaPaymentMode;
import com.hunt.otziv.payments.tochka.service.TochkaClient;
import com.hunt.otziv.payments.tochka.service.TochkaPaymentOperationMapper;
import com.hunt.otziv.payments.tochka.service.TochkaPaymentOperationMapper.ExpectedPayment;
import com.hunt.otziv.payments.tochka.service.TochkaPaymentOperationMapper.MappedPayment;
import com.hunt.otziv.payments.tochka.service.TochkaPaymentProfileResolver;
import com.hunt.otziv.payments.tochka.service.TochkaProviderException;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.model.PaymentMethod;
import com.hunt.otziv.payments.model.PaymentProfile;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import static com.hunt.otziv.logs.util.LogMasking.maskPaymentId;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static com.hunt.otziv.payments.service.BankInitializationStateService.BANK_INIT_AMBIGUOUS_PREFIX;
import static com.hunt.otziv.payments.service.BankInitializationStateService.BankInitReservationRecovery;

/**
 * Owns public bank initialization and recovery: commit a scoped reservation,
 * invoke the provider outside a transaction, then fence its result under the
 * canonical order/link locks. Ambiguous creates remain blocked for reconciliation.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentLinkInitializationWorkflow {

    private final PaymentLinkSettlementService settlementService;

    private final PaymentLinkLifecycleService lifecycleService;

    private final BankInitializationStateService bankInitializationState;

    private final PaymentLinkPreparationWorkflow preparationWorkflow;

    private final PublicPaymentLinkResolutionService publicLinkResolution;

    private final PaymentLinkCancellationWorkflow cancellationWorkflow;

    private final PaymentLinkPresenter paymentPresenter;

    private final CommonInvoiceRouteSelector commonInvoiceRouteSelector;

    private final PaymentBankObservationService bankObservations;

    static final String OFFER_PATH = "/offer";

    static final String PRIVACY_PATH = "/privacy";

    static final String RECEIPT_CONSENT_PATH = "/receipt-consent";

    static final ZoneId MOSCOW_ZONE = ZoneId.of("Europe/Moscow");

    static final Duration BANK_INIT_LEASE = Duration.ofMinutes(5);

    private final PaymentLinkRepository paymentLinkRepository;

    private final OrderRepository orderRepository;

    private final TbankPaymentProperties properties;

    private final PaymentProfileService paymentProfileService;

    private final TbankClient tbankClient;

    private final TochkaPaymentProfileResolver tochkaPaymentProfileResolver;

    private final TochkaClient tochkaClient;

    private final TochkaPaymentOperationMapper tochkaPaymentOperationMapper;

    private final OrderPaymentIntegrityService orderPaymentIntegrityService;

    private final PaymentLinkReturnOutboxService paymentLinkReturnOutboxService;

    private final ContractorPaymentTargetAccessPolicy contractorPaymentTargetAccessPolicy;

    private final PaymentLinkTransactionExecutor transactionExecutor;

    private void ensureOrderNotCoveredByActiveCommonInvoice(Long orderId) {
        preparationWorkflow.ensureOrderNotCoveredByActiveCommonInvoice(orderId);
    }

    private boolean hasBankInitReservation(PaymentLink link) {
        return lifecycleService.hasBankInitReservation(link);
    }

    private boolean isTochkaPaymentLink(PaymentLink link) {
        return paymentPresenter.isTochkaPaymentLink(link);
    }

    private TochkaPaymentMode expectedTochkaMode(PaymentMethod paymentMethod) {
        return bankObservations.expectedTochkaMode(paymentMethod);
    }

    private TochkaPaymentMode tochkaMode(BankInitMode mode) {
        return mode == BankInitMode.SBP_QR ? TochkaPaymentMode.SBP : TochkaPaymentMode.CARD;
    }

    private void requireTochkaModeAllowed(TochkaPaymentProfile profile, BankInitMode mode) {
        TochkaPaymentMode requested = tochkaMode(mode);
        if (profile == null || !profile.paymentModes().contains(requested)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Способ оплаты " + requested.code() + " выключен в платежном профиле Точки");
        }
    }

    private void applyTochkaMappedStatus(PaymentLink link, MappedPayment mapped, boolean providerTestMode) {
        settlementService.applyTochkaMappedStatus(link, mapped, providerTestMode);
    }

    boolean isTochkaRefundStatus(PaymentLinkStatus status) {
        return bankObservations.isTochkaRefundStatus(status);
    }

    private boolean hasOrderBinding(PaymentLink link, Long orderId) {
        return lifecycleService.hasOrderBinding(link, orderId);
    }

    /**
     * Recovery for the intentionally conservative no-PaymentId quarantine.
     * The endpoint requires an explicit administrator assertion that the
     * stable T-Bank OrderId was checked and no payment exists.
     */
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public AdminPaymentLinkResponse releaseAmbiguousBankInit(Long linkId, boolean bankPaymentAbsent, String note, String actor) {
        contractorPaymentTargetAccessPolicy.requireCanManagePaymentLink(linkId);
        PaymentLink snapshot = paymentLinkRepository.findByIdWithOrder(linkId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежная ссылка не найдена"));
        Long orderId = snapshot.getOrder() == null ? null : snapshot.getOrder().getId();
        if (orderId == null || orderRepository.findByIdForCounterUpdate(orderId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ платежной ссылки не найден");
        }
        PaymentLink link = paymentLinkRepository.findByIdForUpdate(linkId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежная ссылка не найдена"));
        String cleanNote = normalize(note);
        if (!hasOrderBinding(link, orderId) || !bankPaymentAbsent || cleanNote.isBlank() || link.getStatus() != PaymentLinkStatus.NEEDS_RECONCILIATION || !normalize(link.getTbankPaymentId()).isBlank() || !normalize(link.getLastError()).startsWith(BANK_INIT_AMBIGUOUS_PREFIX) || (!normalize(link.getBankInitNonce()).isBlank() && link.getBankInitLeaseUntil() != null && link.getBankInitLeaseUntil().isAfter(LocalDateTime.now()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Снять блокировку можно только после подтвержденной сверки неоднозначного Init без PaymentId");
        }
        String previousOrderId = normalize(link.getTbankOrderId());
        clearBankInitReservation(link);
        link.setTbankOrderId(null);
        link.setPaymentUrl(null);
        link.setStatus(PaymentLinkStatus.CREATED);
        link.setInitiatedAt(null);
        link.setLastError(limit("bank_init_released_by=" + normalize(actor) + "; checked_order_id=" + previousOrderId + "; note=" + cleanNote, 512));
        paymentLinkRepository.save(link);
        log.warn("Ambiguous T-Bank Init released after operator verification: linkId={}, orderId={}, actor={}", linkId, orderId, normalize(actor));
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

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PublicPaymentInitResponse init(String token, String email, boolean offerConsent, boolean privacyConsent, boolean receiptConsent, String clientIp, String userAgent) {
        String cleanEmail = normalizeEmail(email);
        if (cleanEmail.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Укажите e-mail для электронного чека");
        }
        BankInitReservation reservation = reserveBankInitialization(token, cleanEmail, offerConsent, privacyConsent, receiptConsent, null, clientIp, userAgent, BankInitMode.BANK_FORM);
        if (reservation.cachedResponse() != null) {
            return reservation.cachedResponse();
        }
        if (reservation.tochkaProfile() != null) {
            return initTochka(reservation);
        }
        TbankInitResponse response;
        try {
            response = tbankClient.init(reservation.runtimeProfile(), new TbankInitCommand(reservation.tbankOrderId(), reservation.amountKopecks(), reservation.description(), reservation.email(), properties.notificationUrl(), properties.successUrl(), properties.failUrl(), OffsetDateTime.now(MOSCOW_ZONE).plus(properties.getRedirectDue())));
        } catch (RuntimeException e) {
            recordAmbiguousBankInitFailure(reservation, e);
            log.warn("T-Bank Init failed: linkId={}, orderId={}, profile={}, terminal={}, status={}, reason={}", reservation.linkId(), reservation.orderId(), reservation.runtimeProfile().code(), reservation.terminalKey(), e instanceof ResponseStatusException statusException ? statusException.getStatusCode() : HttpStatus.BAD_GATEWAY, providerFailureReason(e));
            throw e;
        }
        return requireSuccessfulBankInit(transactionExecutor.required(() -> applyBankInitResponse(reservation, response, false)));
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PublicPaymentInitResponse initSbp(String token, String email, boolean offerConsent, boolean privacyConsent, boolean receiptConsent, String sbpBankId, String clientIp, String userAgent) {
        String cleanEmail = normalizeEmail(email);
        if (cleanEmail.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Укажите e-mail для электронного чека");
        }
        String cleanBankId = normalize(sbpBankId);
        BankInitReservation reservation = reserveBankInitialization(token, cleanEmail, offerConsent, privacyConsent, receiptConsent, cleanBankId, clientIp, userAgent, BankInitMode.SBP_QR);
        if (reservation.cachedResponse() != null) {
            return reservation.cachedResponse();
        }
        if (reservation.tochkaProfile() != null) {
            return initTochka(reservation);
        }
        String paymentId = reservation.paymentId();
        String paymentUrl = reservation.paymentUrl();
        if (paymentId.isBlank()) {
            TbankInitResponse response;
            try {
                response = tbankClient.init(reservation.runtimeProfile(), new TbankInitCommand(reservation.tbankOrderId(), reservation.amountKopecks(), reservation.description(), reservation.email(), properties.notificationUrl(), properties.successUrl(), properties.failUrl(), OffsetDateTime.now(MOSCOW_ZONE).plus(properties.getRedirectDue())));
            } catch (RuntimeException e) {
                recordAmbiguousBankInitFailure(reservation, e);
                log.warn("T-Bank Init before SBP payload failed: linkId={}, orderId={}, profile={}, terminal={}, status={}, reason={}", reservation.linkId(), reservation.orderId(), reservation.runtimeProfile().code(), reservation.terminalKey(), e instanceof ResponseStatusException statusException ? statusException.getStatusCode() : HttpStatus.BAD_GATEWAY, providerFailureReason(e));
                throw e;
            }
            BankInitApplyResult initResult = transactionExecutor.required(() -> applyBankInitResponse(reservation, response, true));
            requireSuccessfulBankInit(initResult);
            paymentId = initResult.paymentId();
            paymentUrl = initResult.paymentUrl();
        }
        TbankGetQrResponse qrResponse;
        try {
            qrResponse = tbankClient.getQr(reservation.runtimeProfile(), new TbankGetQrCommand(paymentId, "PAYLOAD", cleanBankId.isBlank() ? null : cleanBankId));
        } catch (RuntimeException e) {
            recordQrFailure(reservation, paymentId, e);
            log.warn("T-Bank GetQr failed: linkId={}, orderId={}, paymentId={}, profile={}, terminal={}, status={}, reason={}", reservation.linkId(), reservation.orderId(), maskPaymentId(paymentId), reservation.runtimeProfile().code(), reservation.terminalKey(), e instanceof ResponseStatusException statusException ? statusException.getStatusCode() : HttpStatus.BAD_GATEWAY, providerFailureReason(e));
            throw e;
        }
        String finalPaymentId = paymentId;
        String finalPaymentUrl = paymentUrl;
        return requireSuccessfulBankInit(transactionExecutor.required(() -> applyQrResponse(reservation, finalPaymentId, finalPaymentUrl, qrResponse)));
    }

    private PublicPaymentInitResponse initTochka(BankInitReservation reservation) {
        TochkaPaymentMode paymentMode = tochkaMode(reservation.mode());
        CreatePaymentResponse response = null;
        try {
            response = tochkaClient.createPaymentWithReceipt(reservation.tochkaProfile(), new TochkaCreatePaymentCommand(reservation.tbankOrderId(), reservation.amountKopecks(), reservation.description(), reservation.email(), properties.successUrl(), properties.failUrl(), List.of(paymentMode)));
            CreatePaymentResponse providerResponse = response;
            return requireSuccessfulBankInit(transactionExecutor.required(() -> applyTochkaBankInitResponse(reservation, providerResponse, paymentMode)));
        } catch (RuntimeException failure) {
            String operationId = response == null || response.data() == null ? "" : normalize(response.data().operationId());
            recordTochkaBankInitFailure(reservation, operationId, failure);
            log.warn("Tochka payment-link create failed: linkId={}, orderId={}, profile={}, merchant={}, mode={}, status={}, reason={}", reservation.linkId(), reservation.orderId(), reservation.tochkaProfile().code(), maskPaymentId(reservation.terminalKey()), paymentMode.code(), failure instanceof ResponseStatusException statusException ? statusException.getStatusCode() : HttpStatus.BAD_GATEWAY, tochkaProviderFailureReason(failure));
            throw failure;
        }
    }

    private BankInitApplyResult applyTochkaBankInitResponse(BankInitReservation reservation, CreatePaymentResponse response, TochkaPaymentMode paymentMode) {
        PaymentLink link = lockBankInitReservation(reservation);
        if (link == null) {
            return BankInitApplyResult.error(HttpStatus.CONFLICT, "Платежная ссылка изменилась во время инициализации в Точке");
        }
        String operationId = response == null || response.data() == null ? "" : normalize(response.data().operationId());
        if (!reservation.nonce().equals(normalize(link.getBankInitNonce()))) {
            if (normalize(link.getTbankPaymentId()).isBlank() && !operationId.isBlank()) {
                quarantineAmbiguousBankInit(link, operationId, "stale_tochka_create_response");
            }
            return BankInitApplyResult.error(HttpStatus.CONFLICT, "Получен устаревший ответ Точки; платеж отправлен на сверку");
        }
        if (!canApplyBankInitResponseTo(link.getStatus())) {
            quarantineAmbiguousBankInit(link, operationId, "link_retired_while_tochka_create_in_flight");
            return BankInitApplyResult.error(HttpStatus.CONFLICT, "Платежная ссылка закрылась во время обращения в Точку; платеж отправлен на сверку");
        }
        ExpectedPayment expected = new ExpectedPayment(operationId, reservation.tbankOrderId(), reservation.tochkaProfile().customerCode(), reservation.tochkaProfile().merchantId(), reservation.amountKopecks(), paymentMode);
        MappedPayment mapped = tochkaPaymentOperationMapper.map(response, expected, false);
        if (mapped.status() != PaymentLinkStatus.INITIATED || mapped.paymentMethod() != reservation.mode().paymentMethod()) {
            quarantineAmbiguousBankInit(link, operationId, "unexpected_tochka_create_state");
            return BankInitApplyResult.error(HttpStatus.BAD_GATEWAY, "Точка вернула неподдерживаемое состояние созданного платежа");
        }
        String paymentUrl;
        try {
            paymentUrl = PaymentUrlPolicy.require(response.data().paymentLink(), PaymentUrlPolicy.Purpose.TOCHKA_PAYMENT, HttpStatus.BAD_GATEWAY, "Точка вернула недопустимую ссылку оплаты");
        } catch (ResponseStatusException exception) {
            quarantineAmbiguousBankInit(link, operationId, "unsafe_tochka_payment_url: " + normalize(exception.getReason()));
            return BankInitApplyResult.error(HttpStatus.BAD_GATEWAY, normalize(exception.getReason()));
        }
        String currentPaymentId = normalize(link.getTbankPaymentId());
        if (!currentPaymentId.isBlank() && !currentPaymentId.equals(operationId)) {
            quarantineAmbiguousBankInit(link, currentPaymentId, "tochka_operation_binding_changed");
            return BankInitApplyResult.error(HttpStatus.CONFLICT, "Идентификатор операции Точки изменился во время инициализации");
        }
        BankInitApplyResult invalidated = rejectLateBankInitResultIfOrderChanged(link, operationId, "tochka_create_response");
        if (invalidated != null) {
            return invalidated;
        }
        PaymentLinkStatus statusBeforeApply = link.getStatus();
        link.setTbankPaymentId(operationId);
        link.setTbankTerminalKey(reservation.tochkaProfile().merchantId());
        link.setPaymentUrl(paymentUrl);
        link.setPaymentMethod(mapped.paymentMethod());
        link.setProviderTerminalStatus(mapped.providerStatus());
        link.setSbpQrPayload(null);
        link.setSbpQrImage(null);
        link.setSbpQrDataType(null);
        link.setSbpQrCreatedAt(null);
        if (statusBeforeApply == PaymentLinkStatus.CREATED || statusBeforeApply == PaymentLinkStatus.INITIATED) {
            link.setStatus(mapped.status());
        }
        if (link.getInitiatedAt() == null) {
            link.setInitiatedAt(LocalDateTime.now());
        }
        if (!isBankInitBusinessStateAuthoritative(statusBeforeApply)) {
            link.setLastError(null);
        }
        clearBankInitReservation(link);
        paymentLinkRepository.save(link);
        return BankInitApplyResult.success(new PublicPaymentInitResponse(paymentUrl, operationId, link.getStatus().name(), mapped.paymentMethod().name(), null, null), operationId, paymentUrl);
    }

    private BankInitReservation reserveBankInitialization(String token, String email, boolean offerConsent, boolean privacyConsent, boolean receiptConsent, String bankId, String clientIp, String userAgent, BankInitMode mode) {
        validateConsents(offerConsent, privacyConsent, receiptConsent);
        PaymentLink snapshot = findPublicLink(token);
        Long observedOrderId = snapshot.getOrder() == null ? null : snapshot.getOrder().getId();
        return transactionExecutor.requiredNoRollback(() -> reserveBankInitializationLocked(observedOrderId, token, email, bankId, clientIp, userAgent, mode));
    }

    private BankInitReservation reserveBankInitializationLocked(Long observedOrderId, String token, String email, String bankId, String clientIp, String userAgent, BankInitMode mode) {
        if (observedOrderId == null || orderRepository.findByIdForCounterUpdate(observedOrderId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ платежной ссылки не найден");
        }
        // The order lock serializes this check with common-invoice attachment.
        // A disclosed legacy token must not be able to create a second bank
        // payment after the order has moved under one common payment route.
        ensureOrderNotCoveredByActiveCommonInvoice(observedOrderId);
        PaymentLink source = findPublicLinkForUpdate(token);
        if (!hasOrderBinding(source, observedOrderId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Платежная ссылка изменилась; повторите запрос");
        }
        PaymentLink resolved = resolveReplacementPublicLink(source, LocalDateTime.now(), true);
        PaymentLink link = sameLinkId(source, resolved) ? source : lockResolvedPublicLink(resolved);
        if (!hasOrderBinding(link, observedOrderId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Платежная ссылка изменилась; повторите запрос");
        }
        LocalDateTime now = LocalDateTime.now();
        ensureNoCompetingBankPaymentForInit(link, paymentLinkRepository.findByOrderIdForUpdate(observedOrderId), now);
        handleExistingBankInitReservationBeforePayableValidation(link, now);
        validatePayable(link);
        validateTbankPayment(link);
        PaymentProfile profile = ensurePaymentProfile(link);
        TochkaPaymentProfile tochkaProfile = resolveActivatedTochkaProfile(profile);
        TbankPaymentProfile runtimeProfile = tochkaProfile == null ? normalize(link.getTbankPaymentId()).isBlank() ? paymentProfileService.toRuntime(profile) : runtimeProfileForLink(profile, link) : null;
        if (tochkaProfile != null) {
            requireTochkaModeAllowed(tochkaProfile, mode);
        }
        if (tochkaProfile != null && mode == BankInitMode.SBP_QR && !normalize(bankId).isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Для платежной ссылки Точки нельзя выбирать банк СБП заранее");
        }
        rejectUnsafeCachedBankTargets(link, mode, tochkaProfile != null);
        PublicPaymentInitResponse cached = cachedBankInitResponse(link, bankId, clientIp, userAgent, mode, tochkaProfile != null);
        if (cached != null) {
            return bankInitReservation(link, runtimeProfile, tochkaProfile, email, bankId, mode, null, cached);
        }
        link.setPayerEmail(email);
        applyConsentTrace(link, clientIp, userAgent);
        if (normalize(link.getTbankOrderId()).isBlank()) {
            link.setTbankOrderId(tbankOrderId(link));
        }
        link.setTbankTerminalKey(tochkaProfile == null ? runtimeProfile.terminalKey() : tochkaProfile.merchantId());
        if (tochkaProfile != null) {
            // The provider POST can succeed even when the client times out. Persist the
            // requested mode before that call so paymentLinkId recovery can validate the
            // recovered operation without guessing card versus SBP.
            link.setPaymentMethod(mode.paymentMethod());
            // The link may have existed for weeks before the customer pressed Pay. Recovery
            // must search around this POST attempt, not around PaymentLink.createdAt.
            link.setInitiatedAt(now);
        }
        String nonce = UUID.randomUUID().toString();
        link.setBankInitNonce(nonce);
        link.setBankInitLeaseUntil(now.plus(BANK_INIT_LEASE));
        paymentLinkRepository.save(link);
        return bankInitReservation(link, runtimeProfile, tochkaProfile, email, bankId, mode, nonce, null);
    }

    private void handleExistingBankInitReservationBeforePayableValidation(PaymentLink link, LocalDateTime now) {
        bankInitializationState.handleExistingBankInitReservationBeforePayableValidation(link, now);
    }

    private void ensureNoCompetingBankPaymentForInit(PaymentLink current, List<PaymentLink> orderLinks, LocalDateTime now) {
        bankInitializationState.ensureNoCompetingBankPaymentForInit(current, orderLinks, now);
    }

    private BankInitReservationRecovery recoverExpiredBankInitReservationLocked(PaymentLink link, LocalDateTime now, String source) {
        return bankInitializationState.recoverExpiredBankInitReservationLocked(link, now, source);
    }

    private void rejectUnsafeCachedBankTargets(PaymentLink link, BankInitMode mode, boolean tochkaProvider) {
        PaymentUrlPolicy.Purpose paymentUrlPurpose = tochkaProvider ? PaymentUrlPolicy.Purpose.TOCHKA_PAYMENT : PaymentUrlPolicy.Purpose.TBANK_PAYMENT;
        boolean unsafeCachedPaymentUrl = PaymentUrlPolicy.isUnsafeConfigured(link.getPaymentUrl(), paymentUrlPurpose);
        boolean missingCachedPaymentUrl = (tochkaProvider || mode == BankInitMode.BANK_FORM) && link.getStatus() == PaymentLinkStatus.INITIATED && !normalize(link.getTbankPaymentId()).isBlank() && PaymentUrlPolicy.safe(link.getPaymentUrl(), paymentUrlPurpose).isBlank();
        if (unsafeCachedPaymentUrl || missingCachedPaymentUrl) {
            quarantineUnsafeProviderTarget(link, mode.paymentMethod(), tochkaProvider ? "unsafe_cached_tochka_payment_url" : "unsafe_cached_tbank_payment_url", tochkaProvider ? "Сохраненная ссылка Точки отсутствует или имеет недопустимый формат" : "Сохраненная резервная ссылка Т-Банка отсутствует или имеет недопустимый формат");
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, tochkaProvider ? "Сохраненная ссылка Точки отсутствует или имеет недопустимый формат" : "Сохраненная резервная ссылка Т-Банка отсутствует или имеет недопустимый формат");
        }
        if (!tochkaProvider && mode == BankInitMode.SBP_QR && PaymentUrlPolicy.isUnsafeConfigured(link.getSbpQrPayload(), PaymentUrlPolicy.Purpose.SBP_PAYLOAD)) {
            quarantineUnsafeProviderTarget(link, PaymentMethod.SBP_QR, "unsafe_cached_tbank_sbp_payload", "Сохраненная ссылка СБП имеет недопустимый формат");
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Сохраненная ссылка СБП имеет недопустимый формат");
        }
    }

    private PublicPaymentInitResponse cachedBankInitResponse(PaymentLink link, String bankId, String clientIp, String userAgent, BankInitMode mode, boolean tochkaProvider) {
        if (tochkaProvider) {
            return cachedTochkaBankInitResponse(link, bankId, clientIp, userAgent, mode);
        }
        if (mode == BankInitMode.BANK_FORM && !normalize(link.getPaymentUrl()).isBlank() && link.getStatus() == PaymentLinkStatus.INITIATED) {
            String paymentUrl = PaymentUrlPolicy.require(link.getPaymentUrl(), PaymentUrlPolicy.Purpose.TBANK_PAYMENT, HttpStatus.BAD_GATEWAY, "Сохраненная ссылка Т-Банка имеет недопустимый формат");
            applyConsentTrace(link, clientIp, userAgent);
            link.setPaymentMethod(PaymentMethod.BANK_FORM);
            paymentLinkRepository.save(link);
            return new PublicPaymentInitResponse(paymentUrl, link.getTbankPaymentId(), link.getStatus().name());
        }
        if (mode == BankInitMode.SBP_QR && link.getPaymentMethod() == PaymentMethod.SBP_QR && !normalize(link.getSbpQrPayload()).isBlank() && PaymentUrlPolicy.isGenericSbpPayload(link.getSbpQrPayload()) && normalize(bankId).isBlank() && link.getStatus() == PaymentLinkStatus.INITIATED) {
            String paymentUrl = PaymentUrlPolicy.optional(link.getPaymentUrl(), PaymentUrlPolicy.Purpose.TBANK_PAYMENT, HttpStatus.BAD_GATEWAY, "Сохраненная резервная ссылка Т-Банка имеет недопустимый формат");
            String qrPayload = PaymentUrlPolicy.require(link.getSbpQrPayload(), PaymentUrlPolicy.Purpose.SBP_PAYLOAD, HttpStatus.BAD_GATEWAY, "Сохраненная ссылка СБП имеет недопустимый формат");
            applyConsentTrace(link, clientIp, userAgent);
            paymentLinkRepository.save(link);
            return new PublicPaymentInitResponse(paymentUrl, link.getTbankPaymentId(), link.getStatus().name(), PaymentMethod.SBP_QR.name(), qrPayload, null);
        }
        return null;
    }

    private PublicPaymentInitResponse cachedTochkaBankInitResponse(PaymentLink link, String bankId, String clientIp, String userAgent, BankInitMode mode) {
        if (link.getStatus() != PaymentLinkStatus.INITIATED || normalize(link.getTbankPaymentId()).isBlank() || normalize(link.getPaymentUrl()).isBlank()) {
            return null;
        }
        if (mode == BankInitMode.SBP_QR && !normalize(bankId).isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Для платежной ссылки Точки нельзя выбирать банк СБП заранее");
        }
        if (link.getPaymentMethod() != mode.paymentMethod()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Платежная ссылка Точки уже создана для другого способа оплаты");
        }
        String paymentUrl = PaymentUrlPolicy.require(link.getPaymentUrl(), PaymentUrlPolicy.Purpose.TOCHKA_PAYMENT, HttpStatus.BAD_GATEWAY, "Сохраненная ссылка Точки имеет недопустимый формат");
        applyConsentTrace(link, clientIp, userAgent);
        paymentLinkRepository.save(link);
        return new PublicPaymentInitResponse(paymentUrl, link.getTbankPaymentId(), link.getStatus().name(), mode.paymentMethod().name(), null, null);
    }

    private BankInitReservation bankInitReservation(PaymentLink link, TbankPaymentProfile runtimeProfile, TochkaPaymentProfile tochkaProfile, String email, String bankId, BankInitMode mode, String nonce, PublicPaymentInitResponse cachedResponse) {
        return new BankInitReservation(link.getId(), link.getOrder() == null ? null : link.getOrder().getId(), normalize(link.getToken()), nonce, normalize(link.getTbankOrderId()), normalize(link.getTbankPaymentId()), normalize(link.getPaymentUrl()), link.getAmountKopecks(), normalize(link.getDescription()), email, normalize(bankId), normalize(tochkaProfile == null ? runtimeProfile.terminalKey() : tochkaProfile.merchantId()), runtimeProfile, tochkaProfile, mode, cachedResponse);
    }

    private BankInitApplyResult applyBankInitResponse(BankInitReservation reservation, TbankInitResponse response, boolean keepLeaseForQr) {
        PaymentLink link = lockBankInitReservation(reservation);
        if (link == null) {
            return BankInitApplyResult.error(HttpStatus.CONFLICT, "Платежная ссылка изменилась во время инициализации");
        }
        String responsePaymentId = normalize(response == null ? null : response.paymentId());
        if (!reservation.nonce().equals(normalize(link.getBankInitNonce()))) {
            if (normalize(link.getTbankPaymentId()).isBlank() && !responsePaymentId.isBlank()) {
                quarantineAmbiguousBankInit(link, responsePaymentId, "stale_init_response");
            }
            return BankInitApplyResult.error(HttpStatus.CONFLICT, "Получен устаревший ответ банка; платеж отправлен на сверку");
        }
        if (!canApplyBankInitResponseTo(link.getStatus())) {
            quarantineAmbiguousBankInit(link, responsePaymentId, "link_retired_while_init_in_flight");
            return BankInitApplyResult.error(HttpStatus.CONFLICT, "Платежная ссылка закрылась во время инициализации; платеж отправлен на сверку");
        }
        if (!consistentInitResponse(reservation, response) || responsePaymentId.isBlank()) {
            quarantineAmbiguousBankInit(link, responsePaymentId, "inconsistent_provider_response");
            return BankInitApplyResult.error(HttpStatus.BAD_GATEWAY, "Т-Банк вернул несогласованный ответ Init");
        }
        String paymentUrl;
        try {
            paymentUrl = reservation.mode() == BankInitMode.BANK_FORM ? PaymentUrlPolicy.require(response.paymentUrl(), PaymentUrlPolicy.Purpose.TBANK_PAYMENT, HttpStatus.BAD_GATEWAY, "Т-Банк вернул недопустимую ссылку оплаты") : PaymentUrlPolicy.optional(response.paymentUrl(), PaymentUrlPolicy.Purpose.TBANK_PAYMENT, HttpStatus.BAD_GATEWAY, "Т-Банк вернул недопустимую резервную ссылку оплаты");
        } catch (ResponseStatusException e) {
            boolean quarantined = quarantineAmbiguousBankInit(link, responsePaymentId, "unsafe_tbank_payment_url: " + normalize(e.getReason()));
            if (quarantined) {
                link.setPaymentMethod(reservation.mode().paymentMethod());
                link.setPaymentUrl(null);
                link.setLastError(limit("unsafe_tbank_payment_url: " + normalize(e.getReason()), 512));
                paymentLinkRepository.save(link);
            }
            return BankInitApplyResult.error(HttpStatus.BAD_GATEWAY, normalize(e.getReason()));
        }
        String currentPaymentId = normalize(link.getTbankPaymentId());
        if (!currentPaymentId.isBlank() && !currentPaymentId.equals(responsePaymentId)) {
            quarantineAmbiguousBankInit(link, currentPaymentId, "provider_payment_binding_changed");
            return BankInitApplyResult.error(HttpStatus.CONFLICT, "PaymentId изменился во время инициализации");
        }
        BankInitApplyResult invalidated = rejectLateBankInitResultIfOrderChanged(link, responsePaymentId, "init_response");
        if (invalidated != null) {
            return invalidated;
        }
        PaymentLinkStatus statusBeforeApply = link.getStatus();
        link.setTbankPaymentId(responsePaymentId);
        link.setPaymentUrl(paymentUrl);
        link.setPaymentMethod(reservation.mode().paymentMethod());
        if (link.getStatus() == PaymentLinkStatus.CREATED) {
            link.setStatus(PaymentLinkStatus.INITIATED);
        }
        if (link.getInitiatedAt() == null) {
            link.setInitiatedAt(LocalDateTime.now());
        }
        if (!isBankInitBusinessStateAuthoritative(statusBeforeApply)) {
            link.setLastError(null);
        }
        if (keepLeaseForQr) {
            link.setBankInitLeaseUntil(LocalDateTime.now().plus(BANK_INIT_LEASE));
        } else {
            clearBankInitReservation(link);
        }
        paymentLinkRepository.save(link);
        PublicPaymentInitResponse publicResponse = keepLeaseForQr ? null : new PublicPaymentInitResponse(paymentUrl, responsePaymentId, link.getStatus().name());
        return BankInitApplyResult.success(publicResponse, responsePaymentId, paymentUrl);
    }

    private BankInitApplyResult applyQrResponse(BankInitReservation reservation, String paymentId, String paymentUrl, TbankGetQrResponse response) {
        PaymentLink link = lockBankInitReservation(reservation);
        if (link == null || !reservation.nonce().equals(normalize(link.getBankInitNonce())) || !normalize(link.getTbankPaymentId()).equals(normalize(paymentId))) {
            return BankInitApplyResult.error(HttpStatus.CONFLICT, "Платеж изменился во время получения СБП-ссылки");
        }
        String responseTerminal = normalize(response == null ? null : response.terminalKey());
        String responsePaymentId = normalize(response == null ? null : response.paymentId());
        String responseErrorCode = normalize(response == null ? null : response.errorCode());
        if (response == null || !response.success() || (!responseErrorCode.isBlank() && !"0".equals(responseErrorCode)) || (!responseTerminal.isBlank() && !responseTerminal.equals(reservation.terminalKey())) || (!responsePaymentId.isBlank() && !responsePaymentId.equals(normalize(paymentId)))) {
            quarantineAmbiguousBankInit(link, paymentId, "inconsistent_get_qr_response");
            return BankInitApplyResult.error(HttpStatus.BAD_GATEWAY, "Т-Банк вернул несогласованный ответ GetQr");
        }
        String qrPayload = normalize(response == null ? null : response.data());
        try {
            qrPayload = PaymentUrlPolicy.require(qrPayload, PaymentUrlPolicy.Purpose.SBP_PAYLOAD, HttpStatus.BAD_GATEWAY, qrPayload.isBlank() ? "Т-Банк не вернул ссылку СБП" : "Т-Банк вернул недопустимую ссылку СБП");
            paymentUrl = PaymentUrlPolicy.optional(paymentUrl, PaymentUrlPolicy.Purpose.TBANK_PAYMENT, HttpStatus.BAD_GATEWAY, "Т-Банк вернул недопустимую резервную ссылку оплаты");
        } catch (ResponseStatusException e) {
            boolean quarantined = quarantineAmbiguousBankInit(link, paymentId, "unsafe_tbank_sbp_payload: " + normalize(e.getReason()));
            if (quarantined) {
                link.setPaymentMethod(PaymentMethod.SBP_QR);
                link.setSbpQrPayload(null);
                link.setSbpQrImage(null);
                link.setSbpQrDataType(null);
                link.setSbpQrCreatedAt(null);
                link.setLastError(limit("unsafe_tbank_sbp_payload: " + normalize(e.getReason()), 512));
                paymentLinkRepository.save(link);
            }
            return BankInitApplyResult.error(HttpStatus.BAD_GATEWAY, normalize(e.getReason()));
        }
        BankInitApplyResult invalidated = rejectLateBankInitResultIfOrderChanged(link, paymentId, "get_qr_response");
        if (invalidated != null) {
            return invalidated;
        }
        PaymentLinkStatus statusBeforeApply = link.getStatus();
        if (statusBeforeApply == PaymentLinkStatus.CREATED) {
            link.setStatus(PaymentLinkStatus.INITIATED);
        }
        link.setPaymentMethod(PaymentMethod.SBP_QR);
        link.setTbankTerminalKey(reservation.terminalKey());
        link.setPaymentUrl(paymentUrl);
        link.setSbpQrImage(null);
        link.setSbpQrPayload(qrPayload);
        link.setSbpQrDataType("PAYLOAD");
        link.setSbpQrCreatedAt(LocalDateTime.now());
        if (!isBankInitBusinessStateAuthoritative(statusBeforeApply)) {
            link.setLastError(null);
        }
        clearBankInitReservation(link);
        paymentLinkRepository.save(link);
        return BankInitApplyResult.success(new PublicPaymentInitResponse(paymentUrl, paymentId, link.getStatus().name(), PaymentMethod.SBP_QR.name(), qrPayload, null), paymentId, paymentUrl);
    }

    private BankInitApplyResult rejectLateBankInitResultIfOrderChanged(PaymentLink link, String paymentId, String phase) {
        boolean expired = link.getExpiresAt() != null && link.getExpiresAt().isBefore(LocalDateTime.now());
        boolean amountChanged = isAmountChanged(link);
        ResponseStatusException settledOrder = null;
        if (!isSameConfirmedBankPayment(link, paymentId)) {
            try {
                orderPaymentIntegrityService.assertPaymentCycleAllowed(link.getOrder());
            } catch (ResponseStatusException conflict) {
                settledOrder = conflict;
            }
        }
        if (!expired && !amountChanged && settledOrder == null) {
            return null;
        }
        String reason = expired ? "link_expired_during_" + normalize(phase) : amountChanged ? "order_amount_changed_during_" + normalize(phase) : "order_settled_during_" + normalize(phase);
        quarantineAmbiguousBankInit(link, paymentId, reason);
        return BankInitApplyResult.error(HttpStatus.CONFLICT, expired ? "Срок платежной ссылки истек во время обращения к банку; платеж отправлен на сверку" : amountChanged ? "Сумма заказа изменилась во время обращения к банку; платеж отправлен на сверку" : "Заказ был оплачен во время обращения к банку; новый платеж отправлен на сверку");
    }

    /**
     * A verified webhook may confirm this exact payment before the synchronous
     * Init response reaches us. In that case the order is already settled by
     * this link, so the generic order-cycle guard must not misclassify the
     * matching response as a competing late payment.
     */
    private boolean isSameConfirmedBankPayment(PaymentLink link, String paymentId) {
        if (link == null || link.getStatus() != PaymentLinkStatus.CONFIRMED) {
            return false;
        }
        String currentPaymentId = normalize(link.getTbankPaymentId());
        return !currentPaymentId.isBlank() && currentPaymentId.equals(normalize(paymentId));
    }

    private PaymentLink lockBankInitReservation(BankInitReservation reservation) {
        if (reservation == null || reservation.orderId() == null || orderRepository.findByIdForCounterUpdate(reservation.orderId()).isEmpty()) {
            return null;
        }
        PaymentLink link = reservation.linkId() == null ? findPublicLinkForUpdate(reservation.token()) : paymentLinkRepository.findByIdForUpdate(reservation.linkId()).orElse(null);
        if (!hasOrderBinding(link, reservation.orderId()) || link.getAmountKopecks() != reservation.amountKopecks() || !normalize(link.getToken()).equals(reservation.token()) || !normalize(link.getTbankOrderId()).equals(reservation.tbankOrderId()) || !normalize(link.getTbankTerminalKey()).equals(reservation.terminalKey())) {
            return null;
        }
        return link;
    }

    private boolean consistentInitResponse(BankInitReservation reservation, TbankInitResponse response) {
        if (response == null) {
            return false;
        }
        String responseOrderId = normalize(response.orderId());
        String responseTerminal = normalize(response.terminalKey());
        return (responseOrderId.isBlank() || responseOrderId.equals(reservation.tbankOrderId())) && (responseTerminal.isBlank() || responseTerminal.equals(reservation.terminalKey())) && (response.amount() == null || response.amount() == reservation.amountKopecks());
    }

    private void recordAmbiguousBankInitFailure(BankInitReservation reservation, RuntimeException failure) {
        transactionExecutor.required(() -> {
            PaymentLink link = lockBankInitReservation(reservation);
            if (link != null && reservation.nonce().equals(normalize(link.getBankInitNonce()))) {
                quarantineAmbiguousBankInit(link, null, providerFailureReason(failure));
            }
            return null;
        });
    }

    private void recordTochkaBankInitFailure(BankInitReservation reservation, String observedOperationId, RuntimeException failure) {
        transactionExecutor.required(() -> {
            PaymentLink link = lockBankInitReservation(reservation);
            if (link == null || !reservation.nonce().equals(normalize(link.getBankInitNonce()))) {
                return null;
            }
            boolean outcomeUnknown = !normalize(observedOperationId).isBlank() || !(failure instanceof TochkaProviderException providerFailure) || providerFailure.isOutcomeUnknown();
            if (outcomeUnknown) {
                // Never bind the operation id from a failed/invalid create response. The
                // stable merchant-generated paymentLinkId is the only trustworthy recovery
                // key. Keep the durable reservation so the scheduler can perform GET-list
                // recovery and, critically, never repeat the POST.
                if (isBankInitBusinessStateAuthoritative(link.getStatus())) {
                    // A concurrent verified observation (typically APPROVED) won the race.
                    // Never regress a paid/refunded/closed state to reconciliation merely
                    // because the original HTTP client did not receive its create response.
                    clearBankInitReservation(link);
                } else {
                    link.setStatus(PaymentLinkStatus.NEEDS_RECONCILIATION);
                    link.setPaymentUrl(null);
                    link.setLastError(limit(BANK_INIT_AMBIGUOUS_PREFIX + " tochka_create_failed: " + tochkaProviderFailureReason(failure), 512));
                    if (link.getBankInitLeaseUntil() == null) {
                        link.setBankInitLeaseUntil(LocalDateTime.now().plus(BANK_INIT_LEASE));
                    }
                }
                paymentLinkRepository.save(link);
                return null;
            }
            clearBankInitReservation(link);
            if (!isBankInitBusinessStateAuthoritative(link.getStatus())) {
                link.setInitiatedAt(null);
                link.setLastError(limit("tochka_create_rejected: " + tochkaProviderFailureReason(failure), 512));
            }
            paymentLinkRepository.save(link);
            return null;
        });
    }

    private void recordQrFailure(BankInitReservation reservation, String paymentId, RuntimeException failure) {
        transactionExecutor.required(() -> {
            PaymentLink link = lockBankInitReservation(reservation);
            if (link != null && reservation.nonce().equals(normalize(link.getBankInitNonce())) && normalize(link.getTbankPaymentId()).equals(normalize(paymentId))) {
                clearBankInitReservation(link);
                if (!isBankInitBusinessStateAuthoritative(link.getStatus())) {
                    link.setLastError(limit("tbank_get_qr_failed: " + providerFailureReason(failure), 512));
                }
                paymentLinkRepository.save(link);
            }
            return null;
        });
    }

    private boolean quarantineAmbiguousBankInit(PaymentLink link, String paymentId, String reason) {
        return bankInitializationState.quarantineAmbiguousBankInit(link, paymentId, reason);
    }

    private void clearBankInitReservation(PaymentLink link) {
        bankInitializationState.clearBankInitReservation(link);
    }

    private boolean canApplyBankInitResponseTo(PaymentLinkStatus status) {
        return bankInitializationState.canApplyBankInitResponseTo(status);
    }

    private boolean isBankInitBusinessStateAuthoritative(PaymentLinkStatus status) {
        return bankInitializationState.isBankInitBusinessStateAuthoritative(status);
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

    private PublicPaymentInitResponse requireSuccessfulBankInit(BankInitApplyResult result) {
        if (result == null || result.errorStatus() != null) {
            HttpStatus status = result == null ? HttpStatus.CONFLICT : result.errorStatus();
            String reason = result == null ? "Платеж изменился во время инициализации" : result.errorReason();
            throw new ResponseStatusException(status, reason);
        }
        return result.response();
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean recoverExpiredBankInitReservation(Long linkId, LocalDateTime expiredBefore) {
        if (linkId == null || linkId <= 0) {
            return false;
        }
        PaymentLink snapshot = paymentLinkRepository.findByIdWithOrder(linkId).orElse(null);
        Long orderId = snapshot == null || snapshot.getOrder() == null ? null : snapshot.getOrder().getId();
        LocalDateTime cutoff = expiredBefore == null ? LocalDateTime.now() : expiredBefore;
        if (isAmbiguousTochkaCreate(snapshot, cutoff)) {
            return recoverAmbiguousTochkaCreate(snapshot, cutoff);
        }
        return transactionExecutor.requiredNoRollback(() -> {
            if (orderId == null || orderRepository.findByIdForCounterUpdate(orderId).isEmpty()) {
                return false;
            }
            PaymentLink link = paymentLinkRepository.findByIdForUpdate(linkId).orElse(null);
            if (!hasOrderBinding(link, orderId) || !hasBankInitReservation(link) || (link.getBankInitLeaseUntil() != null && link.getBankInitLeaseUntil().isAfter(cutoff))) {
                return false;
            }
            BankInitReservationRecovery recovery = recoverExpiredBankInitReservationLocked(link, cutoff, "scheduled_recovery");
            return recovery == BankInitReservationRecovery.RETRYABLE_RECOVERED || recovery == BankInitReservationRecovery.QUARANTINED;
        });
    }

    private boolean isAmbiguousTochkaCreate(PaymentLink link, LocalDateTime cutoff) {
        return link != null && isTochkaPaymentLink(link) && hasBankInitReservation(link) && normalize(link.getTbankPaymentId()).isBlank() && (link.getBankInitLeaseUntil() == null || !link.getBankInitLeaseUntil().isAfter(cutoff));
    }

    private boolean recoverAmbiguousTochkaCreate(PaymentLink snapshot, LocalDateTime cutoff) {
        TochkaInitRecoveryObservation observation = requestAmbiguousTochkaCreateRecovery(snapshot);
        Long orderId = snapshot.getOrder() == null ? null : snapshot.getOrder().getId();
        return transactionExecutor.requiredNoRollback(() -> applyAmbiguousTochkaCreateRecovery(snapshot, orderId, cutoff, observation));
    }

    private TochkaInitRecoveryObservation requestAmbiguousTochkaCreateRecovery(PaymentLink snapshot) {
        String nonce = normalize(snapshot.getBankInitNonce());
        String paymentLinkId = normalize(snapshot.getTbankOrderId());
        try {
            if (nonce.isBlank() || paymentLinkId.isBlank()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Не сохранена привязка неоднозначного платежа Точки");
            }
            PaymentProfile entityProfile = snapshot.getPaymentProfile();
            TochkaPaymentProfile runtimeProfile = tochkaPaymentProfileResolver.resolveForExistingPayment(entityProfile);
            TochkaPaymentMode expectedMode = expectedTochkaMode(snapshot.getPaymentMethod());
            if (!normalize(snapshot.getTbankTerminalKey()).equals(runtimeProfile.merchantId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "MerchantId профиля Точки изменился до сверки");
            }
            LocalDate attemptDate = tochkaCreateAttemptDate(snapshot);
            LocalDate fromDate = attemptDate.minusDays(1);
            LocalDate toDate = fromDate.plusDays(6);
            Optional<PaymentOperation> recovered = tochkaClient.findPaymentByPaymentLinkId(runtimeProfile, paymentLinkId, snapshot.getAmountKopecks(), fromDate, toDate);
            if (recovered.isEmpty()) {
                return TochkaInitRecoveryObservation.notFound(nonce, snapshot.getBankInitLeaseUntil(), entityProfile == null ? null : entityProfile.getId(), expectedMode, runtimeProfile);
            }
            PaymentOperation operation = recovered.get();
            MappedPayment mapped = tochkaPaymentOperationMapper.map(operation, new ExpectedPayment(normalize(operation.operationId()), paymentLinkId, runtimeProfile.customerCode(), runtimeProfile.merchantId(), snapshot.getAmountKopecks(), expectedMode), false);
            String paymentUrl = mapped.status() == PaymentLinkStatus.INITIATED ? PaymentUrlPolicy.require(operation.paymentLink(), PaymentUrlPolicy.Purpose.TOCHKA_PAYMENT, HttpStatus.BAD_GATEWAY, "Точка вернула недопустимую ссылку восстановленного платежа") : PaymentUrlPolicy.optional(operation.paymentLink(), PaymentUrlPolicy.Purpose.TOCHKA_PAYMENT, HttpStatus.BAD_GATEWAY, "Точка вернула недопустимую ссылку восстановленного платежа");
            return TochkaInitRecoveryObservation.found(nonce, snapshot.getBankInitLeaseUntil(), entityProfile == null ? null : entityProfile.getId(), expectedMode, runtimeProfile, operation, mapped, paymentUrl);
        } catch (RuntimeException failure) {
            return TochkaInitRecoveryObservation.failed(nonce, snapshot.getBankInitLeaseUntil(), snapshot.getPaymentProfile() == null ? null : snapshot.getPaymentProfile().getId(), "tochka_create_recovery_failed: " + tochkaProviderFailureReason(failure));
        }
    }

    private LocalDate tochkaCreateAttemptDate(PaymentLink link) {
        if (link != null && link.getInitiatedAt() != null) {
            return link.getInitiatedAt().toLocalDate();
        }
        if (link != null && link.getBankInitLeaseUntil() != null) {
            // Compatibility for reservations created before initiatedAt became the durable
            // attempt timestamp. A fresh lease was persisted immediately before the POST.
            return link.getBankInitLeaseUntil().minus(BANK_INIT_LEASE).toLocalDate();
        }
        return link == null || link.getCreatedAt() == null ? LocalDate.now() : link.getCreatedAt().toLocalDate();
    }

    private boolean applyAmbiguousTochkaCreateRecovery(PaymentLink snapshot, Long orderId, LocalDateTime cutoff, TochkaInitRecoveryObservation observation) {
        if (orderId == null || orderRepository.findByIdForCounterUpdate(orderId).isEmpty()) {
            return false;
        }
        PaymentLink link = paymentLinkRepository.findByIdForUpdate(snapshot.getId()).orElse(null);
        if (!matchesTochkaInitRecoveryBinding(link, snapshot, observation, orderId, cutoff)) {
            return false;
        }
        link.setBankReconciliationAttemptedAt(LocalDateTime.now());
        if (isBankInitBusinessStateAuthoritative(link.getStatus())) {
            clearBankInitReservation(link);
            paymentLinkRepository.save(link);
            return true;
        }
        if (!normalize(observation.failureReason()).isBlank() || !observation.found()) {
            link.setStatus(PaymentLinkStatus.NEEDS_RECONCILIATION);
            link.setPaymentUrl(null);
            link.setBankInitLeaseUntil(LocalDateTime.now().plus(BANK_INIT_LEASE));
            link.setLastError(limit(BANK_INIT_AMBIGUOUS_PREFIX + " " + (normalize(observation.failureReason()).isBlank() ? "tochka_payment_not_found_by_payment_link_id" : observation.failureReason()), 512));
            paymentLinkRepository.save(link);
            return true;
        }
        TochkaPaymentProfile currentRuntimeProfile;
        try {
            currentRuntimeProfile = tochkaPaymentProfileResolver.resolveForExistingPayment(link.getPaymentProfile());
        } catch (RuntimeException failure) {
            currentRuntimeProfile = null;
        }
        if (currentRuntimeProfile == null || observation.runtimeProfile() == null || !currentRuntimeProfile.customerCode().equals(observation.runtimeProfile().customerCode()) || !currentRuntimeProfile.merchantId().equals(observation.runtimeProfile().merchantId()) || currentRuntimeProfile.testMode() != observation.runtimeProfile().testMode()) {
            link.setStatus(PaymentLinkStatus.NEEDS_RECONCILIATION);
            link.setPaymentUrl(null);
            link.setBankInitLeaseUntil(LocalDateTime.now().plus(BANK_INIT_LEASE));
            link.setLastError(limit(BANK_INIT_AMBIGUOUS_PREFIX + " tochka_profile_changed_during_create_recovery", 512));
            paymentLinkRepository.save(link);
            return true;
        }
        PaymentOperation operation = observation.operation();
        MappedPayment mapped = observation.mapped();
        BankInitApplyResult invalidated = rejectLateBankInitResultIfOrderChanged(link, operation.operationId(), "tochka_create_recovery");
        if (invalidated != null) {
            return true;
        }
        PaymentLinkStatus before = link.getStatus();
        link.setTbankPaymentId(operation.operationId());
        link.setTbankTerminalKey(currentRuntimeProfile.merchantId());
        link.setPaymentMethod(mapped.paymentMethod());
        link.setProviderTerminalStatus(mapped.providerStatus());
        link.setPaymentUrl(normalize(observation.paymentUrl()).isBlank() ? null : observation.paymentUrl());
        if (link.getInitiatedAt() == null) {
            link.setInitiatedAt(LocalDateTime.now());
        }
        clearBankInitReservation(link);
        applyPaymentProfile(link, link.getPaymentProfile());
        if (mapped.status() == PaymentLinkStatus.INITIATED) {
            // This is the one safe NEEDS -> INITIATED transition: the durable
            // paymentLinkId lookup found and strictly validated the exact operation.
            link.setStatus(PaymentLinkStatus.INITIATED);
            link.setLastError(null);
        } else {
            applyTochkaMappedStatus(link, mapped, currentRuntimeProfile.testMode());
        }
        paymentLinkRepository.save(link);
        if (isTochkaRefundStatus(link.getStatus()) && before != link.getStatus()) {
            paymentLinkReturnOutboxService.enqueue(link);
            reconcileContractorPaymentRouteAfterCommit(link.getId());
        }
        return true;
    }

    private boolean matchesTochkaInitRecoveryBinding(PaymentLink link, PaymentLink snapshot, TochkaInitRecoveryObservation observation, Long orderId, LocalDateTime cutoff) {
        return link != null && observation != null && hasOrderBinding(link, orderId) && isTochkaPaymentLink(link) && hasBankInitReservation(link) && normalize(link.getTbankPaymentId()).isBlank() && normalize(link.getBankInitNonce()).equals(observation.nonce()) && Objects.equals(link.getBankInitLeaseUntil(), observation.leaseUntil()) && (link.getBankInitLeaseUntil() == null || !link.getBankInitLeaseUntil().isAfter(cutoff)) && normalize(link.getTbankOrderId()).equals(normalize(snapshot.getTbankOrderId())) && link.getAmountKopecks() == snapshot.getAmountKopecks() && link.getPaymentMethod() == snapshot.getPaymentMethod() && normalize(link.getTbankTerminalKey()).equals(normalize(snapshot.getTbankTerminalKey())) && Objects.equals(link.getPaymentProfile() == null ? null : link.getPaymentProfile().getId(), observation.profileId()) && (observation.expectedMode() == null || observation.expectedMode() == expectedTochkaMode(link.getPaymentMethod()));
    }

    private PaymentLink findPublicLink(String token) {
        return publicLinkResolution.findPublicLink(token);
    }

    private PaymentLink findPublicLinkForUpdate(String token) {
        return publicLinkResolution.findPublicLinkForUpdate(token);
    }

    private PaymentLink lockResolvedPublicLink(PaymentLink link) {
        return publicLinkResolution.lockResolvedPublicLink(link);
    }

    private PaymentLink resolveReplacementPublicLink(PaymentLink link, LocalDateTime now, boolean createIfMissing) {
        return publicLinkResolution.resolveReplacementPublicLink(link, now, createIfMissing);
    }

    private boolean sameLinkId(PaymentLink left, PaymentLink right) {
        Long leftId = left == null ? null : left.getId();
        Long rightId = right == null ? null : right.getId();
        return leftId != null && leftId.equals(rightId);
    }

    private void validatePayable(PaymentLink link) {
        lifecycleService.validatePayable(link);
    }

    private void validatePayable(PaymentLink link, boolean releaseTaskReservationOnExpiry) {
        lifecycleService.validatePayable(link, releaseTaskReservationOnExpiry);
    }

    private boolean isAmountChanged(PaymentLink link) {
        return lifecycleService.isAmountChanged(link);
    }

    void validateTbankPayment(PaymentLink link) {
        lifecycleService.validateTbankPayment(link);
    }

    private TochkaPaymentProfile resolveActivatedTochkaProfile(PaymentProfile profile) {
        if (!paymentProfileService.isTochkaProvider(profile)) {
            return null;
        }
        TochkaPaymentProfile resolved = tochkaPaymentProfileResolver.resolve(profile);
        if (resolved == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Интернет-эквайринг Точки выключен или не настроен");
        }
        return resolved;
    }

    private void validateConsents(boolean offerConsent, boolean privacyConsent, boolean receiptConsent) {
        if (!offerConsent || !privacyConsent || !receiptConsent) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Подтвердите оферту, политику персональных данных и согласие на электронный чек");
        }
    }

    private void applyConsentTrace(PaymentLink link, String clientIp, String userAgent) {
        LocalDateTime now = LocalDateTime.now();
        link.setOfferConsentAt(now);
        link.setPrivacyConsentAt(now);
        link.setReceiptConsentAt(now);
        link.setConsentIp(limit(clientIp, 128));
        link.setConsentUserAgent(limit(userAgent, 512));
        link.setOfferDocumentUrl(publicDocumentUrl(OFFER_PATH));
        link.setPrivacyDocumentUrl(publicDocumentUrl(PRIVACY_PATH));
        link.setReceiptConsentDocumentUrl(publicDocumentUrl(RECEIPT_CONSENT_PATH));
    }

    private AdminPaymentLinkResponse toAdminResponse(PaymentLink link) {
        return paymentPresenter.toAdminResponse(link);
    }

    private PaymentProfile ensurePaymentProfile(PaymentLink link) {
        PaymentProfile profile = resolvePaymentProfile(link);
        applyPaymentProfile(link, profile);
        return profile;
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

    private PaymentLinkStatus statusAfterUnsafeProviderUrl(String paymentId) {
        return normalize(paymentId).isBlank() ? PaymentLinkStatus.FAILED : PaymentLinkStatus.NEEDS_RECONCILIATION;
    }

    private void quarantineUnsafeProviderTarget(PaymentLink link, PaymentMethod paymentMethod, String errorCode, String reason) {
        link.setStatus(statusAfterUnsafeProviderUrl(link.getTbankPaymentId()));
        link.setPaymentMethod(paymentMethod);
        link.setPaymentUrl(null);
        if (paymentMethod == PaymentMethod.SBP_QR) {
            link.setSbpQrPayload(null);
            link.setSbpQrImage(null);
            link.setSbpQrDataType(null);
            link.setSbpQrCreatedAt(null);
        }
        if (link.getInitiatedAt() == null && !normalize(link.getTbankPaymentId()).isBlank()) {
            link.setInitiatedAt(LocalDateTime.now());
        }
        link.setLastError(limit(errorCode + ": " + normalize(reason), 512));
        paymentLinkRepository.save(link);
    }

    private String publicDocumentUrl(String path) {
        String baseUrl = normalize(properties.getPublicBaseUrl());
        if (baseUrl.isBlank()) {
            return path;
        }
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl + path;
    }

    private String tbankOrderId(PaymentLink link) {
        if (link.getTbankOrderId() != null && !link.getTbankOrderId().isBlank()) {
            return link.getTbankOrderId();
        }
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Long orderId = link.getOrder() == null ? 0L : link.getOrder().getId();
        return ("o" + orderId + "-" + suffix).substring(0, Math.min(36, ("o" + orderId + "-" + suffix).length()));
    }

    private boolean isManualPayment(PaymentLink link) {
        return paymentPresenter.isManualPayment(link);
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

    enum BankInitMode {

        BANK_FORM(PaymentMethod.BANK_FORM), SBP_QR(PaymentMethod.SBP_QR);

        private final PaymentMethod paymentMethod;

        BankInitMode(PaymentMethod paymentMethod) {
            this.paymentMethod = paymentMethod;
        }

        private PaymentMethod paymentMethod() {
            return paymentMethod;
        }
    }

    record BankInitReservation(Long linkId, Long orderId, String token, String nonce, String tbankOrderId, String paymentId, String paymentUrl, long amountKopecks, String description, String email, String bankId, String terminalKey, TbankPaymentProfile runtimeProfile, TochkaPaymentProfile tochkaProfile, BankInitMode mode, PublicPaymentInitResponse cachedResponse) {
    }

    record TochkaInitRecoveryObservation(boolean found, String nonce, LocalDateTime leaseUntil, Long profileId, TochkaPaymentMode expectedMode, TochkaPaymentProfile runtimeProfile, PaymentOperation operation, MappedPayment mapped, String paymentUrl, String failureReason) {

        private static TochkaInitRecoveryObservation notFound(String nonce, LocalDateTime leaseUntil, Long profileId, TochkaPaymentMode expectedMode, TochkaPaymentProfile runtimeProfile) {
            return new TochkaInitRecoveryObservation(false, nonce, leaseUntil, profileId, expectedMode, runtimeProfile, null, null, null, null);
        }

        private static TochkaInitRecoveryObservation found(String nonce, LocalDateTime leaseUntil, Long profileId, TochkaPaymentMode expectedMode, TochkaPaymentProfile runtimeProfile, PaymentOperation operation, MappedPayment mapped, String paymentUrl) {
            return new TochkaInitRecoveryObservation(true, nonce, leaseUntil, profileId, expectedMode, runtimeProfile, operation, mapped, paymentUrl, null);
        }

        private static TochkaInitRecoveryObservation failed(String nonce, LocalDateTime leaseUntil, Long profileId, String failureReason) {
            return new TochkaInitRecoveryObservation(false, nonce, leaseUntil, profileId, null, null, null, null, null, failureReason);
        }
    }

    record BankInitApplyResult(PublicPaymentInitResponse response, String paymentId, String paymentUrl, HttpStatus errorStatus, String errorReason) {

        private static BankInitApplyResult success(PublicPaymentInitResponse response, String paymentId, String paymentUrl) {
            return new BankInitApplyResult(response, paymentId, paymentUrl, null, null);
        }

        private static BankInitApplyResult error(HttpStatus status, String reason) {
            return new BankInitApplyResult(null, null, null, status, reason);
        }
    }
}
