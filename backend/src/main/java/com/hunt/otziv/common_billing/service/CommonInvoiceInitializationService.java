package com.hunt.otziv.common_billing.service;

import static com.hunt.otziv.common_billing.service.CommonInvoiceCancellationService.*;
import static com.hunt.otziv.common_billing.service.CommonInvoicePresenter.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceSettlementService.*;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.common_billing.model.CommonBillingAccount;
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceOrder;
import com.hunt.otziv.common_billing.model.CommonInvoicePaymentRef;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonInvoiceOrderRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoicePaymentRefRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentAllocation;
import com.hunt.otziv.contractor_payments.model.ContractorRecipientType;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentLiveRoutingService;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentLiveRoutingService.FrozenCommonRouteAction;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentShadowService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.payments.config.TbankPaymentProperties;
import com.hunt.otziv.payments.dto.PublicPaymentInitResponse;
import com.hunt.otziv.payments.dto.PaymentRouteSelection;
import com.hunt.otziv.payments.dto.TbankInitCommand;
import com.hunt.otziv.payments.dto.TbankInitResponse;
import com.hunt.otziv.payments.dto.TbankPaymentProfile;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.model.ManualPaymentSource;
import com.hunt.otziv.payments.model.ManualPaymentType;
import com.hunt.otziv.payments.model.PaymentMethod;
import com.hunt.otziv.payments.model.PaymentProfile;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.payments.service.PaymentProfileService;
import com.hunt.otziv.payments.service.PaymentIssueReminderService;
import com.hunt.otziv.payments.service.ManualPaymentTaskReceiptIntegrationService;
import com.hunt.otziv.payments.service.StandaloneBankPaymentPolicy;
import com.hunt.otziv.payments.service.PaymentUrlPolicy;
import com.hunt.otziv.payments.service.TbankClient;
import com.hunt.otziv.payments.service.TbankRuntimeSettingsService;
import com.hunt.otziv.payments.tochka.dto.TochkaApiModels.CreatePaymentResponse;
import com.hunt.otziv.payments.tochka.dto.TochkaCreatePaymentCommand;
import com.hunt.otziv.payments.tochka.dto.TochkaPaymentProfile;
import com.hunt.otziv.payments.tochka.model.TochkaPaymentMode;
import com.hunt.otziv.payments.tochka.service.TochkaClient;
import com.hunt.otziv.payments.tochka.service.TochkaPaymentOperationMapper.MappedPayment;
import com.hunt.otziv.payments.tochka.service.TochkaPaymentProfileResolver;
import com.hunt.otziv.payments.tochka.service.TochkaProviderException;
import com.hunt.otziv.u_users.model.Manager;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.CaughtFailureStage.CLOSE_ORDER;
import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.CaughtFailureStage.OPEN_NEXT_ORDER;
import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.TransactionFlow.COMMON_INVOICE_CLOSE;

@Service
@Slf4j
@RequiredArgsConstructor
public class CommonInvoiceInitializationService {

    private final CommonInvoiceMessageQueue messageQueue;

    private final com.hunt.otziv.payments.service.CommonInvoiceRouteSelector invoiceRouteSelector;

    private final CommonInvoiceCancellationService invoiceCancellation;

    private final CommonInvoicePresenter invoicePresenter;

    private final CommonInvoiceSettlementService settlementService;

    static final Set<CommonInvoiceStatus> PUBLIC_PAYABLE_STATUSES = Set.of(CommonInvoiceStatus.COLLECTING, CommonInvoiceStatus.READY, CommonInvoiceStatus.INVOICED, CommonInvoiceStatus.REMINDER, CommonInvoiceStatus.PARTIALLY_PAID);

    static final Set<String> PREPARED_PAYMENT_REF_LIFECYCLE_STATUSES = Set.of(PAYMENT_REF_INIT_PREPARED, PAYMENT_REF_INIT_CONFLICT, PAYMENT_REF_CURRENT, PAYMENT_REF_PREPAID, PAYMENT_REF_CONFIRMED, PAYMENT_REF_APPLYING, PAYMENT_REF_APPLIED, PAYMENT_REF_ARCHIVED, PAYMENT_REF_CANCEL_PENDING, PAYMENT_REF_CANCELING, PAYMENT_REF_CANCELED, PAYMENT_REF_CANCEL_FAILED, PAYMENT_REF_CANCEL_FAILED_FINAL, "REJECTED", "EXPIRED", "REFUNDED", "PARTIAL_REFUNDED", "REVERSED", "PARTIAL_REVERSED");

    static final Set<String> PAYMENT_INIT_NEW_ATTEMPT_BLOCKING_REF_STATUSES = Set.of(PAYMENT_REF_INIT_PREPARED, PAYMENT_REF_INIT_CONFLICT, PAYMENT_REF_CURRENT, PAYMENT_REF_CANCEL_PENDING, PAYMENT_REF_CANCELING, PAYMENT_REF_CANCEL_FAILED, PAYMENT_REF_CANCEL_FAILED_FINAL, PAYMENT_REF_CONFIRMED, PAYMENT_REF_PREPAID, PAYMENT_REF_APPLYING, PAYMENT_REF_APPLIED);

    static final String MESSAGE_SEND_IN_PROGRESS = "message_send_in_progress";

    static final String PAYMENT_ROUTE_CHANGED_MESSAGE_IN_PROGRESS = "payment_route_changed_message_in_progress";

    static final String PAYMENT_ROUTE_CHANGED_MESSAGE_RETRY = "payment_route_changed_message_retry:";

    static final String PAYMENT_INIT_STALE = "payment_init_stale";

    static final String MESSAGE_SEND_STALE = "message_send_stale";

    static final java.time.Duration OPERATION_IN_PROGRESS_TIMEOUT = java.time.Duration.ofMinutes(30);

    static final ZoneId MOSCOW_ZONE = ZoneId.of("Europe/Moscow");

    private final EntityManager entityManager;

    private final CommonInvoiceRepository invoiceRepository;

    private final CommonInvoiceOrderRepository invoiceOrderRepository;

    private final CommonInvoicePaymentRefRepository paymentRefRepository;

    private final PaymentLinkRepository paymentLinkRepository;

    private final PaymentIssueReminderService paymentIssueReminderService;

    private final ContractorPaymentLiveRoutingService contractorPaymentLiveRoutingService;

    private final ContractorPaymentShadowService contractorPaymentShadowService;

    private final ManualPaymentTaskReceiptIntegrationService taskReceiptIntegrationService;

    private final TbankRuntimeSettingsService runtimeSettingsService;

    private final PaymentProfileService paymentProfileService;

    private final TbankPaymentProperties properties;

    private final TbankClient tbankClient;

    private final TochkaPaymentProfileResolver tochkaPaymentProfileResolver;

    private final TochkaClient tochkaClient;

    boolean isTochkaRefundSubmissionClaimed(String reason) {
        return settlementService.isTochkaRefundSubmissionClaimed(reason);
    }

    boolean isTochkaCancellationLifecycleStatus(String status) {
        return settlementService.isTochkaCancellationLifecycleStatus(status);
    }

    void setTochkaReasonUnlessRefundClaimed(CommonInvoicePaymentRef ref, String nextReason) {
        settlementService.setTochkaReasonUnlessRefundClaimed(ref, nextReason);
    }

    boolean isOwnerPaperInvoice(CommonInvoice invoice) {
        return CommonInvoiceRouteState.isOwnerPaperInvoice(invoice);
    }

    public PublicPaymentInitResponse initPublicPayment(String token, String email, boolean offerConsent, boolean privacyConsent, boolean receiptConsent) {
        if (!offerConsent || !privacyConsent || !receiptConsent) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Подтвердите согласия для оплаты");
        }
        String cleanEmail = normalize(email);
        if (cleanEmail.isBlank() || !cleanEmail.contains("@")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Укажите e-mail для электронного чека");
        }
        PreparedCommonPaymentInit prepared = writeTransaction(() -> preparePaymentInit(cleanToken(token), cleanEmail));
        if (prepared.deferredFailure() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, prepared.deferredFailure());
        }
        if (prepared.cachedResponse() != null) {
            return prepared.cachedResponse();
        }
        if (PROVIDER_TOCHKA.equals(prepared.provider())) {
            return executeTochkaPaymentInit(prepared);
        }
        TbankInitResponse response;
        try {
            response = tbankClient.init(prepared.runtimeProfile(), new TbankInitCommand(prepared.tbankOrderId(), prepared.remainingKopecks(), "Репутационные услуги", prepared.email(), properties.notificationUrl(), properties.successUrl(), properties.failUrl(), OffsetDateTime.now(MOSCOW_ZONE).plus(properties.getRedirectDue())));
        } catch (RuntimeException e) {
            boolean tlsCertificateFailureBeforeHttp = CommonPaymentInitFailureClassifier.isCertificateTlsFailureBeforeHttpResponse(e);
            String persistedError = (tlsCertificateFailureBeforeHttp ? CommonPaymentInitFailureClassifier.TLS_BEFORE_HTTP_ERROR_CODE : "payment_init_exception") + ": " + readableException(e);
            String paymentRefReason = tlsCertificateFailureBeforeHttp ? CommonPaymentInitFailureClassifier.TLS_BEFORE_HTTP_REF_REASON : CommonPaymentInitFailureClassifier.LEGACY_TLS_BEFORE_HTTP_REF_REASON;
            writeTransaction(() -> {
                failPaymentInit(prepared, persistedError, paymentRefReason);
                return null;
            });
            throw e;
        }
        String responseMismatch = paymentInitResponseMismatch(prepared, response);
        if (responseMismatch != null) {
            executePaymentInitFailureWrite(prepared, response, () -> failMismatchedPaymentInit(prepared, response, responseMismatch));
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Т-Банк вернул несогласованный ответ. Платеж отправлен на ручную сверку.");
        }
        String paymentUrl;
        try {
            paymentUrl = PaymentUrlPolicy.require(response.paymentUrl(), PaymentUrlPolicy.Purpose.TBANK_PAYMENT, HttpStatus.BAD_GATEWAY, "Т-Банк вернул недопустимую ссылку оплаты");
        } catch (ResponseStatusException e) {
            executePaymentInitFailureWrite(prepared, response, () -> failUnsafePaymentUrl(prepared, response));
            throw e;
        }
        PaymentInitFinishResult result;
        try {
            result = writeTransaction(() -> finishPaymentInit(prepared, response, paymentUrl));
        } catch (RuntimeException finishFailure) {
            boolean identityCollision = isPaymentIdentityConstraintViolation(finishFailure);
            boolean currentRegistryCollision = isCurrentPaymentRegistryConstraintViolation(finishFailure);
            try {
                writeTransaction(() -> {
                    if (currentRegistryCollision) {
                        quarantineCurrentPaymentRegistryConstraint(prepared, response, finishFailure);
                    } else {
                        failMismatchedPaymentInit(prepared, response, "finish_exception:" + readableException(finishFailure));
                    }
                    return null;
                });
            } catch (RuntimeException quarantineFailure) {
                finishFailure.addSuppressed(quarantineFailure);
                log.error("Не удалось пометить общий счет {} для ручной сверки после ошибки фиксации T-Bank Init", prepared.invoiceId(), quarantineFailure);
            }
            if (identityCollision) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "PaymentId уже используется другим платежом; ссылка отправлена на ручную сверку", finishFailure);
            }
            if (currentRegistryCollision) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "У общего счета уже есть другая активная платежная ссылка; платеж отправлен на ручную сверку", finishFailure);
            }
            throw finishFailure;
        }
        if (result.failureStatus() != null) {
            throw new ResponseStatusException(result.failureStatus(), result.failureMessage());
        }
        return result.response();
    }

    void executePaymentInitFailureWrite(PreparedCommonPaymentInit prepared, TbankInitResponse response, Runnable action) {
        try {
            writeTransaction(() -> {
                action.run();
                return null;
            });
        } catch (RuntimeException failure) {
            if (!isPaymentIdentityConstraintViolation(failure)) {
                throw failure;
            }
            writeTransaction(() -> {
                quarantinePaymentInitIdentityConstraint(prepared, response, failure);
                return null;
            });
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PaymentId уже связан с другим платежом; ссылка отправлена на ручную сверку", failure);
        }
    }

    PreparedCommonPaymentInit preparePaymentInit(String token, String cleanEmail) {
        LockedInvoicePaymentPrelude paymentPrelude = lockedInvoiceByTokenAfterStandalonePaymentPrelude(token);
        CommonInvoice invoice = paymentPrelude.invoice();
        ensureNoOperationInProgress(invoice);
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoice.getId());
        try {
            Set<PaymentLink> appliedStandalonePayments = synchronizeConfirmedStandalonePaymentsOrThrow(invoice, items, paymentPrelude.paymentLinksByOrder());
            closeProvablyUnstartedStandaloneRoutesOrThrow(paymentLinksRequiringCommonInvoiceRouteCheck(paymentPrelude.paymentLinksByOrder(), items, appliedStandalonePayments), invoice.getId());
        } catch (ResponseStatusException conflict) {
            markStandalonePaymentRouteConflict(invoice, conflict);
            return new PreparedCommonPaymentInit(invoice.getId(), null, cleanEmail, 0, null, null, null, "У общего счета обнаружен другой незакрытый способ оплаты; нужна ручная сверка");
        }
        refreshInvoiceAmounts(invoice, items);
        long remaining = remainingKopecks(invoice);
        if (!canAcceptPublicPayment(invoice)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Общий счет еще не готов к оплате");
        }
        if (remaining <= 0) {
            if (!allOrdersReady(items)) {
                return new PreparedCommonPaymentInit(invoice.getId(), null, cleanEmail, 0, null, null, new PublicPaymentInitResponse("", "", invoice.getStatus().name()), null);
            }
            closePaidInvoice(invoice, items);
            return new PreparedCommonPaymentInit(invoice.getId(), null, cleanEmail, 0, null, null, new PublicPaymentInitResponse("", "", invoice.getStatus().name()), null);
        }
        ensureCommonPaymentRouteSelected(invoice, remaining);
        if (!isTbankCommonRoute(invoice)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Для общего счета выбран другой способ оплаты. Используйте реквизиты на странице счета.");
        }
        PaymentProfile profile = lockedCommonPaymentProfile(invoice);
        if (paymentProfileService.isTochkaProvider(profile)) {
            return prepareTochkaPaymentInit(invoice, profile, cleanEmail, remaining);
        }
        if (!runtimeSettingsService.isPaymentLinksEnabled() || !runtimeSettingsService.isTbankEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Платежные ссылки выключены в настройках");
        }
        String cachedPaymentUrl = PaymentUrlPolicy.safe(invoice.getPaymentUrl(), PaymentUrlPolicy.Purpose.TBANK_PAYMENT);
        boolean hasPersistedProviderRef = !normalize(invoice.getTbankPaymentId()).isBlank() || !normalize(invoice.getTbankOrderId()).isBlank();
        if (hasPersistedProviderRef && cachedPaymentUrl.isBlank()) {
            String persistedProviderLabel = paymentRefLabel(invoice.getTbankOrderId(), invoice.getTbankPaymentId());
            archiveAndClearCurrentPaymentRef(invoice, "payment_cached_invalid_url");
            invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
            invoice.setNextReminderAt(null);
            invoice.setPaymentUrl(null);
            invoice.setLastError(limit("payment_cached_invalid_url: Сохраненная ссылка платежа " + persistedProviderLabel + " отсутствует или имеет недопустимый формат; нужна ручная сверка", 512));
            invoiceRepository.save(invoice);
            return new PreparedCommonPaymentInit(invoice.getId(), null, cleanEmail, remaining, null, null, null, "Сохраненная ссылка Т-Банка отсутствует или имеет недопустимый формат");
        }
        if (!cachedPaymentUrl.isBlank() && invoice.getTbankPaymentAmountKopecks() != null && invoice.getTbankPaymentAmountKopecks() == remaining && invoice.getTbankPaymentCreatedAt() != null && invoice.getTbankPaymentCreatedAt().plus(properties.getRedirectDue()).isAfter(LocalDateTime.now())) {
            CommonInvoicePaymentRef currentAnchor = lockedPaymentRefByProviderBinding(invoice.getTbankOrderId(), invoice.getTbankPaymentId()).filter(ref -> Objects.equals(invoice.getId(), paymentRefInvoiceId(ref))).filter(ref -> PAYMENT_REF_CURRENT.equals(normalize(ref.getStatus()).toUpperCase(Locale.ROOT))).orElse(null);
            if (currentAnchor == null) {
                quarantineMissingCurrentPaymentAnchor(invoice, invoice.getTbankOrderId(), invoice.getTbankPaymentId());
                return new PreparedCommonPaymentInit(invoice.getId(), null, cleanEmail, remaining, null, null, null, "Платежная ссылка не прошла проверку durable-реестра");
            }
            return new PreparedCommonPaymentInit(invoice.getId(), null, cleanEmail, remaining, null, null, new PublicPaymentInitResponse(cachedPaymentUrl, invoice.getTbankPaymentId(), invoice.getStatus().name()), null);
        }
        if (hasPersistedProviderRef) {
            archiveAndClearCurrentPaymentRef(invoice, "payment_link_expired_before_replacement");
            invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
            invoice.setNextReminderAt(null);
            invoice.setLastError(limit("payment_init_conflict: предыдущая T-Bank ссылка истекла и отправлена на отмену; " + "новую ссылку можно создать после завершения отмены и ручной проверки", 512));
            invoiceRepository.save(invoice);
            return new PreparedCommonPaymentInit(invoice.getId(), null, cleanEmail, remaining, null, null, null, "Предыдущая ссылка Т-Банка отменяется. Повторите после ручной проверки.");
        }
        ensureNoBlockingPaymentRefsForNewInit(invoice);
        TbankPaymentProfile runtimeProfile = normalize(invoice.getPaymentRouteTerminalKey()).isBlank() ? paymentProfileService.toRuntime(profile) : paymentProfileService.toRuntimeForTerminal(profile, invoice.getPaymentRouteTerminalKey());
        if (runtimeProfile == null) {
            runtimeProfile = paymentProfileService.toRuntime(profile);
        }
        if (!runtimeProfile.hasCredentials()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Не заданы TerminalKey или Password Т-Банка");
        }
        String tbankOrderId = groupTbankOrderId(invoice);
        CommonInvoicePaymentRef preparedRef = createPreparedPaymentInitRef(invoice, tbankOrderId, runtimeProfile, remaining);
        invoice.setPayerEmail(cleanEmail);
        invoice.setTbankOrderId(tbankOrderId);
        invoice.setTbankPaymentId(null);
        invoice.setTbankTerminalKey(runtimeProfile.terminalKey());
        invoice.setTbankPaymentAmountKopecks(remaining);
        invoice.setTbankPaymentCreatedAt(LocalDateTime.now());
        invoice.setPaymentUrl(null);
        invoice.setLastError(PAYMENT_INIT_IN_PROGRESS);
        invoiceRepository.save(invoice);
        return new PreparedCommonPaymentInit(invoice.getId(), preparedRef.getId(), cleanEmail, remaining, runtimeProfile, tbankOrderId, null, null);
    }

    /**
     * Reserves a Tochka attempt durably before the provider POST. The provider/profile/mode
     * snapshot lives on the payment-ref row, so changing a manager assignment later can never
     * reroute this in-flight payment to another bank.
     */
    PreparedCommonPaymentInit prepareTochkaPaymentInit(CommonInvoice invoice, PaymentProfile entityProfile, String cleanEmail, long remaining) {
        if (!runtimeSettingsService.isPaymentLinksEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Платежные ссылки выключены в настройках");
        }
        TochkaPaymentProfile runtimeProfile = tochkaPaymentProfileResolver.resolve(entityProfile);
        List<TochkaPaymentMode> paymentModes = preferredCommonTochkaPaymentModes(runtimeProfile);
        String canonicalPaymentModes = canonicalTochkaPaymentModes(paymentModes);
        List<CommonInvoicePaymentRef> currentRefs = paymentRefRepository.findCurrentProviderRefsForUpdate(invoice.getId(), PROVIDER_TOCHKA, PAYMENT_REF_CURRENT);
        if (currentRefs.size() > 1) {
            invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
            invoice.setNextReminderAt(null);
            invoice.setPaymentUrl(null);
            invoice.setLastError(limit("tochka_payment_registry_collision: у общего счёта несколько активных ссылок Точки", 512));
            invoiceRepository.save(invoice);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "У общего счёта обнаружено несколько активных ссылок Точки; нужна ручная сверка");
        }
        if (!currentRefs.isEmpty()) {
            CommonInvoicePaymentRef current = currentRefs.getFirst();
            boolean bindingMatches = Objects.equals(current.getPaymentProfileId(), entityProfile.getId()) && normalize(current.getProviderMerchantId()).equals(runtimeProfile.merchantId()) && Objects.equals(current.getProviderTestMode(), runtimeProfile.testMode()) && normalize(current.getProviderPaymentMode()).equals(canonicalPaymentModes) && Objects.equals(current.getAmountKopecks(), remaining);
            String cachedUrl = PaymentUrlPolicy.safe(current.getProviderPaymentUrl(), PaymentUrlPolicy.Purpose.TOCHKA_PAYMENT);
            boolean stillAlive = current.getProviderExpiresAt() != null && current.getProviderExpiresAt().isAfter(LocalDateTime.now());
            if (bindingMatches && stillAlive && !cachedUrl.isBlank() && !normalize(current.getProviderPaymentId()).isBlank()) {
                invoice.setPaymentUrl(cachedUrl);
                invoice.setPayerEmail(cleanEmail);
                invoice.setLastError(null);
                invoiceRepository.save(invoice);
                return new PreparedCommonPaymentInit(invoice.getId(), current.getId(), cleanEmail, remaining, null, normalize(current.getProviderOrderId()), new PublicPaymentInitResponse(cachedUrl, current.getProviderPaymentId(), invoice.getStatus().name()), null, PROVIDER_TOCHKA, runtimeProfile, paymentModes, current.getProviderExpiresAt());
            }
            current.setStatus(normalize(current.getProviderPaymentId()).isBlank() ? PAYMENT_REF_INIT_CONFLICT : PAYMENT_REF_CANCEL_PENDING);
            current.setReason(limit(stillAlive ? "tochka_cached_binding_mismatch" : "tochka_link_expired_pending_reconciliation", 160));
            paymentRefRepository.save(current);
            invoice.setPaymentUrl(null);
            invoice.setNextReminderAt(null);
            invoice.setLastError(limit("tochka_payment_reconciliation_required: предыдущая ссылка Точки требует сверки", 512));
            invoiceRepository.save(invoice);
            return new PreparedCommonPaymentInit(invoice.getId(), current.getId(), cleanEmail, remaining, null, normalize(current.getProviderOrderId()), null, "Предыдущая ссылка Точки требует сверки. Повторите через несколько минут.", PROVIDER_TOCHKA, runtimeProfile, paymentModes, current.getProviderExpiresAt());
        }
        ensureNoBlockingPaymentRefsForNewInit(invoice);
        LocalDateTime expiresAt = LocalDateTime.now().plus(runtimeProfile.linkTtl());
        String paymentLinkId = commonTochkaPaymentLinkId(invoice);
        CommonInvoicePaymentRef preparedRef = new CommonInvoicePaymentRef();
        preparedRef.setInvoice(invoice);
        preparedRef.setProvider(PROVIDER_TOCHKA);
        preparedRef.setPaymentProfileId(entityProfile.getId());
        preparedRef.setProviderOrderId(paymentLinkId);
        preparedRef.setProviderPaymentId(null);
        preparedRef.setProviderMerchantId(limit(runtimeProfile.merchantId(), 64));
        preparedRef.setProviderPaymentMode(canonicalPaymentModes);
        preparedRef.setProviderTestMode(runtimeProfile.testMode());
        preparedRef.setProviderStatus("CREATE_RESERVED");
        preparedRef.setProviderExpiresAt(expiresAt);
        preparedRef.setAmountKopecks(remaining);
        preparedRef.setStatus(PAYMENT_REF_INIT_PREPARED);
        preparedRef.setReason("provider_init_reserved");
        paymentRefRepository.save(preparedRef);
        entityManager.flush();
        invoice.setPayerEmail(cleanEmail);
        invoice.setPaymentUrl(null);
        invoice.setLastError(PAYMENT_INIT_IN_PROGRESS);
        invoiceRepository.save(invoice);
        return new PreparedCommonPaymentInit(invoice.getId(), preparedRef.getId(), cleanEmail, remaining, null, paymentLinkId, null, null, PROVIDER_TOCHKA, runtimeProfile, paymentModes, expiresAt);
    }

    PublicPaymentInitResponse executeTochkaPaymentInit(PreparedCommonPaymentInit prepared) {
        CreatePaymentResponse response = null;
        try {
            response = tochkaClient.createPaymentWithReceipt(prepared.tochkaProfile(), new TochkaCreatePaymentCommand(prepared.tbankOrderId(), prepared.remainingKopecks(), "Репутационные услуги", prepared.email(), properties.successUrl(), properties.failUrl(), prepared.tochkaPaymentModes()));
            MappedPayment mapped = mapTochkaCreate(prepared, response);
            if (mapped.status() != PaymentLinkStatus.INITIATED) {
                throw new TochkaProviderException("Точка вернула неожиданный статус создаваемой ссылки", true, null);
            }
            String paymentUrl = PaymentUrlPolicy.require(response.data().paymentLink(), PaymentUrlPolicy.Purpose.TOCHKA_PAYMENT, HttpStatus.BAD_GATEWAY, "Точка вернула недопустимую ссылку оплаты");
            CreatePaymentResponse finalResponse = response;
            return writeTransaction(() -> finishTochkaPaymentInit(prepared, finalResponse, mapped, paymentUrl));
        } catch (RuntimeException failure) {
            CreatePaymentResponse responseEvidence = response;
            writeTransaction(() -> {
                failTochkaPaymentInit(prepared, responseEvidence, failure);
                return null;
            });
            throw failure;
        }
    }

    MappedPayment mapTochkaCreate(PreparedCommonPaymentInit prepared, CreatePaymentResponse response) {
        if (response == null || response.data() == null) {
            throw new TochkaProviderException("Точка не вернула данные созданной ссылки", true, null);
        }
        if (prepared == null || prepared.tochkaProfile() == null) {
            throw new TochkaProviderException("Не зафиксирован профиль создаваемой ссылки Точки", true, null);
        }
        String operationId = normalize(response.data().operationId());
        if (operationId.isBlank()) {
            throw new TochkaProviderException("Точка не вернула operationId созданной ссылки", true, null);
        }
        if (!normalize(prepared.tbankOrderId()).equals(normalize(response.data().paymentLinkId()))) {
            throw new TochkaProviderException("Точка вернула другой paymentLinkId созданной ссылки", true, null);
        }
        if (!normalize(prepared.tochkaProfile().customerCode()).equals(normalize(response.data().customerCode()))) {
            throw new TochkaProviderException("Точка вернула другой customerCode созданной ссылки", true, null);
        }
        if (!normalize(prepared.tochkaProfile().merchantId()).equals(normalize(response.data().merchantId()))) {
            throw new TochkaProviderException("Точка вернула другой merchantId созданной ссылки", true, null);
        }
        BigDecimal expectedAmount = BigDecimal.valueOf(prepared.remainingKopecks(), 2);
        if (response.data().amount() == null || expectedAmount.compareTo(response.data().amount()) != 0) {
            throw new TochkaProviderException("Точка вернула другую сумму созданной ссылки", true, null);
        }
        List<String> returnedModes = response.data().paymentMode() == null ? List.of() : response.data().paymentMode().stream().map(this::normalize).toList();
        List<String> expectedModes = prepared.tochkaPaymentModes().stream().map(TochkaPaymentMode::code).toList();
        if (!expectedModes.equals(returnedModes)) {
            throw new TochkaProviderException("Точка вернула другой набор способов оплаты создаваемой ссылки", true, null);
        }
        if (!"CREATED".equals(normalize(response.data().status()))) {
            throw new TochkaProviderException("Точка вернула другой статус создаваемой ссылки", true, null);
        }
        return new MappedPayment(PaymentLinkStatus.INITIATED, PaymentMethod.SBP_QR, "CREATED");
    }

    PublicPaymentInitResponse finishTochkaPaymentInit(PreparedCommonPaymentInit prepared, CreatePaymentResponse response, MappedPayment mapped, String paymentUrl) {
        CommonInvoice invoice = lockedInvoice(prepared.invoiceId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        CommonInvoicePaymentRef ref = paymentRefRepository.findByIdForUpdate(prepared.paymentRefId()).orElseThrow(() -> invoiceMembershipChanged("не найдена durable-запись ссылки Точки"));
        String operationId = normalize(response.data().operationId());
        ref.setProviderPaymentId(limit(operationId, 64));
        ref.setProviderPaymentUrl(limit(paymentUrl, 1024));
        ref.setProviderStatus(mapped.providerStatus());
        String refStatus = paymentRefStatus(ref);
        boolean cancellationLifecycle = isTochkaCancellationLifecycleStatus(refStatus) || isTochkaRefundSubmissionClaimed(ref.getReason());
        if (!matchesPreparedTochkaPayment(ref, prepared) || !Objects.equals(invoice.getPaymentRouteProfileId(), ref.getPaymentProfileId())) {
            if (cancellationLifecycle) {
                paymentRefRepository.save(ref);
                markTochkaCreateCompletedAfterCancellationIntent(invoice, ref);
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Ссылка Точки создана после запроса закрытия счёта; нужна сверка банка");
            }
            quarantineTochkaInit(invoice, ref, "tochka_init_binding_changed");
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Платёжный маршрут общего счёта изменился во время создания ссылки");
        }
        Optional<CommonInvoicePaymentRef> foreign = paymentRefRepository.findByProviderAndProviderPaymentId(PROVIDER_TOCHKA, operationId).filter(candidate -> !Objects.equals(candidate.getId(), ref.getId()));
        if (foreign.isPresent()) {
            quarantineTochkaInit(invoice, ref, "tochka_operation_id_collision");
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Точка вернула уже используемый operationId; нужна ручная сверка");
        }
        if (cancellationLifecycle) {
            paymentRefRepository.save(ref);
            markTochkaCreateCompletedAfterCancellationIntent(invoice, ref);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ссылка Точки создана после запроса закрытия счёта; нужна сверка банка");
        }
        if (Set.of(PAYMENT_REF_CONFIRMED, PAYMENT_REF_PREPAID, PAYMENT_REF_APPLYING, PAYMENT_REF_APPLIED).contains(refStatus)) {
            return new PublicPaymentInitResponse(paymentUrl, operationId, invoice.getStatus().name());
        }
        if (!PAYMENT_REF_INIT_PREPARED.equals(refStatus) && !PAYMENT_REF_INIT_CONFLICT.equals(refStatus)) {
            quarantineTochkaInit(invoice, ref, "tochka_init_unexpected_ref_status:" + refStatus);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Состояние ссылки Точки изменилось во время создания; нужна сверка");
        }
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoice.getId());
        refreshInvoiceAmounts(invoice, items);
        if (!canAcceptPublicPayment(invoice) || remainingKopecks(invoice) != prepared.remainingKopecks()) {
            quarantineTochkaInit(invoice, ref, "tochka_init_amount_changed");
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Состав или сумма общего счёта изменились. Платёж отправлен на сверку.");
        }
        ref.setStatus(PAYMENT_REF_CURRENT);
        ref.setReason("provider_init_active");
        paymentRefRepository.save(ref);
        entityManager.flush();
        invoice.setPayerEmail(prepared.email());
        invoice.setPaymentUrl(paymentUrl);
        invoice.setLastError(null);
        invoiceRepository.save(invoice);
        return new PublicPaymentInitResponse(paymentUrl, operationId, invoice.getStatus().name());
    }

    void failTochkaPaymentInit(PreparedCommonPaymentInit prepared, CreatePaymentResponse response, RuntimeException failure) {
        if (prepared == null || prepared.paymentRefId() == null) {
            return;
        }
        CommonInvoice invoice = lockedInvoice(prepared.invoiceId()).orElse(null);
        CommonInvoicePaymentRef ref = paymentRefRepository.findByIdForUpdate(prepared.paymentRefId()).orElse(null);
        if (invoice == null || ref == null || !matchesPreparedTochkaPayment(ref, prepared)) {
            return;
        }
        String refStatus = paymentRefStatus(ref);
        if (Set.of(PAYMENT_REF_CONFIRMED, PAYMENT_REF_PREPAID, PAYMENT_REF_APPLYING, PAYMENT_REF_APPLIED).contains(refStatus)) {
            return;
        }
        boolean outcomeUnknown = response != null || (failure instanceof TochkaProviderException providerFailure && providerFailure.isOutcomeUnknown());
        if (response != null && response.data() != null) {
            String operationId = normalize(response.data().operationId());
            if (!operationId.isBlank()) {
                ref.setProviderPaymentId(limit(operationId, 64));
            }
            String paymentUrl = PaymentUrlPolicy.safe(response.data().paymentLink(), PaymentUrlPolicy.Purpose.TOCHKA_PAYMENT);
            if (!paymentUrl.isBlank()) {
                ref.setProviderPaymentUrl(paymentUrl);
            }
            ref.setProviderStatus(limit(response.data().status(), 32));
        }
        if (isTochkaCancellationLifecycleStatus(refStatus) || isTochkaRefundSubmissionClaimed(ref.getReason())) {
            paymentRefRepository.save(ref);
            markTochkaCreateCompletedAfterCancellationIntent(invoice, ref);
            return;
        }
        ref.setStatus(outcomeUnknown ? PAYMENT_REF_INIT_CONFLICT : PAYMENT_REF_ARCHIVED);
        ref.setReason(limit(outcomeUnknown ? "tochka_create_outcome_unknown" : "tochka_create_not_started", 160));
        paymentRefRepository.save(ref);
        invoice.setPaymentUrl(null);
        invoice.setLastError(limit((outcomeUnknown ? "tochka_create_ambiguous: " : "tochka_create_failed: ") + readableException(failure), 512));
        invoiceRepository.save(invoice);
    }

    void markTochkaCreateCompletedAfterCancellationIntent(CommonInvoice invoice, CommonInvoicePaymentRef ref) {
        if (invoice == null || ref == null) {
            return;
        }
        if (!isTochkaCancellationLifecycleStatus(paymentRefStatus(ref))) {
            ref.setStatus(PAYMENT_REF_CANCEL_PENDING);
        }
        setTochkaReasonUnlessRefundClaimed(ref, "tochka_create_completed_after_cancel_intent");
        paymentRefRepository.save(ref);
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setNextReminderAt(null);
        invoice.setPaymentUrl(null);
        invoice.setLastError(limit("tochka_create_completed_after_cancel_intent: ссылка Точки могла быть создана; " + "до GET/возврата новая ссылка запрещена", 512));
        invoiceRepository.save(invoice);
    }

    void quarantineTochkaInit(CommonInvoice invoice, CommonInvoicePaymentRef ref, String reason) {
        settlementService.quarantineTochkaInit(invoice, ref, reason);
    }

    boolean matchesPreparedTochkaPayment(CommonInvoicePaymentRef ref, PreparedCommonPaymentInit prepared) {
        return ref != null && prepared != null && Objects.equals(paymentRefInvoiceId(ref), prepared.invoiceId()) && PROVIDER_TOCHKA.equals(normalizedPaymentProvider(ref)) && Objects.equals(ref.getPaymentProfileId(), prepared.tochkaProfile().id()) && normalize(ref.getProviderOrderId()).equals(prepared.tbankOrderId()) && normalize(ref.getProviderMerchantId()).equals(prepared.tochkaProfile().merchantId()) && normalize(ref.getProviderPaymentMode()).equals(canonicalTochkaPaymentModes(prepared.tochkaPaymentModes())) && Objects.equals(ref.getProviderTestMode(), prepared.tochkaProfile().testMode()) && Objects.equals(ref.getAmountKopecks(), prepared.remainingKopecks());
    }

    List<TochkaPaymentMode> preferredCommonTochkaPaymentModes(TochkaPaymentProfile profile) {
        List<TochkaPaymentMode> modes = profile == null ? List.of() : profile.paymentModes();
        List<TochkaPaymentMode> supported = new ArrayList<>(2);
        if (modes.contains(TochkaPaymentMode.SBP)) {
            supported.add(TochkaPaymentMode.SBP);
        }
        if (modes.contains(TochkaPaymentMode.CARD)) {
            supported.add(TochkaPaymentMode.CARD);
        }
        if (!supported.isEmpty()) {
            return List.copyOf(supported);
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Для профиля Точки общего счёта должны быть включены СБП или карта");
    }

    String canonicalTochkaPaymentModes(List<TochkaPaymentMode> modes) {
        return (modes == null ? List.<TochkaPaymentMode>of() : modes).stream().map(TochkaPaymentMode::code).distinct().collect(Collectors.joining(","));
    }

    String commonTochkaPaymentLinkId(CommonInvoice invoice) {
        String invoiceId = String.valueOf(invoice == null || invoice.getId() == null ? 0 : invoice.getId());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        int maxNonce = Math.max(8, 45 - 4 - invoiceId.length());
        return "ci-" + invoiceId + "-" + nonce.substring(0, Math.min(maxNonce, nonce.length()));
    }

    PaymentInitFinishResult finishPaymentInit(PreparedCommonPaymentInit prepared, TbankInitResponse response, String paymentUrl) {
        CommonInvoice invoice = lockedInvoice(prepared.invoiceId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        CommonInvoicePaymentRef preparedRef = lockedPreparedPaymentRef(prepared).orElse(null);
        if (hasForeignPaymentIdBinding(response == null ? null : response.paymentId(), invoice.getId(), preparedRef == null ? prepared.paymentRefId() : preparedRef.getId())) {
            String collisionPaymentId = normalize(response == null ? null : response.paymentId());
            markPreparedPaymentInitConflict(invoice, prepared, "response_payment_id_collision:" + collisionPaymentId);
            if (matchesPreparedCurrentIntent(invoice, prepared)) {
                clearCurrentPaymentRef(invoice);
            } else {
                invoice.setPaymentUrl(null);
            }
            invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
            invoice.setNextReminderAt(null);
            invoice.setLastError(limit("payment_init_response_collision: PaymentId " + collisionPaymentId + " уже связан с другим платежом; " + "ссылка не выдана, нужна ручная сверка", 512));
            invoiceRepository.save(invoice);
            return new PaymentInitFinishResult(null, HttpStatus.CONFLICT, "T-Bank вернул уже используемый PaymentId. Нужна ручная сверка.");
        }
        PaymentInitFinishResult webhookResult = paymentInitAlreadyHandledByWebhook(invoice, prepared, preparedRef, response, paymentUrl);
        if (webhookResult != null) {
            return webhookResult;
        }
        boolean currentIntentMatches = matchesPreparedCurrentIntent(invoice, prepared);
        if (!PAYMENT_INIT_IN_PROGRESS.equals(normalize(invoice.getLastError()))) {
            boolean completedByEarlyWebhook = currentIntentMatches && !normalize(invoice.getTbankPaymentId()).isBlank() && normalize(invoice.getTbankPaymentId()).equals(normalize(response.paymentId()));
            if (completedByEarlyWebhook) {
                return new PaymentInitFinishResult(new PublicPaymentInitResponse(paymentUrl, response.paymentId(), invoice.getStatus().name()), null, null);
            }
            recordInitializedPaymentRef(invoice, prepared, response, "init_finalized_after_invoice_changed");
            clearCurrentPaymentRef(invoice);
            invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
            invoice.setNextReminderAt(null);
            invoice.setLastError(limit("payment_init_conflict: состояние общего счета изменилось после создания платежа; нужна ручная сверка", 512));
            invoiceRepository.save(invoice);
            return new PaymentInitFinishResult(null, HttpStatus.CONFLICT, "Общий счет изменился во время создания платежной ссылки");
        }
        if (!response.success()) {
            recordInitializedPaymentRef(invoice, prepared, response, "tbank_init_failed");
            clearCurrentPaymentRef(invoice);
            invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
            invoice.setNextReminderAt(null);
            invoice.setLastError(limit("tbank_init_failed: " + response.errorText(), 512));
            invoiceRepository.save(invoice);
            return new PaymentInitFinishResult(null, HttpStatus.BAD_GATEWAY, response.errorText());
        }
        if (!currentIntentMatches || (!normalize(invoice.getTbankPaymentId()).isBlank() && !normalize(invoice.getTbankPaymentId()).equals(normalize(response.paymentId())))) {
            recordInitializedPaymentRef(invoice, prepared, response, "init_current_intent_mismatch");
            clearCurrentPaymentRef(invoice);
            invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
            invoice.setNextReminderAt(null);
            invoice.setLastError(limit("payment_init_conflict: сохраненные реквизиты создания платежа изменились; нужна ручная сверка", 512));
            invoiceRepository.save(invoice);
            return new PaymentInitFinishResult(null, HttpStatus.CONFLICT, "Реквизиты платежа изменились. Нужна ручная сверка.");
        }
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoice.getId());
        refreshInvoiceAmounts(invoice, items);
        if (!canAcceptPublicPayment(invoice) || remainingKopecks(invoice) != prepared.remainingKopecks()) {
            recordInitializedPaymentRef(invoice, prepared, response, "init_conflict_after_amount_changed");
            clearCurrentPaymentRef(invoice);
            invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
            invoice.setNextReminderAt(null);
            invoice.setPaymentUrl(null);
            invoice.setLastError(limit("payment_init_conflict: T-Bank создал ссылку " + paymentRefLabel(prepared.tbankOrderId(), response.paymentId()) + " на " + amountRubles(prepared.remainingKopecks()) + " руб., но состав или сумма общего счета изменились; нужна ручная сверка", 512));
            invoiceRepository.save(invoice);
            return new PaymentInitFinishResult(null, HttpStatus.CONFLICT, "Состав или сумма общего счета изменились. Повторите оплату.");
        }
        activatePreparedPaymentRef(invoice, prepared, preparedRef, response);
        invoice.setPayerEmail(prepared.email());
        invoice.setTbankOrderId(prepared.tbankOrderId());
        invoice.setTbankPaymentId(response.paymentId());
        invoice.setTbankTerminalKey(prepared.runtimeProfile().terminalKey());
        invoice.setTbankPaymentAmountKopecks(prepared.remainingKopecks());
        invoice.setTbankPaymentCreatedAt(LocalDateTime.now());
        invoice.setPaymentUrl(paymentUrl);
        invoice.setLastError(null);
        invoiceRepository.save(invoice);
        return new PaymentInitFinishResult(new PublicPaymentInitResponse(paymentUrl, response.paymentId(), invoice.getStatus().name()), null, null);
    }

    void activatePreparedPaymentRef(CommonInvoice invoice, PreparedCommonPaymentInit prepared, CommonInvoicePaymentRef preparedRef, TbankInitResponse response) {
        if (invoice == null || prepared == null || preparedRef == null || response == null) {
            throw invoiceMembershipChanged("не найдена durable-запись создаваемой T-Bank ссылки");
        }
        String status = normalize(preparedRef.getStatus()).toUpperCase(Locale.ROOT);
        if (!PAYMENT_REF_INIT_PREPARED.equals(status) && !PAYMENT_REF_CURRENT.equals(status)) {
            throw invoiceMembershipChanged("T-Bank ссылка уже перешла в состояние " + status);
        }
        if (!matchesPreparedPaymentRef(preparedRef, prepared)) {
            throw invoiceMembershipChanged("durable-запись T-Bank ссылки сменила реквизиты");
        }
        preparedRef.setTbankPaymentId(limit(response.paymentId(), 64));
        preparedRef.setTbankTerminalKey(limit(response.terminalKey(), 64));
        preparedRef.setProvider(PROVIDER_TBANK);
        preparedRef.setProviderPaymentId(limit(response.paymentId(), 64));
        preparedRef.setProviderMerchantId(limit(response.terminalKey(), 64));
        preparedRef.setProviderStatus(limit(response.status(), 32));
        preparedRef.setProviderPaymentUrl(limit(response.paymentUrl(), 1024));
        preparedRef.setAmountKopecks(response.amount());
        preparedRef.setStatus(PAYMENT_REF_CURRENT);
        preparedRef.setReason("provider_init_active");
        paymentRefRepository.save(preparedRef);
        // Materialize the unique PaymentId registry before the non-unique invoice
        // projection is updated. A concurrent duplicate fails here and is
        // quarantined by the outer recovery transaction.
        entityManager.flush();
    }

    String paymentInitResponseMismatch(PreparedCommonPaymentInit prepared, TbankInitResponse response) {
        if (response == null) {
            return "response_missing";
        }
        if (!response.success()) {
            return "success_false:error_code=" + normalize(response.errorCode());
        }
        if (!"0".equals(normalize(response.errorCode()))) {
            return "error_code_mismatch:" + normalize(response.errorCode());
        }
        if (normalize(response.paymentId()).isBlank()) {
            return "payment_id_missing";
        }
        if (!normalize(prepared.tbankOrderId()).equals(normalize(response.orderId()))) {
            return "order_id_mismatch";
        }
        String expectedTerminalKey = prepared.runtimeProfile() == null ? "" : normalize(prepared.runtimeProfile().terminalKey());
        if (expectedTerminalKey.isBlank() || !expectedTerminalKey.equals(normalize(response.terminalKey()))) {
            return "terminal_key_mismatch";
        }
        if (response.amount() == null || response.amount() != prepared.remainingKopecks()) {
            return "amount_mismatch";
        }
        return null;
    }

    void failMismatchedPaymentInit(PreparedCommonPaymentInit prepared, TbankInitResponse response, String mismatch) {
        CommonInvoice invoice = lockedInvoice(prepared.invoiceId()).orElse(null);
        if (invoice == null) {
            return;
        }
        recordInitializedPaymentRef(invoice, prepared, response, "init_response_mismatch:" + normalize(mismatch));
        if (matchesPreparedCurrentIntent(invoice, prepared)) {
            clearCurrentPaymentRef(invoice);
        } else {
            invoice.setPaymentUrl(null);
        }
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setNextReminderAt(null);
        invoice.setPaymentUrl(null);
        invoice.setLastError(limit("payment_init_response_mismatch: T-Bank вернул несогласованные реквизиты (" + normalize(mismatch) + ") для " + paymentRefLabel(response == null ? null : response.orderId(), response == null ? null : response.paymentId()) + "; ссылка не выдана, нужна ручная сверка", 512));
        invoiceRepository.save(invoice);
    }

    void failUnsafePaymentUrl(PreparedCommonPaymentInit prepared, TbankInitResponse response) {
        CommonInvoice invoice = lockedInvoice(prepared.invoiceId()).orElse(null);
        if (invoice == null) {
            return;
        }
        recordInitializedPaymentRef(invoice, prepared, response, "unsafe_tbank_payment_url");
        if (PAYMENT_INIT_IN_PROGRESS.equals(normalize(invoice.getLastError()))) {
            if (matchesPreparedCurrentIntent(invoice, prepared)) {
                clearCurrentPaymentRef(invoice);
            } else {
                invoice.setPaymentUrl(null);
            }
            invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
            invoice.setNextReminderAt(null);
            invoice.setLastError(limit("payment_init_invalid_url: T-Bank создал платеж " + paymentRefLabel(prepared.tbankOrderId(), response.paymentId()) + ", но вернул недопустимую ссылку; нужна ручная сверка", 512));
            invoiceRepository.save(invoice);
        }
    }

    void failPaymentInit(PreparedCommonPaymentInit prepared, String error, String paymentRefReason) {
        CommonInvoice invoice = lockedInvoice(prepared.invoiceId()).orElse(null);
        if (invoice == null || !PAYMENT_INIT_IN_PROGRESS.equals(normalize(invoice.getLastError()))) {
            return;
        }
        markPreparedPaymentInitConflict(invoice, prepared, paymentRefReason);
        if (matchesPreparedCurrentIntent(invoice, prepared)) {
            clearCurrentPaymentRef(invoice);
        } else {
            invoice.setPaymentUrl(null);
        }
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setNextReminderAt(null);
        invoice.setLastError(limit(error + "; проверьте банк вручную перед повторной оплатой", 512));
        invoiceRepository.save(invoice);
    }

    void quarantinePaymentInitIdentityConstraint(PreparedCommonPaymentInit prepared, TbankInitResponse response, RuntimeException failure) {
        if (prepared == null) {
            return;
        }
        CommonInvoice invoice = lockedInvoice(prepared.invoiceId()).orElse(null);
        if (invoice == null) {
            return;
        }
        CommonInvoicePaymentRef anchor = lockedPreparedPaymentRef(prepared).orElse(null);
        String paymentId = normalize(response == null ? null : response.paymentId());
        if (anchor != null) {
            anchor.setStatus(PAYMENT_REF_INIT_CONFLICT);
            anchor.setReason(limit("payment_identity_constraint:" + paymentId, 160));
            paymentRefRepository.save(anchor);
        }
        if (matchesPreparedCurrentIntent(invoice, prepared)) {
            clearCurrentPaymentRef(invoice);
        } else {
            invoice.setPaymentUrl(null);
        }
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setNextReminderAt(null);
        invoice.setLastError(limit("payment_init_response_collision: PaymentId " + paymentId + " нарушил уникальность durable-реестра; нужна ручная сверка (" + readableException(failure) + ")", 512));
        invoiceRepository.save(invoice);
    }

    void quarantineCurrentPaymentRegistryConstraint(PreparedCommonPaymentInit prepared, TbankInitResponse response, RuntimeException failure) {
        if (prepared == null) {
            return;
        }
        CommonInvoice invoice = lockedInvoice(prepared.invoiceId()).orElse(null);
        if (invoice == null) {
            return;
        }
        CommonInvoicePaymentRef anchor = lockedPreparedPaymentRef(prepared).orElse(null);
        if (anchor != null) {
            String paymentId = normalize(response == null ? null : response.paymentId());
            if (!paymentId.isBlank() && !hasForeignPaymentIdBinding(paymentId, invoice.getId(), anchor.getId())) {
                anchor.setTbankPaymentId(limit(paymentId, 64));
            }
            String terminalKey = normalize(response == null ? null : response.terminalKey());
            if (!terminalKey.isBlank()) {
                anchor.setTbankTerminalKey(limit(terminalKey, 64));
            }
            if (response != null && response.amount() != null && response.amount() > 0) {
                anchor.setAmountKopecks(response.amount());
            }
            anchor.setStatus(PAYMENT_REF_INIT_CONFLICT);
            anchor.setReason(limit("current_payment_registry_collision:" + paymentId, 160));
            paymentRefRepository.save(anchor);
            if (!normalize(anchor.getTbankPaymentId()).isBlank()) {
                entityManager.flush();
            }
        }
        if (matchesPreparedCurrentIntent(invoice, prepared)) {
            clearCurrentPaymentRef(invoice);
        } else {
            invoice.setPaymentUrl(null);
        }
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setNextReminderAt(null);
        invoice.setLastError(limit("payment_registry_collision: у общего счета обнаружено несколько активных T-Bank ссылок; " + "ссылка не выдана, нужна ручная сверка (" + readableException(failure) + ")", 512));
        invoiceRepository.save(invoice);
    }

    boolean isEligibleCommonBillingManager(Manager manager) {
        return manager != null && manager.getUser() != null && manager.getUser().isActive() && manager.getUser().getRoles() != null && manager.getUser().getRoles().stream().anyMatch(role -> role != null && "ROLE_MANAGER".equalsIgnoreCase(role.getName()));
    }

    Optional<CommonInvoice> lockedInvoice(Long invoiceId) {
        return settlementService.lockedInvoice(invoiceId);
    }

    LockedInvoicePaymentPrelude lockedInvoiceByTokenAfterStandalonePaymentPrelude(String token) {
        Set<Long> lockedOrderIds = lockInvoiceOrderAggregatesByToken(token);
        Map<Long, List<PaymentLink>> paymentLinksByOrder = lockPaymentLinksForOrders(lockedOrderIds);
        Optional<CommonInvoice> snapshot = invoiceRepository.findByTokenWithAccount(token);
        Long expectedAccountId = snapshot.map(CommonInvoice::getAccount).map(CommonBillingAccount::getId).orElse(null);
        lockFreshAccountAfterOrderPrelude(expectedAccountId);
        CommonInvoice invoice = invoiceRepository.findByTokenWithAccountForUpdate(token).or(() -> snapshot).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        entityManager.refresh(invoice);
        ensureInvoiceAccountUnchanged(invoice, expectedAccountId);
        ensureInvoiceMembershipUnchanged(invoice.getId(), lockedOrderIds);
        return new LockedInvoicePaymentPrelude(invoice, paymentLinksByOrder);
    }

    CommonBillingAccount lockFreshAccountAfterOrderPrelude(Long accountId) {
        return settlementService.lockFreshAccountAfterOrderPrelude(accountId);
    }

    void ensureInvoiceAccountUnchanged(CommonInvoice invoice, Long expectedAccountId) {
        settlementService.ensureInvoiceAccountUnchanged(invoice, expectedAccountId);
    }

    Set<Long> lockInvoiceOrderAggregatesByToken(String token) {
        if (normalize(token).isBlank()) {
            return Set.of();
        }
        return lockOrderAggregates(invoiceOrderRepository.findOrderIdsByInvoiceToken(token));
    }

    Set<Long> lockOrderAggregates(Collection<Long> orderIds) {
        return settlementService.lockOrderAggregates(orderIds);
    }

    Map<Long, List<PaymentLink>> lockPaymentLinksForOrders(Collection<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<PaymentLink>> locked = new HashMap<>();
        for (Long orderId : new TreeSet<>(orderIds.stream().filter(Objects::nonNull).collect(Collectors.toSet()))) {
            List<PaymentLink> links = paymentLinkRepository.findByOrderIdForUpdate(orderId);
            locked.put(orderId, links == null ? List.of() : List.copyOf(links));
        }
        return Map.copyOf(locked);
    }

    Map<Long, List<PaymentLink>> paymentLinksRequiringCommonInvoiceRouteCheck(Map<Long, List<PaymentLink>> paymentLinksByOrder, Collection<CommonInvoiceOrder> items, Set<PaymentLink> appliedStandalonePayments) {
        return settlementService.paymentLinksRequiringCommonInvoiceRouteCheck(paymentLinksByOrder, items, appliedStandalonePayments);
    }

    void ensureNoCompetingStandaloneRoutesOrThrow(Map<Long, List<PaymentLink>> paymentLinksByOrder) {
        settlementService.ensureNoCompetingStandaloneRoutesOrThrow(paymentLinksByOrder);
    }

    int closeProvablyUnstartedStandaloneRoutesOrThrow(Map<Long, List<PaymentLink>> paymentLinksByOrder, Long invoiceId) {
        Map<Long, List<PaymentLink>> routes = paymentLinksByOrder == null ? Map.of() : paymentLinksByOrder;
        List<PaymentLink> closable = new ArrayList<>();
        for (Map.Entry<Long, List<PaymentLink>> entry : routes.entrySet()) {
            for (PaymentLink link : entry.getValue()) {
                if (isSafelyClosedStandaloneRoute(link)) {
                    continue;
                }
                if (!StandaloneBankPaymentPolicy.canAutoCloseForCommonInvoice(link)) {
                    ensureNoCompetingStandaloneRoutesOrThrow(Map.of(entry.getKey(), List.of(link)));
                }
                closable.add(link);
            }
        }
        if (closable.isEmpty()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        for (PaymentLink link : closable) {
            Long orderId = link.getOrder() == null ? null : link.getOrder().getId();
            taskReceiptIntegrationService.release(link, "Отдельный маршрут отменен при создании общего счета");
            link.setStatus(PaymentLinkStatus.CANCELED);
            link.setExpiresAt(link.getExpiresAt() == null || link.getExpiresAt().isAfter(now) ? now : link.getExpiresAt());
            link.setLastError(limit("common_invoice_unstarted_route_auto_closed: invoice=" + (invoiceId == null ? "pending" : invoiceId) + "; order=" + (orderId == null ? "?" : orderId), 512));
        }
        paymentLinkRepository.saveAll(closable);
        log.info("Автоматически закрыты неинициализированные отдельные платежные маршруты: invoice={}, links={}", invoiceId, closable.stream().map(PaymentLink::getId).toList());
        return closable.size();
    }

    Set<PaymentLink> synchronizeConfirmedStandalonePaymentsOrThrow(CommonInvoice invoice, List<CommonInvoiceOrder> items, Map<Long, List<PaymentLink>> paymentLinksByOrder) {
        return settlementService.synchronizeConfirmedStandalonePaymentsOrThrow(invoice, items, paymentLinksByOrder);
    }

    boolean hasCurrentCommonPaymentRoute(CommonInvoice invoice) {
        return settlementService.hasCurrentCommonPaymentRoute(invoice);
    }

    boolean isSafelyClosedStandaloneRoute(PaymentLink link) {
        return settlementService.isSafelyClosedStandaloneRoute(link);
    }

    void markStandalonePaymentRouteConflict(CommonInvoice invoice, ResponseStatusException conflict) {
        settlementService.markStandalonePaymentRouteConflict(invoice, conflict);
    }

    void ensureInvoiceMembershipUnchanged(Long invoiceId, Set<Long> lockedOrderIds) {
        settlementService.ensureInvoiceMembershipUnchanged(invoiceId, lockedOrderIds);
    }

    <T> T writeTransaction(Supplier<T> action) {
        return settlementService.writeTransaction(action);
    }

    ResponseStatusException invoiceMembershipChanged(String detail) {
        return settlementService.invoiceMembershipChanged(detail);
    }

    void ensureNoOperationInProgress(CommonInvoice invoice) {
        recoverStaleOperationInProgress(invoice);
        String error = normalize(invoice == null ? null : invoice.getLastError());
        if (isMessageSendInProgress(error)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Отправка сообщения общего счета уже выполняется");
        }
        if (PAYMENT_INIT_IN_PROGRESS.equals(error)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Создание платежной ссылки общего счета уже выполняется");
        }
    }

    void ensureNoBlockingPaymentRefsForNewInit(CommonInvoice invoice) {
        if (invoice == null || invoice.getId() == null) {
            return;
        }
        if (paymentRefRepository.existsByInvoice_IdAndStatusIn(invoice.getId(), PAYMENT_INIT_NEW_ATTEMPT_BLOCKING_REF_STATUSES)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "У общего счета есть незавершенный или подтвержденный платеж Т-Банка; " + "новую ссылку создавать нельзя до безопасного завершения сверки");
        }
    }

    void recoverStaleOperationInProgress(CommonInvoice invoice) {
        if (invoice == null) {
            return;
        }
        // A timeout cannot prove that a pre-cutover provider call did not deliver.
        // Keep this fence in every recovery entry point, not only the send command.
        if (hasUnresolvedLegacyMessage(invoice)) {
            return;
        }
        if (messageQueue.status(invoice.getPaymentMessageOperationId()) != null) return;
        String error = normalize(invoice.getLastError());
        if (!isMessageSendInProgress(error) && !PAYMENT_INIT_IN_PROGRESS.equals(error)) {
            return;
        }
        LocalDateTime operationStartedAt = PAYMENT_INIT_IN_PROGRESS.equals(error) ? paymentInitStartedAt(invoice) : invoice.getUpdatedAt();
        if (operationStartedAt == null || operationStartedAt.plus(OPERATION_IN_PROGRESS_TIMEOUT).isAfter(LocalDateTime.now())) {
            return;
        }
        if (PAYMENT_INIT_IN_PROGRESS.equals(error)) {
            archiveAndClearCurrentPaymentRef(invoice, "payment_init_stale_timeout");
            invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
            invoice.setNextReminderAt(null);
            invoice.setLastError(limit(PAYMENT_INIT_STALE + ": создание T-Bank ссылки зависло; проверьте банк вручную перед повторной оплатой", 512));
        } else if (PAYMENT_ROUTE_CHANGED_MESSAGE_IN_PROGRESS.equals(error)) {
            invoice.setStatus(invoice.getPaidKopecks() > 0 ? CommonInvoiceStatus.PARTIALLY_PAID : CommonInvoiceStatus.READY);
            invoice.setSentAt(null);
            invoice.setNextReminderAt(null);
            invoice.setLastError(limit(PAYMENT_ROUTE_CHANGED_MESSAGE_RETRY + "stale: отправка обновленного способа оплаты зависла", 512));
        } else {
            invoice.setLastError(limit(MESSAGE_SEND_STALE + ": отправка сообщения зависла; можно повторить отправку вручную", 512));
        }
        invoiceRepository.save(invoice);
    }

    boolean hasUnresolvedLegacyMessage(CommonInvoice invoice) {
        if (invoice == null || !normalize(invoice.getPaymentMessageOperationId()).isBlank()) {
            return false;
        }
        String error = normalize(invoice.getLastError());
        boolean routeRetry = error.startsWith(PAYMENT_ROUTE_CHANGED_MESSAGE_RETRY);
        if (routeRetry) {
            error = error.substring(PAYMENT_ROUTE_CHANGED_MESSAGE_RETRY.length()).stripLeading();
        }
        return isMessageSendInProgress(error)
                || error.startsWith("operation_unknown:")
                || error.startsWith("send_exception:")
                || error.startsWith(MESSAGE_SEND_STALE + ":")
                || (routeRetry && error.startsWith("stale:"));
    }

    boolean isMessageSendInProgress(String state) {
        String normalized = normalize(state);
        return MESSAGE_SEND_IN_PROGRESS.equals(normalized) || PAYMENT_ROUTE_CHANGED_MESSAGE_IN_PROGRESS.equals(normalized);
    }

    LocalDateTime paymentInitStartedAt(CommonInvoice invoice) {
        if (invoice == null) {
            return null;
        }
        if (invoice.getTbankPaymentCreatedAt() != null) {
            return invoice.getTbankPaymentCreatedAt();
        }
        String orderId = normalize(invoice.getTbankOrderId());
        if (orderId.isBlank()) {
            return null;
        }
        return paymentRefRepository.findByTbankOrderId(orderId).filter(ref -> Objects.equals(invoice.getId(), paymentRefInvoiceId(ref))).map(CommonInvoicePaymentRef::getCreatedAt).orElse(null);
    }

    boolean canAcceptPublicPayment(CommonInvoice invoice) {
        return invoice != null && !isOwnerPaperInvoice(invoice) && PUBLIC_PAYABLE_STATUSES.contains(invoice.getStatus());
    }

    String paymentRefStatus(CommonInvoicePaymentRef ref) {
        return settlementService.paymentRefStatus(ref);
    }

    String normalizedPaymentProvider(CommonInvoicePaymentRef ref) {
        return settlementService.normalizedPaymentProvider(ref);
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

    void refreshInvoiceAmounts(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        settlementService.refreshInvoiceAmounts(invoice, items);
    }

    boolean allOrdersReady(List<CommonInvoiceOrder> items) {
        return settlementService.allOrdersReady(items);
    }

    void ensureCommonPaymentRouteSelected(CommonInvoice invoice, long remainingKopecks) {
        if (invoice == null || invoice.getId() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден");
        }
        if (hasFrozenCommonPaymentRoute(invoice)) {
            if (invoice.getContractorAllocationId() == null) {
                return;
            }
            FrozenCommonRouteAction action = contractorPaymentLiveRoutingService.frozenCommonRouteAction(invoice.getId(), invoice.getContractorAllocationId());
            if (action == FrozenCommonRouteAction.KEEP) {
                return;
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Предыдущий платеж общего счета требует сверки вручную; автоматическая повторная выдача " + "реквизитов заблокирована");
        }
        List<CommonInvoiceOrder> routeItems = invoiceOrderRepository.findByInvoiceIdWithOrders(invoice.getId());
        Manager routeManager = paymentProfileService.lockManagerForRouting(managerForNewCommonPaymentAction(invoice, routeItems));
        List<Order> routeOrders = routeItems.stream().map(CommonInvoiceOrder::getOrder).filter(Objects::nonNull).toList();
        if (contractorPaymentLiveRoutingService.enabledForNewRoutes()) {
            contractorPaymentShadowService.prepareLiveCommonInvoiceSource(invoice, routeOrders, routeManager, remainingKopecks, LocalDateTime.now());
        } else {
            contractorPaymentShadowService.prepareCommonInvoiceSource(invoice, routeOrders, routeManager, remainingKopecks, LocalDateTime.now());
        }
        PaymentRouteSelection route;
        ContractorPaymentAllocation liveAllocation = null;
        Optional<PaymentRouteSelection> taskRoute = hasCurrentCommonPaymentRoute(invoice) ? Optional.empty() : invoiceRouteSelector.selectCommonInvoiceTaskRoute(invoice, routeManager, remainingKopecks);
        if (hasCurrentCommonPaymentRoute(invoice)) {
            route = legacyTbankRoute(invoice, remainingKopecks);
        } else if (taskRoute.isPresent()) {
            route = taskRoute.get();
        } else if (contractorPaymentLiveRoutingService.enabledForNewRoutes()) {
            try {
                liveAllocation = requireLiveCommonInvoiceAllocation(invoice, contractorPaymentLiveRoutingService.reserveForCommonInvoice(invoice, routeOrders, routeManager, remainingKopecks));
                route = isContractorRecipient(liveAllocation) ? contractorCommonPaymentRoute(invoice, liveAllocation) : invoiceRouteSelector.selectCommonInvoiceOwnerAcquiringRoute(routeManager, remainingKopecks);
            } catch (RuntimeException exception) {
                notifyCommonInvoiceRoutingFailure(invoice, routeOrders, exception);
                throw exception;
            }
        } else if (contractorPaymentLiveRoutingService.configuredButBlockedForNewRoutes()) {
            ResponseStatusException exception = new ResponseStatusException(HttpStatus.CONFLICT, "Новая система оплат включена, но LIVE-маршрут общего счета временно недоступен");
            notifyCommonInvoiceRoutingFailure(invoice, routeOrders, exception);
            throw exception;
        } else {
            route = invoiceRouteSelector.selectCommonInvoiceRoute(routeManager, remainingKopecks);
        }
        applyCommonPaymentRoute(invoice, route, remainingKopecks);
        if (liveAllocation != null) {
            invoice.setContractorAllocationId(liveAllocation.getId());
            if (isContractorRecipient(liveAllocation)) {
                // Contractor PII is stored only in the encrypted allocation
                // snapshot, never in legacy common-invoice columns.
                invoice.setPaymentRouteManualPhone(null);
                invoice.setPaymentRouteManualRecipient(null);
                invoice.setPaymentRouteManualBankName(limit(liveAllocation.getBankNameSnapshot(), 120));
                // Custom comments and generated instruction text are resolved
                // from the encrypted allocation snapshot only.
                invoice.setPaymentRouteManualComment(null);
                invoice.setPaymentRouteInstructionText(null);
            }
        }
        invoiceRepository.save(invoice);
        scheduleContractorShadowRoute(invoice.getId(), invoice.getShadowRouteGeneration());
        log.info("За общим счетом {} закреплен маршрут оплаты {}: profile={}, task={}, amountKopecks={}", invoice.getId(), invoice.getPaymentRouteType(), invoice.getPaymentRouteProfileId(), invoice.getPaymentRouteManualTaskId(), invoice.getPaymentRouteAmountKopecks());
    }

    boolean isContractorRecipient(ContractorPaymentAllocation allocation) {
        return allocation != null && (allocation.getRecipientType() == ContractorRecipientType.SPECIALIST || allocation.getRecipientType() == ContractorRecipientType.MANAGER);
    }

    ContractorPaymentAllocation requireLiveCommonInvoiceAllocation(CommonInvoice invoice, ContractorPaymentAllocation allocation) {
        if (allocation != null && allocation.getId() != null) {
            return allocation;
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Новая система оплат не зафиксировала получателя общего счета №" + (invoice == null || invoice.getId() == null ? "-" : invoice.getId()) + "; счет не отправлен");
    }

    void notifyCommonInvoiceRoutingFailure(CommonInvoice invoice, List<Order> routeOrders, RuntimeException exception) {
        Long invoiceId = invoice == null ? null : invoice.getId();
        String details = readableException(exception);
        (routeOrders == null ? List.<Order>of() : routeOrders).stream().filter(Objects::nonNull).map(Order::getId).filter(Objects::nonNull).distinct().forEach(orderId -> paymentIssueReminderService.notifyOrderIssue(orderId, PaymentIssueReminderService.SOURCE_PAYMENT_FAIL_CLOSED, invoiceId, "Общий счёт требует внимания: №" + (invoiceId == null ? "-" : invoiceId), "Система остановила отправку общего счёта: LIVE-маршрут оплаты не зафиксирован." + "\nОбщий счёт: №" + (invoiceId == null ? "-" : invoiceId) + "\nПричина: " + details + "\nКлиенту сомнительные реквизиты не отправлены."));
    }

    PaymentRouteSelection contractorCommonPaymentRoute(CommonInvoice invoice, ContractorPaymentAllocation allocation) {
        String recipient = normalize(allocation.getRecipientNameSnapshot());
        String phone = normalize(allocation.getPaymentPhoneSnapshot());
        if (recipient.isBlank() || phone.isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "В зафиксированном платежном профиле отсутствуют обязательные реквизиты");
        }
        return new PaymentRouteSelection(PaymentMethod.MANUAL_MOBILE_BANK.name(), null, "CONTRACTOR", allocation.getRecipientType() == ContractorRecipientType.SPECIALIST ? "Платёжный профиль специалиста" : "Платёжный профиль менеджера", "", ManualPaymentSource.CONTRACTOR_PAYMENT_PROFILE.name(), null, ManualPaymentType.MOBILE_BANK.name(), "", "", "", "", "", "");
    }

    void scheduleContractorShadowRoute(Long invoiceId, String routeGeneration) {
        if (invoiceId == null) {
            return;
        }
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {

                @Override
                public void afterCommit() {
                    reserveContractorShadowRouteSafely(invoiceId, routeGeneration);
                }
            });
            return;
        }
        reserveContractorShadowRouteSafely(invoiceId, routeGeneration);
    }

    void reserveContractorShadowRouteSafely(Long invoiceId, String routeGeneration) {
        try {
            contractorPaymentShadowService.reserveForCommonInvoiceId(invoiceId, routeGeneration);
        } catch (RuntimeException e) {
            log.error("Не удалось записать тестовый маршрут общего счета: invoiceId={}, code={}", invoiceId, e.getClass().getSimpleName());
        }
    }

    boolean hasFrozenCommonPaymentRoute(CommonInvoice invoice) {
        return CommonInvoiceRouteState.hasFrozenCommonPaymentRoute(invoice);
    }

    PaymentRouteSelection legacyTbankRoute(CommonInvoice invoice, long remainingKopecks) {
        PaymentProfile profile = paymentProfileService.findByTerminalKey(invoice.getTbankTerminalKey()).orElseGet(() -> paymentProfileService.selectForManager(managerForNewCommonPaymentAction(invoice)));
        profile = paymentProfileService.lockForRouting(profile);
        TbankPaymentProfile runtimeProfile = normalize(invoice.getTbankTerminalKey()).isBlank() ? paymentProfileService.toRuntime(profile) : paymentProfileService.toRuntimeForTerminal(profile, invoice.getTbankTerminalKey());
        if (runtimeProfile == null) {
            runtimeProfile = paymentProfileService.toRuntime(profile);
        }
        return new PaymentRouteSelection(TbankRuntimeSettingsService.PAYMENT_SOURCE_TBANK_LINK, profile == null ? null : profile.getId(), normalize(profile == null ? null : profile.getCode()), normalize(profile == null ? null : profile.getName()), normalize(runtimeProfile == null ? invoice.getTbankTerminalKey() : runtimeProfile.terminalKey()), null, null, null, "", "", "", "", "", "");
    }

    PaymentProfile lockedCommonPaymentProfile(CommonInvoice invoice) {
        return paymentProfileService.lockByIdForRouting(invoice.getPaymentRouteProfileId());
    }

    void applyCommonPaymentRoute(CommonInvoice invoice, PaymentRouteSelection route, long remainingKopecks) {
        if (route == null || normalize(route.routeType()).isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Не удалось выбрать способ оплаты общего счета");
        }
        invoice.setPaymentRouteType(limit(normalize(route.routeType()).toUpperCase(Locale.ROOT), 32));
        invoice.setPaymentRouteProfileId(route.paymentProfileId());
        invoice.setPaymentRouteProfileCode(limit(route.paymentProfileCode(), 64));
        invoice.setPaymentRouteProfileName(limit(route.paymentProfileName(), 120));
        invoice.setPaymentRouteTerminalKey(limit(route.paymentProfileTerminalKey(), 64));
        invoice.setPaymentRouteManualSource(enumValue(ManualPaymentSource.class, route.manualSource()));
        invoice.setPaymentRouteManualTaskId(route.manualTaskId());
        invoice.setPaymentRouteManualTaskSourceGeneration(route.manualTaskSourceGeneration());
        invoice.setPaymentRouteManualTaskGeneration(route.manualTaskGeneration());
        invoice.setPaymentRouteManualType(enumValue(ManualPaymentType.class, route.manualPaymentType()));
        invoice.setPaymentRouteManualPhone(limit(route.manualPhone(), 32));
        invoice.setPaymentRouteManualRecipient(limit(route.manualRecipientName(), 160));
        invoice.setPaymentRouteManualBankName(limit(route.manualBankName(), 120));
        invoice.setPaymentRouteManualUrl(limit(route.manualPaymentUrl(), 512));
        invoice.setPaymentRouteManualButton(limit(route.manualPaymentButtonLabel(), 80));
        invoice.setPaymentRouteManualComment(limit(route.manualComment(), 255));
        invoice.setPaymentRouteInstructionText(limit(route.instructionText(), 1000));
        invoice.setPaymentRouteAmountKopecks(remainingKopecks);
        invoice.setPaymentRouteSelectedAt(LocalDateTime.now());
    }

    <T extends Enum<T>> T enumValue(Class<T> type, String value) {
        String clean = normalize(value).toUpperCase(Locale.ROOT);
        return clean.isBlank() ? null : Enum.valueOf(type, clean);
    }

    boolean isTbankCommonRoute(CommonInvoice invoice) {
        return invoicePresenter.isTbankCommonRoute(invoice);
    }

    Company chatCompany(CommonInvoice invoice) {
        return chatCompany(invoice, null);
    }

    Company chatCompany(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        if (invoice.getAccount().getInvoiceCompany() != null) {
            return invoice.getAccount().getInvoiceCompany();
        }
        List<CommonInvoiceOrder> resolvedItems = items == null ? invoiceOrderRepository.findByInvoiceIdWithOrders(invoice.getId()) : items;
        return resolvedItems.stream().map(CommonInvoiceOrder::getOrder).map(Order::getCompany).filter(c -> c != null).findFirst().orElse(null);
    }

    Manager manager(CommonInvoice invoice) {
        return manager(invoice, null);
    }

    Manager manager(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        if (invoice.getAccount().getManager() != null) {
            return invoice.getAccount().getManager();
        }
        Company company = chatCompany(invoice, items);
        return company == null ? null : company.getManager();
    }

    Manager managerForNewCommonPaymentAction(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        Manager assigned = manager(invoice, items);
        if (assigned == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Для общего счёта не назначен менеджер. Назначьте менеджера в настройках связи " + "перед созданием нового способа оплаты");
        }
        if (isEligibleCommonBillingManager(assigned)) {
            return assigned;
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Назначенный менеджер общего счёта неактивен или больше не имеет роли менеджера. " + "Переназначьте менеджера в настройках связи перед созданием или отправкой оплаты");
    }

    Manager managerForNewCommonPaymentAction(CommonInvoice invoice) {
        return managerForNewCommonPaymentAction(invoice, null);
    }

    BigDecimal amountRubles(long kopecks) {
        return settlementService.amountRubles(kopecks);
    }

    long remainingKopecks(CommonInvoice invoice) {
        return settlementService.remainingKopecks(invoice);
    }

    String cleanToken(String token) {
        String clean = normalize(token);
        if (clean.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден");
        }
        return clean;
    }

    String groupTbankOrderId(CommonInvoice invoice) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return ("g" + invoice.getId() + "-" + suffix).substring(0, Math.min(36, ("g" + invoice.getId() + "-" + suffix).length()));
    }

    CommonInvoicePaymentRef createPreparedPaymentInitRef(CommonInvoice invoice, String tbankOrderId, TbankPaymentProfile runtimeProfile, long amountKopecks) {
        CommonInvoicePaymentRef ref = new CommonInvoicePaymentRef();
        ref.setInvoice(invoice);
        ref.setProvider(PROVIDER_TBANK);
        ref.setPaymentProfileId(runtimeProfile == null ? null : runtimeProfile.id());
        ref.setProviderOrderId(limit(tbankOrderId, 64));
        ref.setProviderMerchantId(runtimeProfile == null ? null : limit(runtimeProfile.terminalKey(), 64));
        ref.setProviderTestMode(runtimeProfile == null ? null : runtimeProfile.testMode());
        ref.setProviderStatus("INIT_RESERVED");
        ref.setTbankOrderId(limit(tbankOrderId, 36));
        ref.setTbankTerminalKey(runtimeProfile == null ? null : limit(runtimeProfile.terminalKey(), 64));
        ref.setAmountKopecks(amountKopecks > 0 ? amountKopecks : null);
        ref.setStatus(PAYMENT_REF_INIT_PREPARED);
        ref.setReason("provider_init_prepared");
        paymentRefRepository.save(ref);
        return ref;
    }

    Optional<CommonInvoicePaymentRef> lockedPreparedPaymentRef(PreparedCommonPaymentInit prepared) {
        if (prepared == null || prepared.paymentRefId() == null) {
            return Optional.empty();
        }
        Optional<CommonInvoicePaymentRef> locked = paymentRefRepository.findByIdForUpdate(prepared.paymentRefId());
        if (locked.isPresent() && (!matchesPreparedPaymentRef(locked.get(), prepared) || !PREPARED_PAYMENT_REF_LIFECYCLE_STATUSES.contains(normalize(locked.get().getStatus()).toUpperCase(Locale.ROOT)))) {
            throw invoiceMembershipChanged("подготовленная T-Bank ссылка сменила реквизиты, статус или общий счет");
        }
        return locked;
    }

    boolean matchesPreparedPaymentRef(CommonInvoicePaymentRef ref, PreparedCommonPaymentInit prepared) {
        return ref != null && prepared != null && Objects.equals(prepared.invoiceId(), paymentRefInvoiceId(ref)) && normalize(prepared.tbankOrderId()).equals(normalize(ref.getTbankOrderId())) && (prepared.runtimeProfile() == null || normalize(prepared.runtimeProfile().terminalKey()).equals(normalize(ref.getTbankTerminalKey()))) && (prepared.remainingKopecks() <= 0 || (ref.getAmountKopecks() != null && ref.getAmountKopecks() == prepared.remainingKopecks()));
    }

    boolean matchesPreparedCurrentIntent(CommonInvoice invoice, PreparedCommonPaymentInit prepared) {
        if (invoice == null || prepared == null || prepared.runtimeProfile() == null) {
            return false;
        }
        return normalize(prepared.tbankOrderId()).equals(normalize(invoice.getTbankOrderId())) && normalize(prepared.runtimeProfile().terminalKey()).equals(normalize(invoice.getTbankTerminalKey())) && invoice.getTbankPaymentAmountKopecks() != null && invoice.getTbankPaymentAmountKopecks() == prepared.remainingKopecks();
    }

    PaymentInitFinishResult paymentInitAlreadyHandledByWebhook(CommonInvoice invoice, PreparedCommonPaymentInit prepared, CommonInvoicePaymentRef preparedRef, TbankInitResponse response, String paymentUrl) {
        if (invoice == null || preparedRef == null || response == null || !matchesPreparedPaymentRef(preparedRef, prepared)) {
            return null;
        }
        String responsePaymentId = normalize(response.paymentId());
        String refPaymentId = normalize(preparedRef.getTbankPaymentId());
        if (responsePaymentId.isBlank() || refPaymentId.isBlank() || !responsePaymentId.equals(refPaymentId)) {
            return null;
        }
        String status = normalize(preparedRef.getStatus()).toUpperCase(Locale.ROOT);
        if (PAYMENT_REF_PREPAID.equals(status) || PAYMENT_REF_CONFIRMED.equals(status) || PAYMENT_REF_APPLYING.equals(status) || PAYMENT_REF_APPLIED.equals(status)) {
            return new PaymentInitFinishResult(new PublicPaymentInitResponse(paymentUrl, responsePaymentId, status), null, null);
        }
        if ("REJECTED".equals(status) || PAYMENT_REF_REFUNDED_STATUSES.contains(status)) {
            return new PaymentInitFinishResult(null, HttpStatus.CONFLICT, "T-Bank уже сообщил конечный статус платежа: " + status);
        }
        return null;
    }

    boolean hasForeignPaymentIdBinding(String tbankPaymentId, Long currentInvoiceId, Long allowedPaymentRefId) {
        return settlementService.hasForeignPaymentIdBinding(tbankPaymentId, currentInvoiceId, allowedPaymentRefId);
    }

    boolean isCurrentPaymentRegistryConstraintViolation(RuntimeException failure) {
        return settlementService.isCurrentPaymentRegistryConstraintViolation(failure);
    }

    boolean isPaymentIdentityConstraintViolation(RuntimeException failure) {
        return settlementService.isPaymentIdentityConstraintViolation(failure);
    }

    void archiveCurrentPaymentRef(CommonInvoice invoice, String reason) {
        settlementService.archiveCurrentPaymentRef(invoice, reason);
    }

    Long paymentRefInvoiceId(CommonInvoicePaymentRef ref) {
        return settlementService.paymentRefInvoiceId(ref);
    }

    void archiveAndClearCurrentPaymentRef(CommonInvoice invoice, String reason) {
        settlementService.archiveAndClearCurrentPaymentRef(invoice, reason);
    }

    Optional<CommonInvoicePaymentRef> lockedPaymentRefByProviderBinding(String tbankOrderId, String tbankPaymentId) {
        return settlementService.lockedPaymentRefByProviderBinding(tbankOrderId, tbankPaymentId);
    }

    void quarantineMissingCurrentPaymentAnchor(CommonInvoice invoice, String webhookOrderId, String webhookPaymentId) {
        settlementService.quarantineMissingCurrentPaymentAnchor(invoice, webhookOrderId, webhookPaymentId);
    }

    void recordInitializedPaymentRef(CommonInvoice invoice, PreparedCommonPaymentInit prepared, TbankInitResponse response, String reason) {
        if (invoice == null || prepared == null || response == null) {
            return;
        }
        String responseOrderId = normalize(response.orderId());
        String preparedOrderId = normalize(prepared.tbankOrderId());
        String paymentId = normalize(response.paymentId());
        CommonInvoicePaymentRef ref = lockedPreparedPaymentRef(prepared).orElse(null);
        if (ref == null) {
            ref = new CommonInvoicePaymentRef();
            ref.setInvoice(invoice);
            ref.setTbankOrderId(preparedOrderId.isBlank() ? null : limit(preparedOrderId, 36));
            ref.setTbankTerminalKey(prepared.runtimeProfile() == null ? null : limit(prepared.runtimeProfile().terminalKey(), 64));
            ref.setAmountKopecks(prepared.remainingKopecks() > 0 ? prepared.remainingKopecks() : null);
        }
        if (ref != null && !normalize(ref.getTbankPaymentId()).isBlank() && !paymentId.isBlank() && !normalize(ref.getTbankPaymentId()).equals(paymentId)) {
            ref.setStatus(PAYMENT_REF_INIT_CONFLICT);
            ref.setReason(limit("provider_payment_mismatch:" + paymentId + ":" + reason, 160));
            paymentRefRepository.save(ref);
            return;
        }
        if (hasForeignPaymentIdBinding(paymentId, invoice.getId(), ref.getId())) {
            ref.setStatus(PAYMENT_REF_INIT_CONFLICT);
            ref.setReason(limit("response_payment_id_collision:" + paymentId + ":" + reason, 160));
            paymentRefRepository.save(ref);
            return;
        }
        if (!paymentId.isBlank()) {
            ref.setTbankPaymentId(limit(paymentId, 64));
        }
        String responseTerminalKey = normalize(response.terminalKey());
        ref.setTbankTerminalKey(responseTerminalKey.isBlank() ? (prepared.runtimeProfile() == null ? null : limit(prepared.runtimeProfile().terminalKey(), 64)) : limit(responseTerminalKey, 64));
        ref.setProvider(PROVIDER_TBANK);
        if (ref.getPaymentProfileId() == null && prepared.runtimeProfile() != null) {
            ref.setPaymentProfileId(prepared.runtimeProfile().id());
        }
        ref.setProviderOrderId(preparedOrderId.isBlank() ? null : limit(preparedOrderId, 64));
        ref.setProviderPaymentId(paymentId.isBlank() ? null : limit(paymentId, 64));
        ref.setProviderMerchantId(normalize(ref.getTbankTerminalKey()).isBlank() ? null : limit(ref.getTbankTerminalKey(), 64));
        if (prepared.runtimeProfile() != null) {
            ref.setProviderTestMode(prepared.runtimeProfile().testMode());
        }
        ref.setProviderStatus(limit(response.status(), 32));
        String safeProviderUrl = PaymentUrlPolicy.safe(response.paymentUrl(), PaymentUrlPolicy.Purpose.TBANK_PAYMENT);
        if (!safeProviderUrl.isBlank()) {
            ref.setProviderPaymentUrl(safeProviderUrl);
        }
        ref.setAmountKopecks(response.amount() != null && response.amount() > 0 ? response.amount() : (prepared.remainingKopecks() > 0 ? prepared.remainingKopecks() : null));
        String currentStatus = normalize(ref.getStatus());
        if (currentStatus.isBlank() || PAYMENT_REF_INIT_PREPARED.equals(currentStatus) || PAYMENT_REF_INIT_CONFLICT.equals(currentStatus) || PAYMENT_REF_CURRENT.equals(currentStatus) || PAYMENT_REF_ARCHIVED.equals(currentStatus)) {
            ref.setStatus(canCancelInitializedPaymentRef(ref) ? PAYMENT_REF_CANCEL_PENDING : PAYMENT_REF_INIT_CONFLICT);
        }
        String providerOrderEvidence = responseOrderId.isBlank() || responseOrderId.equals(preparedOrderId) ? "" : "provider_order_mismatch=" + responseOrderId + ":";
        ref.setReason(limit(providerOrderEvidence + reason, 160));
        paymentRefRepository.save(ref);
        if (!paymentId.isBlank()) {
            entityManager.flush();
        }
    }

    void markPreparedPaymentInitConflict(CommonInvoice invoice, PreparedCommonPaymentInit prepared, String reason) {
        if (invoice == null || prepared == null) {
            return;
        }
        CommonInvoicePaymentRef ref = lockedPreparedPaymentRef(prepared).orElse(null);
        if (ref == null) {
            String orderId = normalize(prepared.tbankOrderId());
            if (orderId.isBlank()) {
                return;
            }
            ref = new CommonInvoicePaymentRef();
            ref.setInvoice(invoice);
            ref.setTbankOrderId(limit(orderId, 36));
            ref.setTbankTerminalKey(prepared.runtimeProfile() == null ? null : limit(prepared.runtimeProfile().terminalKey(), 64));
            ref.setAmountKopecks(prepared.remainingKopecks() > 0 ? prepared.remainingKopecks() : null);
        }
        ref.setStatus(PAYMENT_REF_INIT_CONFLICT);
        ref.setReason(limit(reason, 160));
        paymentRefRepository.save(ref);
    }

    boolean canCancelInitializedPaymentRef(CommonInvoicePaymentRef ref) {
        return settlementService.canCancelInitializedPaymentRef(ref);
    }

    void clearCurrentPaymentRef(CommonInvoice invoice) {
        settlementService.clearCurrentPaymentRef(invoice);
    }

    String paymentRefLabel(CommonInvoicePaymentRef ref) {
        return settlementService.paymentRefLabel(ref);
    }

    String paymentRefLabel(String tbankOrderId, String tbankPaymentId) {
        return settlementService.paymentRefLabel(tbankOrderId, tbankPaymentId);
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

    record LockedInvoicePaymentPrelude(CommonInvoice invoice, Map<Long, List<PaymentLink>> paymentLinksByOrder) {
    }

    record PreparedCommonPaymentInit(Long invoiceId, Long paymentRefId, String email, long remainingKopecks, TbankPaymentProfile runtimeProfile, String tbankOrderId, PublicPaymentInitResponse cachedResponse, String deferredFailure, String provider, TochkaPaymentProfile tochkaProfile, List<TochkaPaymentMode> tochkaPaymentModes, LocalDateTime providerExpiresAt) {

        private PreparedCommonPaymentInit(Long invoiceId, Long paymentRefId, String email, long remainingKopecks, TbankPaymentProfile runtimeProfile, String tbankOrderId, PublicPaymentInitResponse cachedResponse, String deferredFailure) {
            this(invoiceId, paymentRefId, email, remainingKopecks, runtimeProfile, tbankOrderId, cachedResponse, deferredFailure, PROVIDER_TBANK, null, null, null);
        }
    }

    record PaymentInitFinishResult(PublicPaymentInitResponse response, HttpStatus failureStatus, String failureMessage) {
    }
}
