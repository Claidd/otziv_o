package com.hunt.otziv.common_billing.service;

import org.springframework.context.annotation.Lazy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.hunt.otziv.bad_reviews.service.BadReviewTaskService;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.client_messages.service.PaymentInvoiceRetryScheduler;
import com.hunt.otziv.common_billing.model.CommonBillingAccount;
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceOrder;
import com.hunt.otziv.common_billing.model.CommonInvoicePaymentRef;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonBillingAccountRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoiceOrderRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoicePaymentRefRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoicePaymentNotificationOutboxRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.config.metrics.R0ObservabilityMetrics;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentShadowService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.next_order.service.NextOrderRequestService;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.review.service.OrderAggregateMutationLockService;
import com.hunt.otziv.p_products.service.OrderStatusService;
import com.hunt.otziv.p_products.service.OrderTransactionService;
import com.hunt.otziv.payments.dto.TbankPaymentProfile;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.model.ManualPaymentSource;
import com.hunt.otziv.payments.model.PaymentMethod;
import com.hunt.otziv.payments.model.PaymentProfile;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.payments.service.PaymentProfileService;
import com.hunt.otziv.payments.service.ManualPaymentTaskService;
import com.hunt.otziv.payments.api.StandalonePaymentState;
import com.hunt.otziv.payments.service.PaymentUrlPolicy;
import com.hunt.otziv.payments.service.TbankTokenSigner;
import com.hunt.otziv.payments.service.ManualPaymentAutoConfirmationService;
import com.hunt.otziv.payments.tochka.dto.TochkaAcquiringInternetPaymentWebhook;
import com.hunt.otziv.payments.tochka.dto.TochkaApiModels.PaymentOperation;
import com.hunt.otziv.payments.tochka.dto.TochkaPaymentProfile;
import com.hunt.otziv.payments.tochka.model.TochkaPaymentMode;
import com.hunt.otziv.payments.tochka.service.TochkaPaymentOperationMapper;
import com.hunt.otziv.payments.tochka.service.TochkaPaymentOperationMapper.ExpectedPayment;
import com.hunt.otziv.payments.tochka.service.TochkaPaymentOperationMapper.MappedPayment;
import com.hunt.otziv.payments.tochka.service.TochkaPaymentProfileResolver;
import com.hunt.otziv.payments.tochka.service.TochkaProviderException;
import com.hunt.otziv.payments.tochka.service.TochkaWebhookVerificationException;
import com.hunt.otziv.review_recovery.service.ReviewRecoveryGateService;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.CaughtFailureStage.CLOSE_ORDER;
import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.CaughtFailureStage.OPEN_NEXT_ORDER;
import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.TransactionFlow.COMMON_INVOICE_CLOSE;

@Service
@Slf4j
@RequiredArgsConstructor
public class CommonInvoiceSettlementService implements com.hunt.otziv.common_billing.api.CommonInvoicePaymentOperations {

    public static final String STATUS_WAITING_COMMON_INVOICE = "Ожидает общего счета";

    static final String STATUS_PUBLIC = "Опубликовано";

    static final String STATUS_TO_PAY = "Выставлен счет";

    static final String STATUS_REMINDER = "Напоминание";

    static final Set<String> ACTIVE_WORK_STATUSES = Set.of("Новый", "Нагул", "В проверку", "Коррекция", "На проверке", "Публикация");

    static final Set<String> READY_ON_ATTACH_STATUSES = Set.of(STATUS_PUBLIC, STATUS_TO_PAY, STATUS_REMINDER, STATUS_WAITING_COMMON_INVOICE);

    static final Set<CommonInvoiceStatus> MARK_PAID_STATUSES = Set.of(CommonInvoiceStatus.READY, CommonInvoiceStatus.INVOICED, CommonInvoiceStatus.REMINDER, CommonInvoiceStatus.PARTIALLY_PAID, CommonInvoiceStatus.UNPAID);

    static final int DEFAULT_REMINDER_INTERVAL_DAYS = 2;

    static final String PAYMENT_REF_CONFIRMED = "CONFIRMED";

    static final String PAYMENT_REF_PREPAID = "PREPAID";

    static final String PAYMENT_REF_APPLYING = "APPLYING";

    static final String PAYMENT_REF_APPLIED = "APPLIED";

    static final String PAYMENT_REF_ARCHIVED = "ARCHIVED";

    static final String PAYMENT_REF_CANCEL_PENDING = "CANCEL_PENDING";

    static final String PAYMENT_REF_CANCELING = "CANCELING";

    static final String PAYMENT_REF_CANCELED = "CANCELED";

    static final String PAYMENT_REF_CANCEL_FAILED = "CANCEL_FAILED";

    static final String PAYMENT_REF_CANCEL_FAILED_FINAL = "CANCEL_FAILED_FINAL";

    static final String PAYMENT_METHOD_TBANK = "TBANK";

    static final String PAYMENT_METHOD_TOCHKA = "TOCHKA";

    static final String PAYMENT_METHOD_MANUAL = "MANUAL";

    static final String PAYMENT_METHOD_MIXED = "MIXED";

    static final String PAYMENT_REF_INIT_PREPARED = "INIT_PREPARED";

    static final String PAYMENT_REF_INIT_CONFLICT = "INIT_CONFLICT";

    static final String PAYMENT_REF_CURRENT = "CURRENT";

    static final String PROVIDER_TBANK = PaymentProfile.PROVIDER_TBANK;

    static final String PROVIDER_TOCHKA = PaymentProfile.PROVIDER_TOCHKA;

    static final String MANUAL_PAYMENT_TBANK_RECONCILIATION_IN_PROGRESS = "manual_payment_tbank_reconciliation_in_progress:";

    static final String MANUAL_PAYMENT_TBANK_RECONCILIATION_RETRY = "manual_payment_tbank_reconciliation_retry:";

    static final String MANUAL_PAYMENT_TBANK_PAYMENT_DETECTED = "manual_payment_tbank_payment_detected:";

    static final String PREPAID_WAITING_COMMON_INVOICE_READY = "prepaid_waiting_common_invoice_ready";

    static final Set<String> PAYMENT_REF_REFUNDED_STATUSES = Set.of("REFUNDED", "PARTIAL_REFUNDED", "REVERSED", "PARTIAL_REVERSED", "CANCELED");

    static final Set<String> PAYMENT_REF_NONTERMINAL_DOWNGRADE_PROTECTED_STATUSES = Set.of(PAYMENT_REF_PREPAID, PAYMENT_REF_CONFIRMED, PAYMENT_REF_APPLYING, PAYMENT_REF_APPLIED, PAYMENT_REF_CANCELED, "REJECTED", "REFUNDED", "PARTIAL_REFUNDED", "REVERSED", "PARTIAL_REVERSED");

    static final int TRANSACTION_LOCK_RETRY_ATTEMPTS = 3;

    static final long TRANSACTION_LOCK_RETRY_DELAY_MS = 300L;

    static final String TOCHKA_REFUND_SUBMITTING_PREFIX = "tochka_refund_submitting:";

    static final String PAYMENT_INIT_IN_PROGRESS = "payment_init_in_progress";

    static final String PAYMENT_CANCEL_FAILED_FINAL = "payment_cancel_failed_final";

    static final String MIGRATION_PAYMENT_REGISTRY_ATTENTION = "migration_common_payment_registry:";

    static final String INVOICE_MEMBERSHIP_CHANGED = "common_invoice_membership_changed";

    static final String STANDALONE_PAYMENT_ROUTE_CONFLICT = "standalone_payment_route_conflict";

    static final String CONTRACTOR_COMMON_SOURCE_CONFIRMATION_AUDIT_PREFIX = "contractor_common_source_confirmation;";

    static final Set<PaymentLinkStatus> SAFELY_CLOSED_STANDALONE_PAYMENT_STATUSES = Set.of(PaymentLinkStatus.REJECTED, PaymentLinkStatus.CANCELED, PaymentLinkStatus.REVERSED, PaymentLinkStatus.REFUNDED);

    static final Set<PaymentLinkStatus> STANDALONE_PAYMENT_REVERSAL_STATUSES = Set.of(PaymentLinkStatus.REVERSED, PaymentLinkStatus.PARTIAL_REVERSED, PaymentLinkStatus.REFUNDED, PaymentLinkStatus.PARTIAL_REFUNDED);

    private final PlatformTransactionManager transactionManager;

    private final EntityManager entityManager;

    private final CommonBillingAccountRepository accountRepository;

    private final CommonInvoiceRepository invoiceRepository;

    private final CommonInvoiceOrderRepository invoiceOrderRepository;

    private final CommonInvoicePaymentRefRepository paymentRefRepository;

    private final CommonInvoicePaymentNotificationOutboxRepository paymentNotificationOutboxRepository;

    private final OrderRepository orderRepository;

    private final OrderAggregateMutationLockService orderAggregateMutationLockService;

    private final PaymentLinkRepository paymentLinkRepository;
    private final StandalonePaymentState standalonePaymentState;

    private final CommonInvoiceAfterCommitSender commonInvoiceAfterCommitSender;

    private final ManualPaymentTaskService manualPaymentTaskService;

    private final OrderStatusService orderStatusService;

    @Autowired
    @Lazy
    private OrderTransactionService orderTransactionService;

    @Autowired
    @Lazy
    private NextOrderRequestService nextOrderRequestService;

    private final BadReviewTaskService badReviewTaskService;

    private final PaymentInvoiceRetryScheduler paymentInvoiceRetryScheduler;

    private final ManualPaymentAutoConfirmationService manualPaymentAutoConfirmationService;

    private final AppSettingService appSettingService;

    private final ContractorPaymentShadowService contractorPaymentShadowService;

    private final CommonManualPaymentAttributionCoordinator commonManualPaymentAttributionCoordinator;

    private final PaymentProfileService paymentProfileService;

    private final TbankTokenSigner tokenSigner;

    private final TochkaPaymentProfileResolver tochkaPaymentProfileResolver;

    private final TochkaPaymentOperationMapper tochkaPaymentOperationMapper;

    private final ReviewRecoveryGateService recoveryGateService;

    private final R0ObservabilityMetrics observabilityMetrics;

    @Transactional(readOnly = true)
    public boolean isOrderInActiveCommonInvoice(Long orderId) {
        if (orderId == null) {
            return false;
        }
        return invoiceOrderRepository.findByOrderIdWithInvoice(orderId).map(CommonInvoiceOrder::getInvoice).map(invoice -> invoice.getStatus() != CommonInvoiceStatus.PAID && invoice.getStatus() != CommonInvoiceStatus.BAN && invoice.getStatus() != CommonInvoiceStatus.ARCHIVED && invoice.getStatus() != CommonInvoiceStatus.DISABLED).orElse(false);
    }

    boolean isTochkaRefundSubmissionClaimed(String reason) {
        String clean = normalize(reason);
        return clean.startsWith(TOCHKA_REFUND_SUBMITTING_PREFIX) || clean.startsWith("tochka_refund_requested") || clean.startsWith("tochka_refund_in_progress") || clean.startsWith("tochka_refund_observed_external") || clean.startsWith("tochka_refund_outcome_unknown") || clean.startsWith("tochka_refund_confirmation_timeout");
    }

    boolean isTochkaCancellationLifecycleStatus(String status) {
        return Set.of(PAYMENT_REF_CANCEL_PENDING, PAYMENT_REF_CANCELING, PAYMENT_REF_CANCEL_FAILED, PAYMENT_REF_CANCEL_FAILED_FINAL).contains(normalize(status).toUpperCase(Locale.ROOT));
    }

    void setTochkaReasonUnlessRefundClaimed(CommonInvoicePaymentRef ref, String nextReason) {
        if (ref == null || isTochkaRefundSubmissionClaimed(ref.getReason())) {
            return;
        }
        ref.setReason(limit(nextReason, 160));
    }

    @Transactional
    public boolean applyConfirmedOrderPayment(Long orderId, LocalDateTime paidAt, String reason) {
        if (orderId == null) {
            return false;
        }
        // PaymentLinkService already follows Order -> PaymentLink. Re-locking the
        // same canonical row is safe and makes this entry point correct when it
        // is invoked independently as well. Do not lock every sibling here:
        // two concurrent standalone confirmations may already own different
        // order rows from the same invoice.
        orderAggregateMutationLockService.lock(orderId);
        List<PaymentLink> lockedPaymentLinks = paymentLinkRepository.findByOrderIdForUpdate(orderId);
        if (lockedPaymentLinks == null) {
            lockedPaymentLinks = List.of();
        }
        Optional<CommonInvoiceOrder> optionalItem = invoiceOrderRepository.findByOrderIdWithInvoice(orderId);
        if (optionalItem.isEmpty()) {
            return false;
        }
        CommonInvoiceOrder item = optionalItem.get();
        CommonInvoice itemInvoice = item.getInvoice();
        Long invoiceId = itemInvoice == null ? null : itemInvoice.getId();
        if (invoiceId == null) {
            return false;
        }
        CommonInvoice invoice = lockedInvoiceAfterOrderPrelude(invoiceId).orElse(itemInvoice);
        if (invoice.getStatus() == CommonInvoiceStatus.PAID || invoice.getStatus() == CommonInvoiceStatus.UNPAID || invoice.getStatus() == CommonInvoiceStatus.BAN || invoice.getStatus() == CommonInvoiceStatus.DISABLED || invoice.getStatus() == CommonInvoiceStatus.NEEDS_ATTENTION) {
            return false;
        }
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        CommonInvoiceOrder target = items.stream().filter(candidate -> candidate.getOrder() != null && orderId.equals(candidate.getOrder().getId())).findFirst().orElse(item);
        boolean alreadyApplied = target.isPaid();
        try {
            Set<PaymentLink> appliedStandalonePayments = synchronizeConfirmedStandalonePaymentsOrThrow(invoice, items, Map.of(orderId, List.copyOf(lockedPaymentLinks)));
            if (appliedStandalonePayments.isEmpty()) {
                throw standalonePaymentConflict(orderId, "обратный вызов оплаты не нашел единственный подтвержденный отдельный платеж");
            }
            ensureNoCompetingStandaloneRoutesOrThrow(paymentLinksRequiringCommonInvoiceRouteCheck(Map.of(orderId, List.copyOf(lockedPaymentLinks)), List.of(target), appliedStandalonePayments));
        } catch (ResponseStatusException conflict) {
            markStandalonePaymentRouteConflict(invoice, conflict);
            return false;
        }
        // The caller already supplied the confirmed standalone payment. Avoid
        // discovering and closing sibling standalone links while only the
        // target order is locked.
        refreshInvoiceAmounts(invoice, items);
        if (!items.isEmpty() && items.stream().allMatch(CommonInvoiceOrder::isPaid)) {
            closeOrderAsPaidForConfirmedItem(invoice, target);
            closePaidInvoice(invoice, items);
        } else {
            closeOrderAsPaidForConfirmedItem(invoice, target);
            invoice.setStatus(CommonInvoiceStatus.PARTIALLY_PAID);
            if (invoice.getNextReminderAt() == null) {
                invoice.setNextReminderAt(nextAutomaticPaymentReminderAt(LocalDateTime.now()));
            }
            invoiceRepository.save(invoice);
        }
        log.info("Оплата отдельной ссылки заказа {} зачтена в общий счет {}: {}", orderId, invoiceId, normalize(reason));
        return !alreadyApplied;
    }

    /**
     * Quarantines an invoice when the provider later reverses/refunds the exact
     * standalone payment that funded one of its positions. Paid flags are kept
     * intact until a human reconciles the returned money.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean applyStandalonePaymentReversal(Long orderId, Long paymentLinkId, PaymentLinkStatus terminalStatus) {
        if (orderId == null || paymentLinkId == null || !STANDALONE_PAYMENT_REVERSAL_STATUSES.contains(terminalStatus)) {
            return false;
        }
        orderAggregateMutationLockService.lock(orderId);
        PaymentLink sourceLink = paymentLinkRepository.findByOrderIdForUpdate(orderId).stream().filter(link -> paymentLinkId.equals(link.getId())).findFirst().orElse(null);
        String durableProviderStatus = sourceLink == null ? "" : normalize(sourceLink.getProviderTerminalStatus()).toUpperCase(Locale.ROOT);
        if (sourceLink == null || sourceLink.getStatus() != terminalStatus || !terminalStatus.name().equals(durableProviderStatus)) {
            return false;
        }
        CommonInvoiceOrder item = invoiceOrderRepository.findByOrderIdWithInvoice(orderId).orElse(null);
        if (item == null || item.getInvoice() == null || item.getInvoice().getId() == null) {
            return false;
        }
        CommonInvoice snapshot = item.getInvoice();
        if (!paymentLinkId.equals(item.getSourcePaymentLinkId())) {
            return false;
        }
        CommonInvoice invoice = lockedInvoiceAfterOrderPrelude(snapshot.getId()).orElse(snapshot);
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setNextReminderAt(null);
        invoice.setLastError(limit("standalone_payment_reversed: order=" + orderId + ";link=" + paymentLinkId + ";status=" + terminalStatus.name() + ";provider=" + durableProviderStatus, 512));
        invoiceRepository.save(invoice);
        return true;
    }

    boolean isPaperModeSwitchSafeTerminalStatus(String status) {
        String normalizedStatus = normalize(status).toUpperCase(Locale.ROOT);
        return PAYMENT_REF_CANCELED.equals(normalizedStatus) || "REJECTED".equals(normalizedStatus) || "EXPIRED".equals(normalizedStatus) || "DEADLINE_EXPIRED".equals(normalizedStatus);
    }

    boolean isPaperModeSwitchFinancialStatus(String status) {
        String normalizedStatus = normalize(status).toUpperCase(Locale.ROOT);
        return PAYMENT_REF_CONFIRMED.equals(normalizedStatus) || "AUTHORIZED".equals(normalizedStatus) || PAYMENT_REF_PREPAID.equals(normalizedStatus) || PAYMENT_REF_APPLYING.equals(normalizedStatus) || PAYMENT_REF_APPLIED.equals(normalizedStatus) || "REFUNDED".equals(normalizedStatus) || "PARTIAL_REFUNDED".equals(normalizedStatus) || "REVERSED".equals(normalizedStatus) || "PARTIAL_REVERSED".equals(normalizedStatus);
    }

    String readableProviderStatus(String status) {
        String normalizedStatus = normalize(status).toUpperCase(Locale.ROOT);
        return normalizedStatus.isBlank() ? "UNKNOWN" : normalizedStatus;
    }

    CommonInvoiceStatus controlledManualPaymentOriginalStatus(CommonInvoice invoice) {
        if (invoice == null || invoice.getStatus() != CommonInvoiceStatus.NEEDS_ATTENTION) {
            return null;
        }
        String error = normalize(invoice.getLastError());
        for (String prefix : List.of(MANUAL_PAYMENT_TBANK_RECONCILIATION_IN_PROGRESS, MANUAL_PAYMENT_TBANK_RECONCILIATION_RETRY, MANUAL_PAYMENT_TBANK_PAYMENT_DETECTED)) {
            if (!error.startsWith(prefix)) {
                continue;
            }
            String value = error.substring(prefix.length());
            int delimiter = value.indexOf(':');
            if (delimiter >= 0) {
                value = value.substring(0, delimiter);
            }
            try {
                CommonInvoiceStatus original = CommonInvoiceStatus.valueOf(value);
                if (MARK_PAID_STATUSES.contains(original)) {
                    return original;
                }
            } catch (IllegalArgumentException ignored) {
                // Fall through to the fail-closed error below.
            }
        }
        return null;
    }

    void quarantineTochkaInit(CommonInvoice invoice, CommonInvoicePaymentRef ref, String reason) {
        if (ref != null) {
            boolean cancellationLifecycle = isTochkaCancellationLifecycleStatus(paymentRefStatus(ref)) || isTochkaRefundSubmissionClaimed(ref.getReason());
            ref.setStatus(cancellationLifecycle ? PAYMENT_REF_CANCEL_FAILED_FINAL : PAYMENT_REF_INIT_CONFLICT);
            setTochkaReasonUnlessRefundClaimed(ref, reason);
            paymentRefRepository.save(ref);
        }
        if (invoice != null) {
            invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
            invoice.setNextReminderAt(null);
            invoice.setPaymentUrl(null);
            boolean cancellationLifecycle = ref != null && isTochkaCancellationLifecycleStatus(paymentRefStatus(ref));
            invoice.setLastError(limit((cancellationLifecycle ? PAYMENT_CANCEL_FAILED_FINAL + ": " : "") + reason + ": платёж Точки требует ручной сверки", 512));
            invoiceRepository.save(invoice);
        }
    }

    List<TochkaPaymentMode> parseTochkaPaymentModes(String value) {
        List<TochkaPaymentMode> modes = java.util.Arrays.stream(normalize(value).split(",")).map(String::trim).filter(mode -> !mode.isBlank()).map(TochkaPaymentMode::fromCode).distinct().toList();
        if (modes.isEmpty() || modes.stream().anyMatch(mode -> mode != TochkaPaymentMode.SBP && mode != TochkaPaymentMode.CARD)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "В durable-записи Точки отсутствуют разрешённые способы оплаты");
        }
        return modes;
    }

    TochkaPaymentMode observedTochkaPaymentMode(String paymentType, String providerStatus, List<TochkaPaymentMode> allowedModes) {
        List<TochkaPaymentMode> allowed = allowedModes == null ? List.of() : allowedModes;
        String cleanType = normalize(paymentType).toLowerCase(Locale.ROOT);
        if (cleanType.isBlank() && Set.of("CREATED", "EXPIRED").contains(normalize(providerStatus).toUpperCase(Locale.ROOT))) {
            if (allowed.isEmpty()) {
                throw new TochkaProviderException("В платеже Точки не зафиксирован разрешённый способ оплаты", false, null);
            }
            return allowed.getFirst();
        }
        TochkaPaymentMode observed = TochkaPaymentMode.fromCode(cleanType);
        if (!allowed.contains(observed)) {
            throw new TochkaProviderException("Точка сообщила способ оплаты, не разрешённый durable-записью", false, null);
        }
        return observed;
    }

    public boolean handleTbankWebhook(Map<String, String> payload) {
        try {
            return writeTransaction(() -> handleTbankWebhookInTransaction(payload));
        } catch (RuntimeException failure) {
            if (!isDurablePaymentRegistryConstraintViolation(failure)) {
                throw failure;
            }
            writeTransaction(() -> {
                quarantineWebhookIdentityConstraint(payload, failure);
                return null;
            });
            // Acknowledge a verified bank webhook after durable quarantine;
            // returning an error would cause endless provider retries.
            return true;
        }
    }

    /**
     * Applies an already signature-verified Tochka webhook to a common-invoice attempt.
     */
    public boolean handleTochkaWebhook(TochkaAcquiringInternetPaymentWebhook claims) {
        if (claims == null) {
            return false;
        }
        String paymentLinkId = normalize(claims.paymentLinkId());
        String operationId = normalize(claims.operationId());
        if (paymentLinkId.isBlank() && operationId.isBlank()) {
            return false;
        }
        return writeTransaction(() -> handleTochkaWebhookInTransaction(claims, paymentLinkId, operationId));
    }

    boolean handleTochkaWebhookInTransaction(TochkaAcquiringInternetPaymentWebhook claims, String paymentLinkId, String operationId) {
        Optional<CommonInvoicePaymentRef> candidate = paymentLinkId.isBlank() ? Optional.empty() : paymentRefRepository.findByProviderAndProviderOrderId(PROVIDER_TOCHKA, paymentLinkId);
        if (candidate.isEmpty() && !operationId.isBlank()) {
            candidate = paymentRefRepository.findByProviderAndProviderPaymentId(PROVIDER_TOCHKA, operationId);
        }
        if (candidate.isEmpty()) {
            return false;
        }
        Long invoiceId = paymentRefInvoiceId(candidate.get());
        CommonInvoice invoice = lockedInvoice(invoiceId).orElseThrow(() -> new TochkaWebhookVerificationException("Tochka common-invoice binding disappeared"));
        CommonInvoicePaymentRef ref = paymentRefRepository.findByIdForUpdate(candidate.get().getId()).orElseThrow(() -> new TochkaWebhookVerificationException("Tochka common-invoice payment ref disappeared"));
        if (!Objects.equals(invoiceId, paymentRefInvoiceId(ref)) || !PROVIDER_TOCHKA.equals(normalizedPaymentProvider(ref)) || (!paymentLinkId.isBlank() && !paymentLinkId.equals(normalize(ref.getProviderOrderId()))) || (!operationId.isBlank() && !normalize(ref.getProviderPaymentId()).isBlank() && !operationId.equals(normalize(ref.getProviderPaymentId())))) {
            throw new TochkaWebhookVerificationException("Tochka common-invoice webhook identity does not match the durable ref");
        }
        Optional<CommonInvoicePaymentRef> foreignOperation = operationId.isBlank() ? Optional.empty() : paymentRefRepository.findByProviderAndProviderPaymentId(PROVIDER_TOCHKA, operationId).filter(other -> !Objects.equals(other.getId(), ref.getId()));
        if (foreignOperation.isPresent()) {
            quarantineTochkaInit(invoice, ref, "tochka_webhook_operation_collision");
            return true;
        }
        PaymentProfile entityProfile;
        TochkaPaymentProfile runtimeProfile;
        List<TochkaPaymentMode> paymentModes;
        try {
            entityProfile = paymentProfileService.lockByIdForRouting(ref.getPaymentProfileId());
            runtimeProfile = tochkaPaymentProfileResolver.resolveForExistingPayment(entityProfile);
            paymentModes = parseTochkaPaymentModes(ref.getProviderPaymentMode());
        } catch (RuntimeException failure) {
            throw new TochkaWebhookVerificationException("Pinned Tochka common-invoice profile cannot be resolved", failure);
        }
        if (!paymentProfileService.isTochkaProvider(entityProfile) || !Objects.equals(runtimeProfile.id(), ref.getPaymentProfileId()) || !runtimeProfile.merchantId().equals(normalize(ref.getProviderMerchantId())) || !Objects.equals(runtimeProfile.testMode(), ref.getProviderTestMode()) || ref.getAmountKopecks() == null || ref.getAmountKopecks() <= 0 || operationId.isBlank() || paymentLinkId.isBlank()) {
            throw new TochkaWebhookVerificationException("Pinned Tochka common-invoice identity is incomplete or changed");
        }
        MappedPayment mapped;
        try {
            TochkaPaymentMode observedMode = observedTochkaPaymentMode(claims.paymentType(), claims.status(), paymentModes);
            mapped = tochkaPaymentOperationMapper.map(tochkaWebhookOperation(claims), new ExpectedPayment(operationId, paymentLinkId, runtimeProfile.customerCode(), runtimeProfile.merchantId(), ref.getAmountKopecks(), observedMode), false);
        } catch (RuntimeException failure) {
            throw new TochkaWebhookVerificationException("Tochka common-invoice webhook payload does not match its durable ref", failure);
        }
        ref.setProviderPaymentId(limit(operationId, 64));
        ref.setProviderStatus(limit(mapped.providerStatus(), 32));
        paymentRefRepository.save(ref);
        entityManager.flush();
        applyTochkaPaymentObservation(invoice, ref, mapped, null, "webhook");
        return true;
    }

    PaymentOperation tochkaWebhookOperation(TochkaAcquiringInternetPaymentWebhook claims) {
        return new PaymentOperation(claims.customerCode(), null, claims.paymentType(), null, claims.transactionId(), null, claims.amount(), claims.status(), claims.operationId(), null, claims.merchantId(), null, claims.paymentLinkId(), List.of());
    }

    void applyTochkaPaymentObservation(CommonInvoice invoice, CommonInvoicePaymentRef ref, MappedPayment mapped, String observedPaymentUrl, String source) {
        if (invoice == null || ref == null || mapped == null) {
            return;
        }
        String previousStatus = paymentRefStatus(ref);
        boolean cancellationLifecycle = isTochkaCancellationLifecycleStatus(previousStatus) || isTochkaRefundSubmissionClaimed(ref.getReason());
        ref.setProviderStatus(limit(mapped.providerStatus(), 32));
        String safeUrl = PaymentUrlPolicy.safe(observedPaymentUrl, PaymentUrlPolicy.Purpose.TOCHKA_PAYMENT);
        if (!safeUrl.isBlank()) {
            ref.setProviderPaymentUrl(safeUrl);
        }
        switch(mapped.status()) {
            case INITIATED ->
                {
                    if (Set.of(PAYMENT_REF_CONFIRMED, PAYMENT_REF_PREPAID, PAYMENT_REF_APPLYING, PAYMENT_REF_APPLIED, "REFUNDED", "PARTIAL_REFUNDED").contains(previousStatus)) {
                        return;
                    }
                    if (cancellationLifecycle) {
                        setTochkaReasonUnlessRefundClaimed(ref, "tochka_cancel_waiting_expiry:" + source);
                        paymentRefRepository.save(ref);
                        return;
                    }
                    ref.setStatus(PAYMENT_REF_CURRENT);
                    ref.setReason(limit("tochka_payment_created:" + source, 160));
                    paymentRefRepository.save(ref);
                    String persistedUrl = PaymentUrlPolicy.safe(ref.getProviderPaymentUrl(), PaymentUrlPolicy.Purpose.TOCHKA_PAYMENT);
                    if (!persistedUrl.isBlank()) {
                        invoice.setPaymentUrl(persistedUrl);
                    }
                    invoice.setLastError(null);
                    invoiceRepository.save(invoice);
                }
            case CONFIRMED ->
                applyConfirmedTochkaPayment(invoice, ref, previousStatus, source);
            case EXPIRED ->
                {
                    ref.setStatus("EXPIRED");
                    ref.setReason(limit("tochka_payment_expired:" + source, 160));
                    paymentRefRepository.save(ref);
                    invoice.setPaymentUrl(null);
                    if (PAYMENT_INIT_IN_PROGRESS.equals(normalize(invoice.getLastError())) || normalize(invoice.getLastError()).startsWith("tochka_")) {
                        invoice.setLastError(null);
                    }
                    invoiceRepository.save(invoice);
                }
            case REFUNDED ->
                {
                    ref.setStatus("REFUNDED");
                    ref.setReason(limit("tochka_payment_refunded:" + source, 160));
                    paymentRefRepository.save(ref);
                    invoice.setPaymentUrl(null);
                    if (invoice.getStatus() == CommonInvoiceStatus.PAID || PAYMENT_REF_APPLIED.equals(previousStatus) || PAYMENT_REF_PREPAID.equals(previousStatus)) {
                        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
                        invoice.setNextReminderAt(null);
                        invoice.setLastError(limit("tochka_payment_refunded: оплаченный общий счёт получил возврат; нужна сверка", 512));
                    }
                    invoiceRepository.save(invoice);
                }
            case PARTIAL_REFUNDED ->
                {
                    ref.setStatus("PARTIAL_REFUNDED");
                    ref.setReason(limit("tochka_payment_partially_refunded:" + source, 160));
                    paymentRefRepository.save(ref);
                    invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
                    invoice.setNextReminderAt(null);
                    invoice.setPaymentUrl(null);
                    invoice.setLastError(limit("tochka_payment_partially_refunded: частичный возврат требует ручной сверки", 512));
                    invoiceRepository.save(invoice);
                }
            case AUTHORIZED, NEEDS_RECONCILIATION ->
                {
                    ref.setStatus(cancellationLifecycle ? PAYMENT_REF_CANCEL_FAILED_FINAL : PAYMENT_REF_INIT_CONFLICT);
                    setTochkaReasonUnlessRefundClaimed(ref, "tochka_payment_ambiguous:" + mapped.providerStatus() + ":" + source);
                    paymentRefRepository.save(ref);
                    invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
                    invoice.setNextReminderAt(null);
                    invoice.setPaymentUrl(null);
                    invoice.setLastError(limit((cancellationLifecycle ? PAYMENT_CANCEL_FAILED_FINAL + ": " : "") + "tochka_payment_ambiguous: статус " + mapped.providerStatus() + " требует ручной сверки", 512));
                    invoiceRepository.save(invoice);
                }
            default ->
                {
                    ref.setStatus(cancellationLifecycle ? PAYMENT_REF_CANCEL_FAILED_FINAL : PAYMENT_REF_INIT_CONFLICT);
                    setTochkaReasonUnlessRefundClaimed(ref, "tochka_payment_unhandled:" + mapped.providerStatus());
                    paymentRefRepository.save(ref);
                    invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
                    invoice.setPaymentUrl(null);
                    invoice.setLastError((cancellationLifecycle ? PAYMENT_CANCEL_FAILED_FINAL + ": " : "") + "tochka_payment_unhandled: нужна ручная сверка");
                    invoiceRepository.save(invoice);
                }
        }
    }

    void applyConfirmedTochkaPayment(CommonInvoice invoice, CommonInvoicePaymentRef ref, String previousStatus, String source) {
        if (PAYMENT_REF_APPLIED.equals(previousStatus) || PAYMENT_REF_PREPAID.equals(previousStatus)) {
            return;
        }
        if (Set.of(PAYMENT_REF_CANCEL_PENDING, PAYMENT_REF_CANCELING, PAYMENT_REF_CANCEL_FAILED, PAYMENT_REF_CANCEL_FAILED_FINAL).contains(previousStatus) || isTochkaRefundSubmissionClaimed(ref.getReason())) {
            // Preserve the cancellation lifecycle so reconciliation can claim exactly one Refund.
            // Financial confirmation remains frozen in providerStatus/reason and is never applied
            // to a route that was already being replaced.
            if (!PAYMENT_REF_CANCEL_FAILED_FINAL.equals(previousStatus)) {
                ref.setStatus(PAYMENT_REF_CANCEL_PENDING);
            }
            setTochkaReasonUnlessRefundClaimed(ref, "late_tochka_payment_after_cancel_request:" + source);
            paymentRefRepository.save(ref);
            invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
            invoice.setNextReminderAt(null);
            invoice.setPaymentUrl(null);
            invoice.setLastError(limit("late_tochka_payment: ссылка Точки оплачена после запроса смены маршрута; " + "нужна сверка и возврат", 512));
            invoiceRepository.save(invoice);
            return;
        }
        if (invoice.getStatus() == CommonInvoiceStatus.PAID) {
            ref.setStatus(PAYMENT_REF_APPLIED);
            ref.setReason(limit("tochka_confirmed_after_paid:" + source, 160));
            paymentRefRepository.save(ref);
            return;
        }
        if (invoice.getStatus() == CommonInvoiceStatus.UNPAID || invoice.getStatus() == CommonInvoiceStatus.BAN || invoice.getStatus() == CommonInvoiceStatus.DISABLED || invoice.getStatus() == CommonInvoiceStatus.ARCHIVED) {
            ref.setStatus(PAYMENT_REF_CONFIRMED);
            ref.setReason(limit("late_tochka_payment_after_terminal_invoice:" + source, 160));
            paymentRefRepository.save(ref);
            invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
            invoice.setNextReminderAt(null);
            invoice.setPaymentUrl(null);
            invoice.setLastError(limit("late_payment_tochka: оплачена ссылка после закрытия общего счёта; нужна сверка", 512));
            invoiceRepository.save(invoice);
            return;
        }
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoice.getId());
        refreshInvoiceAmounts(invoice, items);
        long confirmedAmount = ref.getAmountKopecks() == null ? 0 : ref.getAmountKopecks();
        if (confirmedAmount <= 0 || remainingKopecks(invoice) != confirmedAmount) {
            ref.setStatus(PAYMENT_REF_CONFIRMED);
            ref.setReason(limit("tochka_confirmed_amount_mismatch:" + source, 160));
            paymentRefRepository.save(ref);
            invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
            invoice.setNextReminderAt(null);
            invoice.setPaymentUrl(null);
            invoice.setLastError(limit("payment_amount_changed_after_confirmation: Точка подтвердила " + amountRubles(confirmedAmount) + " руб., остаток счёта " + amountRubles(remainingKopecks(invoice)) + " руб.; нужна сверка", 512));
            invoiceRepository.save(invoice);
            return;
        }
        mergeInvoicePaymentMethod(invoice, PAYMENT_METHOD_TOCHKA);
        invoice.setPaymentUrl(null);
        if (!allOrdersReady(items)) {
            ref.setStatus(PAYMENT_REF_PREPAID);
            ref.setReason(PREPAID_WAITING_COMMON_INVOICE_READY);
            paymentRefRepository.save(ref);
            long prepaid = confirmedCommonInvoicePrepaymentKopecks(invoice);
            invoice.setPaidKopecks(Math.min(invoice.getAmountKopecks(), prepaid));
            invoice.setStatus(CommonInvoiceStatus.COLLECTING);
            invoice.setNextReminderAt(null);
            invoice.setLastError(null);
            invoiceRepository.save(invoice);
            return;
        }
        ref.setStatus(PAYMENT_REF_CONFIRMED);
        ref.setReason(limit("tochka_payment_confirmed:" + source, 160));
        paymentRefRepository.save(ref);
        closePaidInvoice(invoice, items);
        if (invoice.getStatus() == CommonInvoiceStatus.PAID) {
            ref.setStatus(PAYMENT_REF_APPLIED);
            ref.setReason(limit("tochka_payment_applied:" + source, 160));
            paymentRefRepository.save(ref);
        }
    }

    boolean handleTbankWebhookInTransaction(Map<String, String> payload) {
        VerifiedWebhookProfile verified = verifyWebhook(payload);
        String orderId = normalize(payload.get("OrderId"));
        String paymentId = normalize(payload.get("PaymentId"));
        String status = normalize(payload.get("Status")).toUpperCase(Locale.ROOT);
        boolean success = "true".equalsIgnoreCase(normalize(payload.get("Success")));
        String errorCode = normalize(payload.get("ErrorCode"));
        if (isTerminalPaymentWebhook(status, success, errorCode) && paymentId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PaymentId обязателен для конечного статуса webhook");
        }
        List<Long> invoiceIdsByOrderId = orderId.isBlank() ? List.of() : invoiceRepository.findIdsByTbankOrderId(orderId);
        if (new HashSet<>(invoiceIdsByOrderId).size() > 1) {
            quarantineDuplicateProviderIdentityInvoices("OrderId", orderId, invoiceIdsByOrderId);
            return true;
        }
        List<Long> invoiceIdsByPaymentId = paymentId.isBlank() ? List.of() : invoiceRepository.findIdsByTbankPaymentId(paymentId);
        if (new HashSet<>(invoiceIdsByPaymentId).size() > 1) {
            quarantineDuplicateProviderIdentityInvoices("PaymentId", paymentId, invoiceIdsByPaymentId);
            return true;
        }
        Optional<CommonInvoicePaymentRef> providerRefCandidate = findProviderPaymentRefCandidate(orderId, paymentId);
        Long candidateInvoiceId = providerRefCandidate.map(this::paymentRefInvoiceId).orElse(null);
        if (candidateInvoiceId == null && !orderId.isBlank()) {
            candidateInvoiceId = invoiceIdsByOrderId.stream().findFirst().orElse(null);
        }
        if (candidateInvoiceId == null && !paymentId.isBlank()) {
            candidateInvoiceId = invoiceIdsByPaymentId.stream().findFirst().orElse(null);
        }
        if (candidateInvoiceId == null) {
            return handleArchivedPaymentWebhook(payload, orderId, paymentId, verified.runtimeProfile());
        }
        CommonInvoice invoice = lockedInvoice(candidateInvoiceId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        if (!matchesCurrentPaymentRef(invoice, orderId, paymentId)) {
            return handleArchivedPaymentWebhook(payload, orderId, paymentId, verified.runtimeProfile());
        }
        validateWebhookTerminal(invoice, verified.runtimeProfile());
        validateWebhookAmount(invoice, payload);
        CommonInvoicePaymentRef currentAnchor = providerRefCandidate.flatMap(this::lockedPaymentRef).or(() -> lockedPaymentRefByProviderBinding(orderId, paymentId)).filter(ref -> Objects.equals(invoice.getId(), paymentRefInvoiceId(ref))).orElse(null);
        if (currentAnchor == null) {
            quarantineMissingCurrentPaymentAnchor(invoice, orderId, paymentId);
            return true;
        }
        if (currentAnchor != null && !normalize(currentAnchor.getTbankPaymentId()).isBlank() && !paymentId.isBlank() && !paymentId.equals(normalize(currentAnchor.getTbankPaymentId()))) {
            quarantineWebhookPaymentIdCollision(invoice, currentAnchor, paymentId);
            return true;
        }
        if (hasForeignPaymentIdBinding(paymentId, invoice.getId(), currentAnchor == null ? null : currentAnchor.getId())) {
            quarantineWebhookPaymentIdCollision(invoice, currentAnchor, paymentId);
            return true;
        }
        invoice.setTbankPaymentId(paymentId.isBlank() ? invoice.getTbankPaymentId() : paymentId);
        invoice.setTbankTerminalKey(verified.runtimeProfile().terminalKey());
        if ("CONFIRMED".equals(status)) {
            updateCurrentPaymentAnchorFromWebhook(currentAnchor, paymentId, verified.runtimeProfile().terminalKey(), payload, PAYMENT_REF_CONFIRMED, "current_payment_confirmed");
            if (invoice.getStatus() == CommonInvoiceStatus.PAID) {
                recordCurrentPaymentRef(invoice, PAYMENT_REF_APPLIED, "confirmed_after_paid");
                invoiceRepository.save(invoice);
                return true;
            }
            if (invoice.getStatus() == CommonInvoiceStatus.UNPAID || invoice.getStatus() == CommonInvoiceStatus.BAN || invoice.getStatus() == CommonInvoiceStatus.DISABLED) {
                recordCurrentPaymentRef(invoice, PAYMENT_REF_CONFIRMED, "confirmed_after_terminal_status");
                invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
                invoice.setNextReminderAt(null);
                invoice.setLastError(limit("late_tbank_payment: оплачена ссылка после закрытия общего счета; требуется ручная сверка", 512));
                invoiceRepository.save(invoice);
                return true;
            }
            List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoice.getId());
            mergeInvoicePaymentMethod(invoice, PAYMENT_METHOD_TBANK);
            refreshInvoiceAmounts(invoice, items);
            long confirmedAmount = currentAnchor != null && currentAnchor.getAmountKopecks() != null ? currentAnchor.getAmountKopecks() : parseWebhookAmount(payload);
            if (confirmedAmount > 0 && remainingKopecks(invoice) != confirmedAmount) {
                clearCurrentPaymentRef(invoice);
                invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
                invoice.setNextReminderAt(null);
                invoice.setLastError(limit("payment_amount_changed_after_confirmation: T-Bank подтвердил " + amountRubles(confirmedAmount) + " руб., но текущий остаток счета равен " + amountRubles(remainingKopecks(invoice)) + " руб.; нужна ручная сверка", 512));
                invoiceRepository.save(invoice);
                return true;
            }
            if (!allOrdersReady(items)) {
                recordCommonInvoicePrepayment(invoice, items);
                return true;
            }
            recordCurrentPaymentRef(invoice, PAYMENT_REF_CONFIRMED, "current_payment_confirmed");
            closePaidInvoice(invoice, items);
            if (invoice.getStatus() == CommonInvoiceStatus.PAID) {
                markConfirmedPaymentRefsApplied(invoice.getId());
            }
        } else if (isTerminalPaymentWebhook(status, success, errorCode)) {
            String terminalStatus = durableTerminalWebhookStatus(status, success, errorCode);
            CommonInvoiceStatus manualReconciliationOriginal = controlledManualPaymentOriginalStatus(invoice);
            updateCurrentPaymentAnchorFromWebhook(currentAnchor, paymentId, verified.runtimeProfile().terminalKey(), payload, terminalStatus, "current_payment_terminal");
            recordCurrentPaymentRef(invoice, terminalStatus, "current_payment_terminal");
            if (manualReconciliationOriginal != null && isPaperModeSwitchSafeTerminalStatus(terminalStatus)) {
                // Cancel may notify asynchronously while the manual-payment request is
                // waiting for the synchronous T-Bank response. Keep the parseable
                // reconciliation marker: the finishing transaction will accept the
                // same terminal ref and only then credit the actual recipient.
                invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
                invoice.setNextReminderAt(null);
            } else if (manualReconciliationOriginal != null && isPaperModeSwitchFinancialStatus(terminalStatus)) {
                invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
                invoice.setNextReminderAt(null);
                invoice.setLastError(limit(MANUAL_PAYMENT_TBANK_PAYMENT_DETECTED + manualReconciliationOriginal.name() + ": T-Bank=" + readableProviderStatus(terminalStatus), 512));
            } else if (PAYMENT_REF_REFUNDED_STATUSES.contains(terminalStatus) && invoice.getStatus() == CommonInvoiceStatus.PAID) {
                invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
                invoice.setNextReminderAt(null);
                invoice.setLastError(limit("tbank_payment_refunded: платеж получил статус " + terminalStatus + "; проверьте банк и оплату вручную", 512));
            } else {
                invoice.setLastError(limit("tbank_payment_terminal: " + (errorCode.isBlank() ? terminalStatus : errorCode), 512));
            }
            invoiceRepository.save(invoice);
        } else {
            updateCurrentPaymentAnchorFromWebhook(currentAnchor, paymentId, verified.runtimeProfile().terminalKey(), payload, PAYMENT_REF_CURRENT, "current_payment_pending:" + (status.isBlank() ? "UNKNOWN" : status));
            invoiceRepository.save(invoice);
        }
        return true;
    }

    boolean handleArchivedPaymentWebhook(Map<String, String> payload, String orderId, String paymentId, TbankPaymentProfile runtimeProfile) {
        Optional<CommonInvoicePaymentRef> ref = !orderId.isBlank() ? paymentRefRepository.findByTbankOrderId(orderId) : Optional.empty();
        if (ref.isEmpty() && !paymentId.isBlank()) {
            ref = paymentRefRepository.findByTbankPaymentId(paymentId);
        }
        if (ref.isEmpty()) {
            return false;
        }
        CommonInvoicePaymentRef paymentRef = ref.get();
        CommonInvoice invoice = lockedInvoice(paymentRef.getInvoice().getId()).orElse(paymentRef.getInvoice());
        paymentRef = lockedPaymentRef(paymentRef).orElse(paymentRef);
        validateArchivedWebhookTerminal(paymentRef, runtimeProfile);
        validateArchivedWebhookAmount(paymentRef, payload);
        boolean allowedProviderOrderMismatch = allowsArchivedProviderOrderMismatch(paymentRef, orderId, paymentId);
        if ((!orderId.isBlank() && !normalize(paymentRef.getTbankOrderId()).isBlank() && !orderId.equals(normalize(paymentRef.getTbankOrderId())) && !allowedProviderOrderMismatch) || (!paymentId.isBlank() && !normalize(paymentRef.getTbankPaymentId()).isBlank() && !paymentId.equals(normalize(paymentRef.getTbankPaymentId())))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Реквизиты webhook не совпадают с архивной ссылкой");
        }
        if (hasForeignPaymentIdBinding(paymentId, invoice.getId(), paymentRef.getId())) {
            quarantineWebhookPaymentIdCollision(invoice, paymentRef, paymentId);
            return true;
        }
        if (!orderId.isBlank() && orderId.equals(normalize(invoice.getTbankOrderId())) && !normalize(invoice.getTbankPaymentId()).isBlank() && !paymentId.isBlank() && !paymentId.equals(normalize(invoice.getTbankPaymentId()))) {
            quarantineWebhookPaymentIdCollision(invoice, paymentRef, paymentId);
            return true;
        }
        String previousPaymentId = normalize(paymentRef.getTbankPaymentId());
        String previousTerminalKey = normalize(paymentRef.getTbankTerminalKey());
        Long previousAmount = paymentRef.getAmountKopecks();
        enrichPaymentRefAmountFromWebhook(paymentRef, payload);
        if (!paymentId.isBlank()) {
            paymentRef.setTbankPaymentId(limit(paymentId, 64));
        }
        if (normalize(paymentRef.getTbankTerminalKey()).isBlank()) {
            paymentRef.setTbankTerminalKey(limit(runtimeProfile.terminalKey(), 64));
        }
        boolean providerEvidenceChanged = !previousPaymentId.equals(normalize(paymentRef.getTbankPaymentId())) || !previousTerminalKey.equals(normalize(paymentRef.getTbankTerminalKey())) || !Objects.equals(previousAmount, paymentRef.getAmountKopecks());
        if (providerEvidenceChanged) {
            paymentRefRepository.save(paymentRef);
            entityManager.flush();
        }
        String status = normalize(payload.get("Status")).toUpperCase(Locale.ROOT);
        boolean success = "true".equalsIgnoreCase(normalize(payload.get("Success")));
        String errorCode = normalize(payload.get("ErrorCode"));
        String originalStatus = normalize(paymentRef.getStatus()).toUpperCase(Locale.ROOT);
        if (isIdempotentArchivedWebhook(paymentRef, status, success, errorCode)) {
            log.info("Повторный webhook старой ссылки общего счета {} уже обработан: {} ({})", invoice.getId(), paymentRefLabel(paymentRef), status);
            return true;
        }
        boolean terminalWebhook = isTerminalPaymentWebhook(status, success, errorCode);
        if (!terminalWebhook && (PAYMENT_REF_CANCEL_PENDING.equals(originalStatus) || PAYMENT_REF_CANCELING.equals(originalStatus) || PAYMENT_REF_CANCEL_FAILED.equals(originalStatus) || PAYMENT_REF_CANCEL_FAILED_FINAL.equals(originalStatus))) {
            paymentRef.setReason(paymentRefReasonPreservingProviderOrderMismatch(paymentRef, "cancel_lifecycle_webhook:" + (status.isBlank() ? "UNKNOWN" : status)));
            paymentRefRepository.save(paymentRef);
            return true;
        }
        if (!terminalWebhook && PAYMENT_REF_NONTERMINAL_DOWNGRADE_PROTECTED_STATUSES.contains(originalStatus)) {
            log.info("Устаревший не-конечный webhook {} не понижает durable-статус ссылки {} общего счета {}", status.isBlank() ? "UNKNOWN" : status, originalStatus, invoice.getId());
            return true;
        }
        if (!terminalWebhook) {
            boolean cancellable = canCancelInitializedPaymentRef(paymentRef);
            paymentRef.setStatus(cancellable ? PAYMENT_REF_CANCEL_PENDING : PAYMENT_REF_INIT_CONFLICT);
            paymentRef.setReason(paymentRefReasonPreservingProviderOrderMismatch(paymentRef, (cancellable ? "init_webhook_cancel_pending:" : "init_webhook_incomplete:") + (status.isBlank() ? "UNKNOWN" : status)));
            paymentRefRepository.save(paymentRef);
            invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
            invoice.setNextReminderAt(null);
            invoice.setPaymentUrl(null);
            invoice.setLastError(limit("payment_init_conflict: T-Bank сообщил статус " + (status.isBlank() ? "UNKNOWN" : status) + " до завершения Init; нужна ручная сверка", 512));
            invoiceRepository.save(invoice);
            return true;
        }
        String terminalStatus = durableTerminalWebhookStatus(status, success, errorCode);
        paymentRef.setStatus(terminalStatus);
        paymentRefRepository.save(paymentRef);
        if (PAYMENT_REF_CONFIRMED.equals(terminalStatus)) {
            invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
            invoice.setNextReminderAt(null);
            invoice.setLastError(limit("late_tbank_payment: оплачена старая ссылка " + paymentRefLabel(paymentRef) + ", сумма " + amountRubles(paymentRef.getAmountKopecks() == null ? 0 : paymentRef.getAmountKopecks()) + " руб.; требуется ручная сверка", 512));
            invoiceRepository.save(invoice);
        } else if (PAYMENT_REF_REFUNDED_STATUSES.contains(terminalStatus) && invoice.getStatus() == CommonInvoiceStatus.PAID) {
            invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
            invoice.setNextReminderAt(null);
            invoice.setLastError(limit("tbank_payment_refunded: оплаченный общий счет получил статус " + terminalStatus + " по T-Bank ссылке " + paymentRefLabel(paymentRef) + "; проверьте банк и оплату вручную", 512));
            invoiceRepository.save(invoice);
        } else if ("REJECTED".equals(terminalStatus)) {
            invoice.setLastError(limit("archived_payment_" + (errorCode.isBlank() ? terminalStatus : errorCode), 512));
            invoiceRepository.save(invoice);
        }
        return true;
    }

    boolean allowsArchivedProviderOrderMismatch(CommonInvoicePaymentRef paymentRef, String webhookOrderId, String webhookPaymentId) {
        if (paymentRef == null || normalize(webhookOrderId).isBlank() || normalize(webhookPaymentId).isBlank() || !normalize(webhookPaymentId).equals(normalize(paymentRef.getTbankPaymentId()))) {
            return false;
        }
        String reason = normalize(paymentRef.getReason()).toLowerCase(Locale.ROOT);
        return reason.contains("order_id_mismatch") || reason.contains("provider_order_mismatch");
    }

    String paymentRefReasonPreservingProviderOrderMismatch(CommonInvoicePaymentRef paymentRef, String nextReason) {
        String existing = normalize(paymentRef == null ? null : paymentRef.getReason());
        String normalizedExisting = existing.toLowerCase(Locale.ROOT);
        if (normalizedExisting.contains("order_id_mismatch") || normalizedExisting.contains("provider_order_mismatch")) {
            return limit(existing + ";" + normalize(nextReason), 160);
        }
        return limit(nextReason, 160);
    }

    Optional<CommonInvoice> lockedInvoice(Long invoiceId) {
        Set<Long> lockedOrderIds = lockInvoiceOrderAggregates(invoiceId);
        Optional<CommonInvoice> invoice = lockedInvoiceAfterOrderPrelude(invoiceId);
        invoice.ifPresent(ignored -> ensureInvoiceMembershipUnchanged(invoiceId, lockedOrderIds));
        return invoice;
    }

    Optional<CommonInvoice> lockedInvoiceAfterOrderPrelude(Long invoiceId) {
        Optional<CommonInvoice> snapshot = invoiceRepository.findByIdWithAccount(invoiceId);
        Long expectedAccountId = snapshot.map(CommonInvoice::getAccount).map(CommonBillingAccount::getId).orElse(null);
        lockFreshAccountAfterOrderPrelude(expectedAccountId);
        Optional<CommonInvoice> locked = invoiceRepository.findByIdWithAccountForUpdate(invoiceId).or(() -> snapshot);
        locked.ifPresent(invoice -> {
            entityManager.refresh(invoice);
            ensureInvoiceAccountUnchanged(invoice, expectedAccountId);
        });
        return locked;
    }

    CommonBillingAccount lockFreshAccountAfterOrderPrelude(Long accountId) {
        if (accountId == null) {
            return null;
        }
        CommonBillingAccount account = accountRepository.findByIdWithRelationsForUpdate(accountId).orElseThrow(() -> invoiceMembershipChanged("плательщик общего счета исчез во время операции"));
        // SELECT ... FOR UPDATE may return an already-managed pre-lock snapshot.
        // Refresh while the row lock is held before any caller mutates it.
        entityManager.refresh(account);
        return account;
    }

    void ensureInvoiceAccountUnchanged(CommonInvoice invoice, Long expectedAccountId) {
        Long currentAccountId = invoice == null || invoice.getAccount() == null ? null : invoice.getAccount().getId();
        if (expectedAccountId != null && !Objects.equals(expectedAccountId, currentAccountId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, INVOICE_MEMBERSHIP_CHANGED + ": плательщик общего счета изменился; повторите действие");
        }
    }

    Set<Long> lockInvoiceOrderAggregates(Long invoiceId) {
        if (invoiceId == null) {
            return Set.of();
        }
        return lockOrderAggregates(invoiceOrderRepository.findOrderIdsByInvoiceId(invoiceId));
    }

    Set<Long> lockOrderAggregates(Collection<Long> orderIds) {
        return Set.copyOf(lockOrderAggregatesWithEntities(orderIds).keySet());
    }

    Map<Long, Order> lockOrderAggregatesWithEntities(Collection<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return Map.of();
        }
        Set<Long> sortedOrderIds = new TreeSet<>(orderIds.stream().filter(Objects::nonNull).collect(Collectors.toSet()));
        Map<Long, Order> locked = new HashMap<>();
        for (Long orderId : sortedOrderIds) {
            locked.put(orderId, orderAggregateMutationLockService.lock(orderId));
        }
        return locked;
    }

    Map<Long, List<PaymentLink>> paymentLinksRequiringCommonInvoiceRouteCheck(Map<Long, List<PaymentLink>> paymentLinksByOrder, Collection<CommonInvoiceOrder> items, Set<PaymentLink> appliedStandalonePayments) {
        if (paymentLinksByOrder == null || paymentLinksByOrder.isEmpty() || items == null || items.isEmpty()) {
            return Map.of();
        }
        Set<Long> invoiceOrderIds = items.stream().filter(Objects::nonNull).filter(item -> item.getOrder() != null && item.getOrder().getId() != null).map(item -> item.getOrder().getId()).collect(Collectors.toSet());
        Set<PaymentLink> applied = appliedStandalonePayments == null ? Set.of() : appliedStandalonePayments;
        Map<Long, List<PaymentLink>> selected = new HashMap<>();
        for (Map.Entry<Long, List<PaymentLink>> entry : paymentLinksByOrder.entrySet()) {
            if (!invoiceOrderIds.contains(entry.getKey())) {
                continue;
            }
            List<PaymentLink> relevant = entry.getValue().stream().filter(link -> !applied.contains(link)).toList();
            if (!relevant.isEmpty()) {
                selected.put(entry.getKey(), relevant);
            }
        }
        return Map.copyOf(selected);
    }

    void ensureNoCompetingStandaloneRoutesOrThrow(Map<Long, List<PaymentLink>> paymentLinksByOrder) {
        for (Map.Entry<Long, List<PaymentLink>> entry : (paymentLinksByOrder == null ? Map.<Long, List<PaymentLink>>of() : paymentLinksByOrder).entrySet()) {
            for (PaymentLink link : entry.getValue()) {
                if (isSafelyClosedStandaloneRoute(link)) {
                    continue;
                }
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Заказ #" + entry.getKey() + " нельзя включить в общий счет: отдельный способ оплаты" + " (ссылка #" + (link.getId() == null ? "?" : link.getId()) + ", статус " + (link.getStatus() == null ? "UNKNOWN" : link.getStatus().name()) + ") не закрыт безопасно. Проверьте T-Bank либо поступление по ручным реквизитам," + " затем явно отмените/закройте отдельный платеж.");
            }
        }
    }

    Set<PaymentLink> synchronizeConfirmedStandalonePaymentsOrThrow(CommonInvoice invoice, List<CommonInvoiceOrder> items, Map<Long, List<PaymentLink>> paymentLinksByOrder) {
        if (invoice == null || items == null || items.isEmpty() || paymentLinksByOrder == null || paymentLinksByOrder.isEmpty()) {
            return Set.of();
        }
        ensureAppliedStandalonePaymentSourcesHealthyOrThrow(items, paymentLinksByOrder);
        long allocatedPaidKopecks = items.stream().filter(CommonInvoiceOrder::isPaid).mapToLong(CommonInvoiceOrder::getAmountKopecks).sum();
        boolean hasUnallocatedCommonPayment = invoice.getPaidKopecks() > allocatedPaidKopecks;
        Set<PaymentLink> applied = new HashSet<>();
        List<ConfirmedStandaloneApplication> applications = new ArrayList<>();
        for (CommonInvoiceOrder item : items) {
            Order order = item == null ? null : item.getOrder();
            Long orderId = order == null ? null : order.getId();
            if (orderId == null) {
                continue;
            }
            List<PaymentLink> confirmed = paymentLinksByOrder.getOrDefault(orderId, List.of()).stream().filter(Objects::nonNull).filter(link -> link.getStatus() == PaymentLinkStatus.CONFIRMED).toList();
            if (confirmed.isEmpty()) {
                continue;
            }
            if (confirmed.size() != 1) {
                throw standalonePaymentConflict(orderId, "найдено несколько подтвержденных отдельных платежей (" + confirmed.size() + ")");
            }
            PaymentLink link = confirmed.getFirst();
            long currentAmount = item.getAmountKopecks();
            if (!item.isPaid()) {
                try {
                    currentAmount = amountKopecks(payableSum(order));
                } catch (AmountCalculationException exception) {
                    throw standalonePaymentConflict(orderId, "не удалось безопасно определить актуальную сумму позиции");
                }
            }
            ensureConfirmedStandaloneEvidence(item, link, currentAmount);
            if (item.getSourcePaymentLinkId() != null) {
                if (!Objects.equals(item.getSourcePaymentLinkId(), link.getId())) {
                    throw standalonePaymentConflict(orderId, "позиция уже связана с другим отдельным платежом");
                }
                ensureItemMatchesConfirmedStandalonePayment(item, link);
                applied.add(link);
                continue;
            }
            if (hasItemPaymentEvidence(item)) {
                throw standalonePaymentConflict(orderId, "позиция уже содержит другой либо недоказанный источник оплаты");
            }
            if (hasUnallocatedCommonPayment || hasCurrentCommonPaymentRoute(invoice)) {
                throw standalonePaymentConflict(orderId, "в общем счете уже есть платежный источник; автоматическое распределение небезопасно");
            }
            if (!isOrderPaid(order)) {
                throw standalonePaymentConflict(orderId, "платеж подтвержден, но перевод заказа в статус оплаты не завершен");
            }
            applications.add(new ConfirmedStandaloneApplication(item, link, currentAmount));
        }
        for (ConfirmedStandaloneApplication application : applications) {
            CommonInvoiceOrder item = application.item();
            PaymentLink link = application.link();
            item.setAmountKopecks(application.amountKopecks());
            item.setPaid(true);
            item.setUnpaid(false);
            item.setPaidAt(link.getPaidAt());
            item.setPaymentMethod(standalonePaymentMethod(link));
            item.setSourcePaymentLinkId(link.getId());
            if (isManualPayment(link)) {
                item.setManualPaidBy(normalize(link.getManualConfirmedBy()));
                item.setManualPaymentComment(normalize(link.getManualComment()));
            }
            mergeInvoicePaymentMethod(invoice, standalonePaymentMethod(link));
            applied.add(link);
        }
        if (!applications.isEmpty()) {
            invoiceOrderRepository.saveAll(applications.stream().map(ConfirmedStandaloneApplication::item).toList());
        }
        return Set.copyOf(applied);
    }

    void ensureConfirmedStandaloneEvidence(CommonInvoiceOrder item, PaymentLink link, long currentItemAmount) {
        Long orderId = item == null || item.getOrder() == null ? null : item.getOrder().getId();
        Long linkOrderId = link == null || link.getOrder() == null ? null : link.getOrder().getId();
        Long confirmedAmount = link == null ? null : link.getConfirmedAmountKopecks();
        boolean exactAmount = confirmedAmount != null && confirmedAmount > 0 && confirmedAmount == currentItemAmount && link.getAmountKopecks() == currentItemAmount;
        boolean commonEvidence = link != null && link.getId() != null && Objects.equals(orderId, linkOrderId) && link.getPaidAt() != null && exactAmount;
        boolean bankEvidence = commonEvidence && (link.getPaymentMethod() == PaymentMethod.BANK_FORM || link.getPaymentMethod() == PaymentMethod.SBP_QR) && !normalize(link.getTbankPaymentId()).isBlank() && !normalize(link.getTbankOrderId()).isBlank() && !normalize(link.getTbankTerminalKey()).isBlank();
        boolean manualEvidence = commonEvidence && isManualPayment(link) && link.getManualConfirmedAt() != null && !normalize(link.getManualConfirmedBy()).isBlank();
        if (!bankEvidence && !manualEvidence) {
            throw standalonePaymentConflict(orderId, "подтвержденный платеж не имеет однозначного происхождения или его сумма не совпадает");
        }
    }

    void ensureAppliedStandalonePaymentSourcesHealthyOrThrow(List<CommonInvoiceOrder> items, Map<Long, List<PaymentLink>> paymentLinksByOrder) {
        for (CommonInvoiceOrder item : items) {
            Long sourcePaymentLinkId = item == null ? null : item.getSourcePaymentLinkId();
            Order order = item == null ? null : item.getOrder();
            Long orderId = order == null ? null : order.getId();
            if (sourcePaymentLinkId == null || orderId == null) {
                continue;
            }
            PaymentLink source = paymentLinksByOrder.getOrDefault(orderId, List.of()).stream().filter(link -> sourcePaymentLinkId.equals(link.getId())).findFirst().orElse(null);
            if (source != null && source.getStatus() != PaymentLinkStatus.CONFIRMED) {
                throw standalonePaymentConflict(orderId, "ранее зачтенный отдельный платеж #" + sourcePaymentLinkId + " теперь имеет статус " + source.getStatus().name());
            }
        }
    }

    void ensureItemMatchesConfirmedStandalonePayment(CommonInvoiceOrder item, PaymentLink link) {
        boolean matches = item != null && item.isPaid() && !item.isUnpaid() && Objects.equals(item.getPaidAt(), link.getPaidAt()) && standalonePaymentMethod(link).equals(normalize(item.getPaymentMethod())) && Objects.equals(item.getSourcePaymentLinkId(), link.getId());
        if (!matches) {
            Long orderId = item == null || item.getOrder() == null ? null : item.getOrder().getId();
            throw standalonePaymentConflict(orderId, "источник отдельной оплаты не совпадает с позицией счета");
        }
    }

    boolean hasItemPaymentEvidence(CommonInvoiceOrder item) {
        return item != null && (item.isPaid() || item.isUnpaid() || item.getPaidAt() != null || !normalize(item.getPaymentMethod()).isBlank() || !normalize(item.getManualPaidBy()).isBlank() || !normalize(item.getManualPaymentComment()).isBlank() || !normalize(item.getManualPaymentReceiptUrl()).isBlank());
    }

    boolean hasCurrentCommonPaymentRoute(CommonInvoice invoice) {
        return invoice != null && (!normalize(invoice.getPaymentUrl()).isBlank() || !normalize(invoice.getTbankPaymentId()).isBlank() || !normalize(invoice.getTbankOrderId()).isBlank() || !normalize(invoice.getTbankTerminalKey()).isBlank() || invoice.getTbankPaymentAmountKopecks() != null || invoice.getTbankPaymentCreatedAt() != null);
    }

    String standalonePaymentMethod(PaymentLink link) {
        return isManualPayment(link) ? PAYMENT_METHOD_MANUAL : PAYMENT_METHOD_TBANK;
    }

    boolean isManualPayment(PaymentLink link) {
        return link != null && (link.getPaymentMethod() == PaymentMethod.MANUAL_MOBILE_BANK || link.getPaymentMethod() == PaymentMethod.MANUAL_EXTERNAL_LINK);
    }

    ResponseStatusException standalonePaymentConflict(Long orderId, String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Заказ #" + (orderId == null ? "?" : orderId) + ": " + reason + ". Нужна ручная сверка платежных источников.");
    }

    boolean isFrozenLiveContractorSource(CommonInvoice invoice) {
        return invoice != null && invoice.getContractorAllocationId() != null && invoice.getPaymentRouteManualSource() == ManualPaymentSource.CONTRACTOR_PAYMENT_PROFILE && "MANUAL_MOBILE_BANK".equalsIgnoreCase(normalize(invoice.getPaymentRouteType()));
    }

    boolean hasExactContractorSourceEvidence(CommonInvoice invoice) {
        return isFrozenLiveContractorSource(invoice) && invoice.getManualConfirmedAt() != null && normalize(invoice.getManualPaymentComment()).startsWith(CONTRACTOR_COMMON_SOURCE_CONFIRMATION_AUDIT_PREFIX);
    }

    boolean isSafelyClosedStandaloneRoute(PaymentLink link) {
        if (link == null || link.getStatus() == null) {
            return false;
        }
        String manualPaymentState = normalize(link.getLastError()).toLowerCase(Locale.ROOT);
        boolean manualPaymentCompleted = manualPaymentState.startsWith("manual_card_payment_completed:");
        boolean actualRecipientOperationPending = link.getManualActualRecipientFrozenAt() != null || manualPaymentState.startsWith("manual_card_payment_pending:");
        if (actualRecipientOperationPending && !manualPaymentCompleted) {
            return false;
        }
        boolean operationStillReserved = !normalize(link.getBankInitNonce()).isBlank() || !normalize(link.getBankCancelNonce()).isBlank() || link.getBankCancelOriginStatus() != null;
        if (operationStillReserved) {
            return false;
        }
        if (SAFELY_CLOSED_STANDALONE_PAYMENT_STATUSES.contains(link.getStatus())) {
            return true;
        }
        if (link.getStatus() != PaymentLinkStatus.EXPIRED) {
            return false;
        }
        boolean providerConfirmedExpiry = "DEADLINE_EXPIRED".equals(normalize(link.getProviderTerminalStatus()).toUpperCase(Locale.ROOT)) && (link.getPaymentMethod() == PaymentMethod.BANK_FORM || link.getPaymentMethod() == PaymentMethod.SBP_QR);
        return providerConfirmedExpiry || (link.getPaymentMethod() == PaymentMethod.BANK_FORM && normalize(link.getTbankPaymentId()).isBlank() && normalize(link.getTbankOrderId()).isBlank() && link.getInitiatedAt() == null);
    }

    void markStandalonePaymentRouteConflict(CommonInvoice invoice, ResponseStatusException conflict) {
        if (invoice == null) {
            throw conflict;
        }
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setNextReminderAt(null);
        invoice.setLastError(limit(STANDALONE_PAYMENT_ROUTE_CONFLICT + ": " + normalize(conflict == null ? null : conflict.getReason()), 512));
        invoiceRepository.save(invoice);
    }

    void ensureInvoiceMembershipUnchanged(Long invoiceId, Set<Long> lockedOrderIds) {
        Set<Long> currentOrderIds = invoiceOrderRepository.findMembershipByInvoiceIdForRead(invoiceId).stream().map(CommonInvoiceOrder::getOrder).filter(Objects::nonNull).map(Order::getId).filter(Objects::nonNull).collect(Collectors.toSet());
        if (!currentOrderIds.equals(lockedOrderIds == null ? Set.of() : lockedOrderIds)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, INVOICE_MEMBERSHIP_CHANGED + ": состав общего счета изменился; повторите действие");
        }
    }

    <T> T writeTransaction(Supplier<T> action) {
        RuntimeException lastException = null;
        for (int attempt = 1; attempt <= TRANSACTION_LOCK_RETRY_ATTEMPTS; attempt++) {
            try {
                TransactionTemplate template = new TransactionTemplate(transactionManager);
                template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                return template.execute(status -> action.get());
            } catch (RuntimeException e) {
                lastException = e;
                if (!isRetryableLockFailure(e) || attempt == TRANSACTION_LOCK_RETRY_ATTEMPTS) {
                    throw e;
                }
                log.warn("Транзакция общего счета упала на блокировке, повтор {}/{}", attempt + 1, TRANSACTION_LOCK_RETRY_ATTEMPTS, e);
                sleepBeforeTransactionRetry();
            }
        }
        throw lastException;
    }

    boolean isRetryableLockFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String className = current.getClass().getName();
            String message = current.getMessage();
            if (className.contains("CannotAcquireLock") || className.contains("Deadlock") || className.contains("LockAcquisition") || className.contains("MySQLTransactionRollback") || (message != null && message.toLowerCase(Locale.ROOT).contains("deadlock found")) || (message != null && message.contains(INVOICE_MEMBERSHIP_CHANGED))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    void sleepBeforeTransactionRetry() {
        try {
            Thread.sleep(TRANSACTION_LOCK_RETRY_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    void sendInvoiceAfterCommit(Long invoiceId, boolean manual) {
        commonInvoiceAfterCommitSender.send(invoiceId, manual);
    }

    Map<Long, InvoiceOrderBinding> invoiceBindings(Collection<Long> invoiceIds) {
        if (invoiceIds == null || invoiceIds.isEmpty()) {
            return Map.of();
        }
        return projectionBindings(invoiceOrderRepository.findBindingsByInvoiceIds(invoiceIds));
    }

    Map<Long, InvoiceOrderBinding> projectionBindings(Collection<CommonInvoiceOrderRepository.OrderInvoiceBindingView> views) {
        Map<Long, InvoiceOrderBinding> bindings = new HashMap<>();
        if (views == null) {
            return bindings;
        }
        for (CommonInvoiceOrderRepository.OrderInvoiceBindingView view : views) {
            Long orderId = view == null ? null : view.getOrderId();
            Long invoiceId = view == null ? null : view.getInvoiceId();
            Long accountId = view == null ? null : view.getAccountId();
            InvoiceOrderBinding previous = orderId == null ? null : bindings.put(orderId, new InvoiceOrderBinding(invoiceId, accountId));
            if (orderId == null || invoiceId == null || accountId == null || previous != null) {
                throw invoiceMembershipChanged("обнаружена неоднозначная связь заказа и общего счета");
            }
        }
        return bindings;
    }

    Map<Long, CommonInvoice> loadInvoiceSnapshots(Collection<Long> invoiceIds) {
        Map<Long, CommonInvoice> snapshots = new HashMap<>();
        if (invoiceIds == null) {
            return snapshots;
        }
        Set<Long> sortedInvoiceIds = invoiceIds.stream().filter(Objects::nonNull).collect(Collectors.toCollection(TreeSet::new));
        for (Long invoiceId : sortedInvoiceIds) {
            invoiceRepository.findByIdWithAccount(invoiceId).ifPresent(invoice -> snapshots.put(invoiceId, invoice));
        }
        if (snapshots.size() != sortedInvoiceIds.size()) {
            throw invoiceMembershipChanged("один из общих счетов исчез во время подготовки");
        }
        return snapshots;
    }

    Map<Long, CommonBillingAccount> loadAccountSnapshots(Collection<Long> accountIds) {
        Map<Long, CommonBillingAccount> snapshots = new HashMap<>();
        if (accountIds == null) {
            return snapshots;
        }
        Set<Long> sortedAccountIds = accountIds.stream().filter(Objects::nonNull).collect(Collectors.toCollection(TreeSet::new));
        for (Long accountId : sortedAccountIds) {
            accountRepository.findByIdWithRelations(accountId).ifPresent(account -> snapshots.put(accountId, account));
        }
        if (snapshots.size() != sortedAccountIds.size()) {
            throw invoiceMembershipChanged("один из общих плательщиков исчез во время подготовки");
        }
        return snapshots;
    }

    Map<Long, CommonBillingAccount> lockAccountsInCanonicalOrder(Map<Long, CommonBillingAccount> snapshots) {
        Map<Long, CommonBillingAccount> locked = new HashMap<>();
        if (snapshots == null || snapshots.isEmpty()) {
            return locked;
        }
        for (Long accountId : new TreeSet<>(snapshots.keySet())) {
            CommonBillingAccount account = lockFreshAccountAfterOrderPrelude(accountId);
            locked.put(accountId, account);
        }
        return locked;
    }

    Map<Long, CommonInvoice> lockInvoicesInCanonicalOrder(Map<Long, CommonInvoice> snapshots) {
        Map<Long, CommonInvoice> locked = new HashMap<>();
        if (snapshots == null || snapshots.isEmpty()) {
            return locked;
        }
        for (Long invoiceId : new TreeSet<>(snapshots.keySet())) {
            CommonInvoice invoice = invoiceRepository.findByIdWithAccountForUpdate(invoiceId).orElseThrow(() -> invoiceMembershipChanged("общий счет исчез во время операции"));
            entityManager.refresh(invoice);
            Long expectedAccountId = snapshots.get(invoiceId) == null || snapshots.get(invoiceId).getAccount() == null ? null : snapshots.get(invoiceId).getAccount().getId();
            ensureInvoiceAccountUnchanged(invoice, expectedAccountId);
            locked.put(invoiceId, invoice);
        }
        return locked;
    }

    ResponseStatusException invoiceMembershipChanged(String detail) {
        return new ResponseStatusException(HttpStatus.CONFLICT, INVOICE_MEMBERSHIP_CHANGED + ": " + detail + "; повторите действие");
    }

    boolean areInvoiceItemsReady(List<CommonInvoiceOrder> items) {
        return areInvoiceItemsReady(items, this::hasActiveRecovery);
    }

    private boolean areInvoiceItemsReady(List<CommonInvoiceOrder> items, java.util.function.Predicate<CommonInvoiceOrder> recovery) {
        return items != null && !items.isEmpty() && items.stream().allMatch(CommonInvoiceOrder::isReady) && items.stream().map(CommonInvoiceOrder::getOrder).noneMatch(order -> ACTIVE_WORK_STATUSES.contains(statusTitle(order))) && items.stream().noneMatch(recovery);
    }

    boolean hasAttentionError(CommonInvoice invoice, String prefix) {
        return attentionError(invoice).startsWith(prefix);
    }

    boolean isMigrationPaymentRegistryAttention(CommonInvoice invoice) {
        return attentionError(invoice).startsWith(MIGRATION_PAYMENT_REGISTRY_ATTENTION);
    }

    String paymentRefStatus(CommonInvoicePaymentRef ref) {
        return normalize(ref == null ? null : ref.getStatus()).toUpperCase(Locale.ROOT);
    }

    String normalizedPaymentProvider(CommonInvoicePaymentRef ref) {
        String provider = normalize(ref == null ? null : ref.getProvider()).toUpperCase(Locale.ROOT);
        return provider.isBlank() ? PROVIDER_TBANK : provider;
    }

    String providerPaymentId(CommonInvoicePaymentRef ref) {
        String value = normalize(ref == null ? null : ref.getProviderPaymentId());
        return value.isBlank() ? normalize(ref == null ? null : ref.getTbankPaymentId()) : value;
    }

    String attentionError(CommonInvoice invoice) {
        return normalize(invoice == null ? null : invoice.getLastError()).toLowerCase(Locale.ROOT);
    }

    void recordCommonInvoicePrepayment(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        recordCurrentPaymentRef(invoice, PAYMENT_REF_PREPAID, PREPAID_WAITING_COMMON_INVOICE_READY);
        mergeInvoicePaymentMethod(invoice, PAYMENT_METHOD_TBANK);
        long prepaid = confirmedCommonInvoicePrepaymentKopecks(invoice);
        invoice.setPaidKopecks(Math.min(invoice.getAmountKopecks(), prepaid));
        invoice.setStatus(CommonInvoiceStatus.COLLECTING);
        invoice.setNextReminderAt(null);
        invoice.setLastError(null);
        invoiceRepository.save(invoice);
        log.info("Оплата общего счета {} принята как предоплата: paid={} amount={} ready={}/{}", invoice.getId(), invoice.getPaidKopecks(), invoice.getAmountKopecks(), items == null ? 0 : items.stream().filter(CommonInvoiceOrder::isReady).count(), items == null ? 0 : items.size());
    }

    boolean applyCommonInvoicePrepaymentIfReady(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        long prepaid = confirmedCommonInvoicePrepaymentKopecks(invoice);
        if (prepaid <= 0 || !allOrdersReady(items)) {
            return false;
        }
        if (prepaid >= invoice.getAmountKopecks()) {
            closePaidInvoice(invoice, items);
            if (items.stream().allMatch(CommonInvoiceOrder::isPaid)) {
                markCommonInvoicePrepaymentsApplied(invoice);
                log.info("Предоплата общего счета {} применена после готовности всех заказов", invoice.getId());
            }
            return true;
        }
        boolean firstPartialPrepayment = invoice.getStatus() != CommonInvoiceStatus.PARTIALLY_PAID;
        invoice.setPaidKopecks(prepaid);
        invoice.setStatus(CommonInvoiceStatus.PARTIALLY_PAID);
        invoice.setLastError(null);
        invoice.setNextReminderAt(nextAutomaticPaymentReminderAt(LocalDateTime.now()));
        invoiceRepository.save(invoice);
        markInvoiceOrdersPublished(items);
        if (firstPartialPrepayment && immediateClientMessagesEnabled()) {
            sendInvoiceAfterCommit(invoice.getId(), false);
        }
        log.info("Предоплата общего счета {} меньше итоговой суммы: prepaid={}, amount={}", invoice.getId(), prepaid, invoice.getAmountKopecks());
        return true;
    }

    long confirmedCommonInvoicePrepaymentKopecks(CommonInvoice invoice) {
        Long invoiceId = invoice == null ? null : invoice.getId();
        if (invoiceId == null) {
            return 0;
        }
        return paymentRefRepository.sumAmountKopecksByInvoiceIdAndStatus(invoiceId, PAYMENT_REF_PREPAID);
    }

    void markCommonInvoicePrepaymentsApplied(CommonInvoice invoice) {
        Long invoiceId = invoice == null ? null : invoice.getId();
        if (invoiceId == null) {
            return;
        }
        setPaymentRefsStatus(paymentRefRepository.findByInvoiceIdAndStatusForUpdate(invoiceId, PAYMENT_REF_PREPAID), PAYMENT_REF_APPLIED);
    }

    void markConfirmedPaymentRefsApplied(Long invoiceId) {
        if (invoiceId == null) {
            return;
        }
        setPaymentRefsStatus(paymentRefRepository.findByInvoiceIdAndStatusForUpdate(invoiceId, PAYMENT_REF_CONFIRMED), PAYMENT_REF_APPLIED);
    }

    void closePaidInvoice(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        closePaidInvoice(invoice, items, Set.of(), null);
    }

    void closePaidInvoice(CommonInvoice invoice, List<CommonInvoiceOrder> items, Set<Long> alreadyClosedOrderIds) {
        closePaidInvoice(invoice, items, alreadyClosedOrderIds, null);
    }

    void closePaidInvoice(CommonInvoice invoice, List<CommonInvoiceOrder> items, Set<Long> alreadyClosedOrderIds, Runnable finalAttribution) {
        if (isFrozenLiveContractorSource(invoice) && finalAttribution == null && !hasExactContractorSourceEvidence(invoice) && !commonManualPaymentAttributionCoordinator.hasRecordedAttribution(invoice.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Поступление по реквизитам специалиста или менеджера подтверждается только сверкой конкретного счета");
        }
        observabilityMetrics.observeTransactionCompletion(COMMON_INVOICE_CLOSE);
        if (normalize(invoice.getPaymentMethod()).isBlank()) {
            invoice.setPaymentMethod(PAYMENT_METHOD_TBANK);
        }
        for (CommonInvoiceOrder item : items) {
            try {
                if (isAlreadyClosedOrder(item, alreadyClosedOrderIds)) {
                    cleanupPaidOrderAfterCommonBilling(item.getOrder());
                } else if (!item.isPaid() || !isOrderPaid(item.getOrder())) {
                    closeOrderAsPaidWithoutNextOrder(item.getOrder());
                } else {
                    cleanupPaidOrderAfterCommonBilling(item.getOrder());
                }
                if (!item.isPaid()) {
                    item.setPaid(true);
                    item.setPaidAt(LocalDateTime.now());
                }
                if (normalize(item.getPaymentMethod()).isBlank()) {
                    item.setPaymentMethod(invoice.getPaymentMethod());
                }
                item.setUnpaid(false);
            } catch (Exception e) {
                observabilityMetrics.recordCaughtFailure(COMMON_INVOICE_CLOSE, CLOSE_ORDER);
                Long orderId = item == null || item.getOrder() == null ? null : item.getOrder().getId();
                log.error("Не удалось закрыть заказ {} оплатой общего счета {}", orderId, invoice.getId(), e);
                throw commonInvoiceAtomicFailure(invoice, orderId, "close_order", e);
            }
        }
        invoiceOrderRepository.saveAll(items);
        invoice.setPaidKopecks(invoice.getAmountKopecks());
        if (invoice.getPaidAt() == null) {
            invoice.setPaidAt(LocalDateTime.now());
        }
        invoice.setNextReminderAt(null);
        markInvoicePaidClosed(invoice);
        invoice.setLastError(null);
        if (finalAttribution != null) {
            invoiceRepository.save(invoice);
            recordFinalAttributionAndEnqueueRecipientNotifications(invoice, finalAttribution);
        }
        paymentNotificationOutboxRepository.enqueueClient(invoice.getId());
        invoiceRepository.save(invoice);
        manualPaymentTaskService.completeCommonInvoiceTaskIfTargetReached(invoice.getPaymentRouteManualTaskId());
        openNextOrdersIfEnabled(invoice, items);
        scheduleContractorShadowReconcile(invoice.getId());
    }

    void recordFinalAttributionAndEnqueueRecipientNotifications(CommonInvoice invoice, Runnable finalAttribution) {
        entityManager.flush();
        finalAttribution.run();
        // The outbox INSERT ... SELECT must observe immutable attribution rows
        // created by the coordinator in this same business transaction.
        entityManager.flush();
        paymentNotificationOutboxRepository.enqueueRecipients(invoice.getId());
    }

    void markInvoicePaidClosed(CommonInvoice invoice) {
        if (invoice.getStatus() != CommonInvoiceStatus.PAID && invoice.getPreviousStatus() == null) {
            invoice.setPreviousStatus(invoice.getStatus() == null ? null : invoice.getStatus().name());
        }
        if (invoice.getPaidAt() == null) {
            invoice.setPaidAt(LocalDateTime.now());
        }
        invoice.setStatus(CommonInvoiceStatus.PAID);
        if (invoice.getClosedAt() == null) {
            invoice.setClosedAt(invoice.getPaidAt());
        }
        if (normalize(invoice.getClosedBy()).isBlank()) {
            String manualActor = normalize(invoice.getManualPaidBy());
            invoice.setClosedBy(limit(manualActor.isBlank() ? "payment-confirmation" : manualActor, 160));
        }
        invoice.setCloseReason("PAID");
        invoice.setNextReminderAt(null);
    }

    void closeOrderAsPaidForConfirmedItem(CommonInvoice invoice, CommonInvoiceOrder item) {
        try {
            Order order = item == null ? null : item.getOrder();
            if (isOrderPaid(order)) {
                cleanupPaidOrderAfterCommonBilling(order);
            } else {
                closeOrderAsPaidWithoutNextOrder(order, true);
            }
        } catch (Exception e) {
            Long orderId = item == null || item.getOrder() == null ? null : item.getOrder().getId();
            observabilityMetrics.recordCaughtFailure(COMMON_INVOICE_CLOSE, CLOSE_ORDER);
            log.error("Не удалось закрыть заказ {} после подтвержденной оплаты отдельной ссылки общего счета {}", orderId, invoice == null ? null : invoice.getId(), e);
            throw commonInvoiceAtomicFailure(invoice, orderId, "close_confirmed_order", e);
        }
    }

    IllegalStateException commonInvoiceAtomicFailure(CommonInvoice invoice, Long orderId, String operation, Throwable cause) {
        Long invoiceId = invoice == null ? null : invoice.getId();
        return new IllegalStateException("Common invoice operation failed atomically: operation=" + operation + ", invoiceId=" + invoiceId + ", orderId=" + orderId, cause);
    }

    boolean isAlreadyClosedOrder(CommonInvoiceOrder item, Set<Long> alreadyClosedOrderIds) {
        if (item == null || item.getOrder() == null || item.getOrder().getId() == null || alreadyClosedOrderIds == null || alreadyClosedOrderIds.isEmpty()) {
            return false;
        }
        return alreadyClosedOrderIds.contains(item.getOrder().getId());
    }

    boolean isOrderPaid(Order order) {
        return "Оплачено".equals(statusTitle(order));
    }

    void closeOrderAsPaidWithoutNextOrder(Order order) throws Exception {
        closeOrderAsPaidWithoutNextOrder(order, false);
    }

    void closeOrderAsPaidWithoutNextOrder(Order order, boolean standalonePaymentAlreadyConfirmed) throws Exception {
        if (!standalonePaymentAlreadyConfirmed) {
            lockAndEnsureNoCompetingStandaloneBankPayment(order);
        }
        orderTransactionService.handlePaymentStatus(order, false);
        cleanupPaidOrderAfterCommonBilling(order);
    }

    void lockAndEnsureNoCompetingStandaloneBankPayment(Order order) {
        if (order == null || order.getId() == null) {
            return;
        }
        // The invoice entry point already owns the canonical Order row. This
        // current/locking read is therefore ordered strictly as
        // Order -> PaymentLink and observes an Init/GetQr reservation that
        // committed while CommonBilling was waiting for the Order lock.
        boolean competingPayment = standalonePaymentState.hasStartedPaymentWithLock(order.getId());
        if (competingPayment) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "У заказа есть незавершенный T-Bank/СБП платеж. Проверьте его в журнале перед закрытием общего счета.");
        }
    }

    void cleanupPaidOrderAfterCommonBilling(Order order) {
        Long orderId = order == null ? null : order.getId();
        if (orderId == null) {
            return;
        }
        if (TransactionSynchronizationManager.isActualTransactionActive() && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {

                @Override
                public void afterCommit() {
                    cleanupPaidOrderAfterCommit(orderId);
                }
            });
            return;
        }
        performPaidOrderCleanup(order);
    }

    void cleanupPaidOrderAfterCommit(Long orderId) {
        try {
            writeTransaction(() -> {
                Order lockedOrder = orderAggregateMutationLockService.lock(orderId);
                performPaidOrderCleanup(lockedOrder);
                return null;
            });
        } catch (RuntimeException e) {
            log.warn("Не удалось выполнить отложенную очистку оплаченного заказа {}", orderId, e);
        }
    }

    /**
     * Both downstream operations are idempotent and safe to retry after commit.
     */
    void performPaidOrderCleanup(Order order) {
        try {
            manualPaymentAutoConfirmationService.retireOpenLinksForPaidOrder(order);
        } catch (RuntimeException e) {
            log.warn("Не удалось закрыть открытые платежные ссылки заказа {} после оплаты общего счета", order == null ? null : order.getId(), e);
        }
        try {
            paymentInvoiceRetryScheduler.cancelBadReviewAutoBan(order, "Оплата общего счета");
        } catch (RuntimeException e) {
            log.warn("Не удалось отменить авто-бан плохих отзывов заказа {} после оплаты общего счета", order == null ? null : order.getId(), e);
        }
    }

    void openNextOrdersIfEnabled(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        if (invoice == null || invoice.getAccount() == null || !invoice.getAccount().isAutoRepeatOrders()) {
            return;
        }
        for (CommonInvoiceOrder item : items) {
            try {
                nextOrderRequestService.openForPaidOrder(item.getOrder());
            } catch (RuntimeException e) {
                observabilityMetrics.recordCaughtFailure(COMMON_INVOICE_CLOSE, OPEN_NEXT_ORDER);
                Long orderId = item == null || item.getOrder() == null ? null : item.getOrder().getId();
                log.error("Не удалось сохранить заявку следующего заказа после полной оплаты общего счета {} для заказа {}", invoice.getId(), orderId, e);
                throw commonInvoiceAtomicFailure(invoice, orderId, "open_next_order", e);
            }
        }
    }

    String orderFailureLabel(CommonInvoiceOrder item) {
        return orderFailureLabel(item == null ? null : item.getOrder());
    }

    String orderFailureLabel(Order order) {
        String companyTitle = companyTitle(order);
        Long orderId = order == null ? null : order.getId();
        return companyTitle + " #" + (orderId == null ? "-" : orderId);
    }

    String companyTitle(Order order) {
        Company company = order == null ? null : order.getCompany();
        String title = company == null ? "" : normalize(company.getTitle());
        return title.isBlank() ? "компания не указана" : title;
    }

    void recalculateInvoice(CommonInvoice invoice) {
        recalculateInvoice(invoice, invoiceOrderRepository.findByInvoiceIdWithOrders(invoice.getId()));
    }

    void recalculateInvoice(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        boolean preserveRecordedAttentionPayment = invoice.getStatus() == CommonInvoiceStatus.NEEDS_ATTENTION && !hasAttentionError(invoice, "late_tbank_payment") && invoice.getAmountKopecks() > 0 && invoice.getPaidKopecks() >= invoice.getAmountKopecks();
        long recordedPaid = invoice.getPaidKopecks();
        boolean preserveExactContractorPayment = hasExactContractorSourceEvidence(invoice) || commonManualPaymentAttributionCoordinator.hasRecordedAttribution(invoice.getId());
        long amount = items.stream().mapToLong(CommonInvoiceOrder::getAmountKopecks).sum();
        long paid = items.stream().filter(CommonInvoiceOrder::isPaid).mapToLong(CommonInvoiceOrder::getAmountKopecks).sum() + confirmedCommonInvoicePrepaymentKopecks(invoice);
        invoice.setAmountKopecks(amount);
        invoice.setPaidKopecks(Math.min(amount, (preserveRecordedAttentionPayment || preserveExactContractorPayment) ? Math.max(recordedPaid, paid) : paid));
        boolean preserveMigrationPaymentEvidence = isMigrationPaymentRegistryAttention(invoice);
        if (!preserveMigrationPaymentEvidence && invoice.getStatus() != CommonInvoiceStatus.PAID && invoice.getTbankPaymentAmountKopecks() != null && invoice.getTbankPaymentAmountKopecks() != remainingKopecks(invoice)) {
            archiveCurrentPaymentRef(invoice, "remaining_changed");
            clearCurrentPaymentRef(invoice);
        }
        if (invoice.getStatus() != CommonInvoiceStatus.PAID && invoice.getStatus() != CommonInvoiceStatus.UNPAID && invoice.getStatus() != CommonInvoiceStatus.BAN && invoice.getStatus() != CommonInvoiceStatus.NEEDS_ATTENTION) {
            if (paid > 0 && paid < amount) {
                invoice.setStatus(CommonInvoiceStatus.PARTIALLY_PAID);
                ensurePartialPaymentNextAction(invoice);
            }
        }
        invoiceRepository.save(invoice);
    }

    void ensurePartialPaymentNextAction(CommonInvoice invoice) {
        if (invoice == null || invoice.getStatus() != CommonInvoiceStatus.PARTIALLY_PAID) {
            return;
        }
        if (invoice.getSentAt() != null && invoice.getNextReminderAt() == null) {
            invoice.setNextReminderAt(nextAutomaticPaymentReminderAt(LocalDateTime.now()));
        }
    }

    void refreshInvoiceAmounts(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        refreshInvoiceAmounts(invoice, items, Map.of());
    }

    /** Board-only batch inputs belong to this transaction. Commands always use fresh owner reads above. */
    void refreshInvoiceAmounts(CommonInvoice invoice, List<CommonInvoiceOrder> items, Map<Long, BigDecimal> preparedAmounts) {
        refreshInvoiceAmounts(invoice, items, preparedAmounts, Map.of());
    }

    Map<Long, Boolean> prepareBoardRecoveryState(Collection<Order> orders) {
        List<Long> ids = orders.stream().filter(Objects::nonNull).map(Order::getId).filter(Objects::nonNull).distinct().toList();
        Set<Long> activeIds = recoveryGateService.activeRecoveryOrderIds(ids);
        return ids.stream().collect(Collectors.toMap(Function.identity(), activeIds::contains));
    }

    void refreshInvoiceAmounts(CommonInvoice invoice, List<CommonInvoiceOrder> items,
                               Map<Long, BigDecimal> preparedAmounts, Map<Long, Boolean> preparedRecovery) {
        java.util.function.Predicate<CommonInvoiceOrder> recovery = item -> {
            Order order = item == null ? null : item.getOrder();
            Boolean active = order == null ? null : preparedRecovery.get(order.getId());
            return active == null ? hasActiveRecovery(item) : active;
        };
        // Standalone confirmations are synchronized separately and only while
        // a LockedInvoicePaymentPrelude owns Order and PaymentLink locks.
        if (isMigrationPaymentRegistryAttention(invoice)) {
            return;
        }
        // Superseded rows remain available as an immutable financial snapshot,
        // but no longer own the current order membership.
        if (items != null && !items.isEmpty() && items.stream().noneMatch(CommonInvoiceOrder::isActiveMembership)) {
            return;
        }
        boolean changed = false;
        List<String> amountFailures = new ArrayList<>();
        for (CommonInvoiceOrder item : items) {
            if (item.isPaid()) {
                continue;
            }
            long payable;
            try {
                Order order = item.getOrder();
                BigDecimal prepared = order == null ? null : preparedAmounts.get(order.getId());
                payable = amountKopecks(prepared == null ? payableSum(order) : prepared);
            } catch (AmountCalculationException e) {
                amountFailures.add(orderFailureLabel(item));
                log.warn("Не удалось посчитать сумму общего счета {} для заказа {}", invoice == null ? null : invoice.getId(), item.getOrder() == null ? null : item.getOrder().getId(), e);
                continue;
            }
            if (item.getAmountKopecks() != payable) {
                item.setAmountKopecks(payable);
                changed = true;
            }
            if (!item.isReady() && canMarkCommonInvoiceItemReady(item.getOrder(), recovery.test(item))) {
                item.setReady(true);
                changed = true;
            }
        }
        if (!amountFailures.isEmpty()) {
            markAmountCalculationFailed(invoice, amountFailures);
            return;
        }
        if (changed) {
            invoiceOrderRepository.saveAll(items);
        }
        // Amount calculation must succeed before immutable zero/no-recipient
        // markers can be written. If completion accrual then fails, this whole
        // transaction rolls back both item amounts and routing state.
        recalculateInvoice(invoice, items);
        if (invoice != null) {
            if (allOrdersReady(items, recovery) && applyCommonInvoicePrepaymentIfReady(invoice, items)) {
                return;
            }
            if (invoice.getStatus() == CommonInvoiceStatus.COLLECTING && areInvoiceItemsReady(items, recovery)) {
                invoice.setStatus(CommonInvoiceStatus.READY);
                invoiceRepository.save(invoice);
                markInvoiceOrdersPublished(items);
            } else if (invoice.getStatus() == CommonInvoiceStatus.READY && !allOrdersReady(items, recovery)) {
                invoice.setStatus(CommonInvoiceStatus.COLLECTING);
                invoiceRepository.save(invoice);
            }
        }
    }

    boolean canMarkCommonInvoiceItemReady(Order order) {
        return canMarkCommonInvoiceItemReady(order, order != null && order.getId() != null && recoveryGateService.hasActiveRecoveryTasks(order.getId()));
    }

    private boolean canMarkCommonInvoiceItemReady(Order order, boolean activeRecovery) {
        if (order == null || order.getId() == null) {
            return false;
        }
        if (activeRecovery) {
            return false;
        }
        String status = statusTitle(order);
        if (READY_ON_ATTACH_STATUSES.contains(status)) {
            return true;
        }
        return order.getAmount() > 0 && order.getCounter() >= order.getAmount() && !ACTIVE_WORK_STATUSES.contains(status);
    }

    void markAmountCalculationFailed(CommonInvoice invoice, List<String> amountFailures) {
        if (invoice == null) {
            return;
        }
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setNextReminderAt(null);
        invoice.setLastError(limit("amount_calc_failed: не удалось посчитать сумму по заказам: " + String.join(", ", amountFailures == null ? List.of("неизвестный заказ") : amountFailures), 512));
        invoiceRepository.save(invoice);
    }

    void setPaymentRefsStatus(List<CommonInvoicePaymentRef> refs, String status) {
        if (refs == null || refs.isEmpty()) {
            return;
        }
        for (CommonInvoicePaymentRef ref : refs) {
            ref.setStatus(status);
        }
        paymentRefRepository.saveAll(refs);
    }

    boolean isIdempotentArchivedWebhook(CommonInvoicePaymentRef ref, String webhookStatus, boolean success, String errorCode) {
        String currentStatus = normalize(ref == null ? null : ref.getStatus()).toUpperCase(Locale.ROOT);
        String status = normalize(webhookStatus).toUpperCase(Locale.ROOT);
        if (PAYMENT_REF_CONFIRMED.equals(status)) {
            return PAYMENT_REF_PREPAID.equals(currentStatus) || PAYMENT_REF_CONFIRMED.equals(currentStatus) || PAYMENT_REF_APPLYING.equals(currentStatus) || PAYMENT_REF_APPLIED.equals(currentStatus);
        }
        return currentStatus.equals(status) && isTerminalPaymentWebhook(status, success, errorCode);
    }

    Optional<CommonInvoicePaymentRef> lockedPaymentRef(CommonInvoicePaymentRef ref) {
        if (ref == null || ref.getId() == null) {
            return Optional.ofNullable(ref);
        }
        return paymentRefRepository.findByIdForUpdate(ref.getId());
    }

    boolean allOrdersReady(List<CommonInvoiceOrder> items) {
        return allOrdersReady(items, this::hasActiveRecovery);
    }

    private boolean allOrdersReady(List<CommonInvoiceOrder> items, java.util.function.Predicate<CommonInvoiceOrder> recovery) {
        return items != null && !items.isEmpty() && items.stream().allMatch(CommonInvoiceOrder::isReady) && items.stream().noneMatch(recovery);
    }

    boolean hasActiveRecovery(List<CommonInvoiceOrder> items) {
        return items != null && items.stream().anyMatch(this::hasActiveRecovery);
    }

    boolean hasActiveRecovery(CommonInvoiceOrder item) {
        Order order = item == null ? null : item.getOrder();
        return order != null && order.getId() != null && recoveryGateService.hasActiveRecoveryTasks(order.getId());
    }

    void mergeInvoicePaymentMethod(CommonInvoice invoice, String method) {
        String current = normalize(invoice.getPaymentMethod()).toUpperCase(Locale.ROOT);
        if (current.isBlank()) {
            invoice.setPaymentMethod(method);
        } else if (!current.equals(method)) {
            invoice.setPaymentMethod(PAYMENT_METHOD_MIXED);
        }
    }

    void scheduleContractorShadowReconcile(Long invoiceId) {
        if (invoiceId == null) {
            return;
        }
        Runnable reconcile = () -> {
            try {
                contractorPaymentShadowService.reconcileCommonInvoiceId(invoiceId);
            } catch (RuntimeException e) {
                // The paid common invoice remains a durable retry source for
                // the claim-based contractor reconciliation worker.
                log.error("Не удалось сразу сверить назначение оплаченного общего счета invoiceId={}, code={}", invoiceId, e.getClass().getSimpleName());
            }
        };
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive() && org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {

                @Override
                public void afterCommit() {
                    reconcile.run();
                }
            });
            return;
        }
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            log.warn("Пропущена немедленная сверка общего счета без transaction synchronization invoiceId={}", invoiceId);
            return;
        }
        reconcile.run();
    }

    boolean immediateClientMessagesEnabled() {
        return appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_IMMEDIATE_ENABLED, true);
    }

    boolean automaticPaymentRemindersEnabled() {
        return appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_PAYMENT_REMINDER_ENABLED, true);
    }

    int paymentReminderIntervalDays() {
        int configured = appSettingService.getInt(AppSettingService.CLIENT_MESSAGES_PAYMENT_REMINDER_INTERVAL_DAYS, DEFAULT_REMINDER_INTERVAL_DAYS);
        return Math.max(1, Math.min(365, configured));
    }

    LocalDateTime nextAutomaticPaymentReminderAt(LocalDateTime from) {
        if (!automaticPaymentRemindersEnabled()) {
            return null;
        }
        LocalDateTime base = from == null ? LocalDateTime.now() : from;
        return base.plusDays(paymentReminderIntervalDays());
    }

    void markInvoiceOrdersPublished(Long invoiceId) {
        if (invoiceId == null) {
            return;
        }
        markInvoiceOrdersPublished(invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId));
    }

    void markInvoiceOrdersPublished(List<CommonInvoiceOrder> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        OrderStatus publicStatus = orderStatusService.getOrderStatusByTitle(STATUS_PUBLIC);
        for (CommonInvoiceOrder item : items) {
            Order order = item == null ? null : item.getOrder();
            if (order == null || STATUS_PUBLIC.equals(statusTitle(order))) {
                continue;
            }
            order.setStatus(publicStatus);
            orderRepository.save(order);
        }
    }

    BigDecimal payableSum(Order order) {
        try {
            return badReviewTaskService.getPayableSum(order);
        } catch (RuntimeException e) {
            throw new AmountCalculationException(order == null ? null : order.getId(), e);
        }
    }

    long amountKopecks(BigDecimal amount) {
        return (amount == null ? BigDecimal.ZERO : amount).setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValue();
    }

    BigDecimal amountRubles(long kopecks) {
        return BigDecimal.valueOf(kopecks, 2);
    }

    long remainingKopecks(CommonInvoice invoice) {
        return Math.max(0, invoice.getAmountKopecks() - invoice.getPaidKopecks());
    }

    String statusTitle(Order order) {
        return order == null || order.getStatus() == null || order.getStatus().getTitle() == null ? "" : order.getStatus().getTitle();
    }

    boolean hasForeignPaymentIdBinding(String tbankPaymentId, Long currentInvoiceId, Long allowedPaymentRefId) {
        String paymentId = normalize(tbankPaymentId);
        if (paymentId.isBlank()) {
            return false;
        }
        boolean invoiceCollision = invoiceRepository.findIdsByTbankPaymentId(paymentId).stream().anyMatch(id -> !Objects.equals(id, currentInvoiceId));
        if (invoiceCollision) {
            return true;
        }
        return paymentRefRepository.findByTbankPaymentId(paymentId).map(CommonInvoicePaymentRef::getId).filter(id -> !Objects.equals(id, allowedPaymentRefId)).isPresent();
    }

    void quarantineWebhookPaymentIdCollision(CommonInvoice invoice, CommonInvoicePaymentRef currentRef, String tbankPaymentId) {
        if (invoice == null) {
            return;
        }
        if (currentRef != null && Objects.equals(invoice.getId(), paymentRefInvoiceId(currentRef))) {
            currentRef.setStatus(PAYMENT_REF_INIT_CONFLICT);
            currentRef.setReason(limit("webhook_payment_id_collision:" + normalize(tbankPaymentId), 160));
            paymentRefRepository.save(currentRef);
        }
        clearCurrentPaymentRef(invoice);
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setNextReminderAt(null);
        invoice.setLastError(limit("payment_init_response_collision: webhook PaymentId уже связан с другим платежом; " + "ссылка заблокирована, нужна ручная сверка", 512));
        invoiceRepository.save(invoice);
    }

    void quarantineDuplicateProviderIdentityInvoices(String identityName, String identityValue, Collection<Long> invoiceIds) {
        Set<Long> expectedInvoiceIds = invoiceIds == null ? Set.of() : invoiceIds.stream().filter(Objects::nonNull).collect(Collectors.toCollection(TreeSet::new));
        if (expectedInvoiceIds.isEmpty()) {
            return;
        }
        Map<Long, CommonInvoice> invoiceSnapshots = loadInvoiceSnapshots(expectedInvoiceIds);
        Map<Long, InvoiceOrderBinding> expectedBindings = invoiceBindings(expectedInvoiceIds);
        Set<Long> accountIds = invoiceSnapshots.values().stream().map(CommonInvoice::getAccount).filter(Objects::nonNull).map(CommonBillingAccount::getId).filter(Objects::nonNull).collect(Collectors.toCollection(TreeSet::new));
        Map<Long, CommonBillingAccount> accountSnapshots = loadAccountSnapshots(accountIds);
        lockOrderAggregatesWithEntities(expectedBindings.keySet());
        lockAccountsInCanonicalOrder(accountSnapshots);
        Map<Long, CommonInvoice> lockedInvoices = lockInvoicesInCanonicalOrder(invoiceSnapshots);
        Set<Long> currentInvoiceIds = new TreeSet<>(providerIdentityInvoiceIds(identityName, identityValue));
        if (!currentInvoiceIds.equals(expectedInvoiceIds) || !invoiceBindings(currentInvoiceIds).equals(expectedBindings)) {
            throw invoiceMembershipChanged("состав конфликтующих PaymentId изменился");
        }
        for (Long invoiceId : expectedInvoiceIds) {
            CommonInvoice invoice = lockedInvoices.get(invoiceId);
            if (invoice == null) {
                throw invoiceMembershipChanged("конфликтующий общий счет исчез");
            }
            invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
            invoice.setNextReminderAt(null);
            invoice.setPaymentUrl(null);
            invoice.setLastError(limit("payment_registry_collision: " + identityName + " " + identityValue + " связан с несколькими общими счетами; webhook не применен", 512));
            invoiceRepository.save(invoice);
        }
    }

    void quarantineWebhookIdentityConstraint(Map<String, String> payload, RuntimeException failure) {
        String orderId = normalize(payload == null ? null : payload.get("OrderId"));
        String paymentId = normalize(payload == null ? null : payload.get("PaymentId"));
        List<Long> invoiceIdsByOrderId = orderId.isBlank() ? List.of() : invoiceRepository.findIdsByTbankOrderId(orderId);
        if (new HashSet<>(invoiceIdsByOrderId).size() > 1) {
            quarantineDuplicateProviderIdentityInvoices("OrderId", orderId, invoiceIdsByOrderId);
            return;
        }
        List<Long> invoiceIdsByPaymentId = paymentId.isBlank() ? List.of() : invoiceRepository.findIdsByTbankPaymentId(paymentId);
        if (new HashSet<>(invoiceIdsByPaymentId).size() > 1) {
            quarantineDuplicateProviderIdentityInvoices("PaymentId", paymentId, invoiceIdsByPaymentId);
            return;
        }
        CommonInvoicePaymentRef ref = findProviderPaymentRefCandidate(orderId, paymentId).orElse(null);
        Long invoiceId = paymentRefInvoiceId(ref);
        if (invoiceId == null && !orderId.isBlank()) {
            invoiceId = invoiceIdsByOrderId.stream().findFirst().orElse(null);
        }
        if (invoiceId == null && !paymentId.isBlank()) {
            invoiceId = invoiceIdsByPaymentId.stream().findFirst().orElse(null);
        }
        CommonInvoice invoice = invoiceId == null ? null : lockedInvoice(invoiceId).orElse(null);
        CommonInvoicePaymentRef lockedRef = lockedPaymentRef(ref).orElse(ref);
        if (lockedRef != null && Objects.equals(invoiceId, paymentRefInvoiceId(lockedRef))) {
            if (isCurrentPaymentRegistryConstraintViolation(failure)) {
                if (!paymentId.isBlank() && !hasForeignPaymentIdBinding(paymentId, invoiceId, lockedRef.getId())) {
                    lockedRef.setTbankPaymentId(limit(paymentId, 64));
                }
                String terminalKey = normalize(payload == null ? null : payload.get("TerminalKey"));
                if (!terminalKey.isBlank()) {
                    lockedRef.setTbankTerminalKey(limit(terminalKey, 64));
                }
                enrichPaymentRefAmountFromWebhook(lockedRef, payload);
            }
            lockedRef.setStatus(PAYMENT_REF_INIT_CONFLICT);
            lockedRef.setReason(limit((isCurrentPaymentRegistryConstraintViolation(failure) ? "webhook_current_registry_constraint:" : "webhook_identity_constraint:") + paymentId, 160));
            paymentRefRepository.save(lockedRef);
            if (isCurrentPaymentRegistryConstraintViolation(failure) && !normalize(lockedRef.getTbankPaymentId()).isBlank()) {
                entityManager.flush();
            }
        }
        if (invoice != null) {
            if (matchesCurrentPaymentRef(invoice, orderId, paymentId)) {
                clearCurrentPaymentRef(invoice);
            } else {
                invoice.setPaymentUrl(null);
            }
            invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
            invoice.setNextReminderAt(null);
            invoice.setLastError(limit((isCurrentPaymentRegistryConstraintViolation(failure) ? "payment_registry_collision: у общего счета обнаружено несколько активных T-Bank ссылок" : "payment_init_response_collision: PaymentId " + paymentId + " нарушил уникальность durable-реестра") + "; нужна ручная сверка (" + readableException(failure) + ")", 512));
            invoiceRepository.save(invoice);
        }
    }

    List<Long> providerIdentityInvoiceIds(String identityName, String identityValue) {
        if ("OrderId".equals(identityName)) {
            return invoiceRepository.findIdsByTbankOrderId(identityValue);
        }
        if ("PaymentId".equals(identityName)) {
            return invoiceRepository.findIdsByTbankPaymentId(identityValue);
        }
        throw new IllegalArgumentException("Unsupported payment identity: " + identityName);
    }

    boolean isDurablePaymentRegistryConstraintViolation(RuntimeException failure) {
        return isPaymentIdentityConstraintViolation(failure) || isCurrentPaymentRegistryConstraintViolation(failure);
    }

    boolean isCurrentPaymentRegistryConstraintViolation(RuntimeException failure) {
        Throwable current = failure;
        while (current != null) {
            String message = normalize(current.getMessage()).toLowerCase(Locale.ROOT);
            if (message.contains("uk_common_invoice_payment_refs_current_invoice")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    boolean isPaymentIdentityConstraintViolation(RuntimeException failure) {
        Throwable current = failure;
        while (current != null) {
            String message = normalize(current.getMessage()).toLowerCase(Locale.ROOT);
            if (message.contains("uk_common_invoice_payment_ref_payment") || (message.contains("tbank_payment_id") && (message.contains("duplicate") || message.contains("unique")))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    void enrichPaymentRefAmountFromWebhook(CommonInvoicePaymentRef paymentRef, Map<String, String> payload) {
        if (paymentRef == null || paymentRef.getAmountKopecks() != null) {
            return;
        }
        String amount = normalize(payload == null ? null : payload.get("Amount"));
        if (amount.isBlank()) {
            return;
        }
        try {
            long parsed = Long.parseLong(amount);
            if (parsed <= 0) {
                throw new NumberFormatException("amount must be positive");
            }
            paymentRef.setAmountKopecks(parsed);
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректная сумма webhook", e);
        }
    }

    long parseWebhookAmount(Map<String, String> payload) {
        String amount = normalize(payload == null ? null : payload.get("Amount"));
        if (amount.isBlank()) {
            return 0L;
        }
        try {
            long parsed = Long.parseLong(amount);
            if (parsed <= 0) {
                throw new NumberFormatException("amount must be positive");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректная сумма webhook", e);
        }
    }

    void updateCurrentPaymentAnchorFromWebhook(CommonInvoicePaymentRef anchor, String paymentId, String terminalKey, Map<String, String> payload, String targetStatus, String reason) {
        if (anchor == null) {
            return;
        }
        if (!normalize(paymentId).isBlank()) {
            anchor.setTbankPaymentId(limit(paymentId, 64));
        }
        if (!normalize(terminalKey).isBlank()) {
            anchor.setTbankTerminalKey(limit(terminalKey, 64));
        }
        enrichPaymentRefAmountFromWebhook(anchor, payload);
        String currentStatus = normalize(anchor.getStatus()).toUpperCase(Locale.ROOT);
        boolean pendingUpdate = PAYMENT_REF_CURRENT.equals(targetStatus);
        if (!pendingUpdate || currentStatus.isBlank() || PAYMENT_REF_INIT_PREPARED.equals(currentStatus) || PAYMENT_REF_INIT_CONFLICT.equals(currentStatus) || PAYMENT_REF_CURRENT.equals(currentStatus)) {
            anchor.setStatus(limit(targetStatus, 32));
            anchor.setReason(limit(reason, 160));
        }
        paymentRefRepository.save(anchor);
        entityManager.flush();
    }

    boolean isTerminalPaymentWebhook(String status, boolean success, String errorCode) {
        String normalizedStatus = normalize(status).toUpperCase(Locale.ROOT);
        return PAYMENT_REF_CONFIRMED.equals(normalizedStatus) || "REJECTED".equals(normalizedStatus) || PAYMENT_REF_REFUNDED_STATUSES.contains(normalizedStatus) || (!success && !normalize(errorCode).isBlank() && !"0".equals(normalize(errorCode)));
    }

    String durableTerminalWebhookStatus(String status, boolean success, String errorCode) {
        String normalizedStatus = normalize(status).toUpperCase(Locale.ROOT);
        if (PAYMENT_REF_CONFIRMED.equals(normalizedStatus) || "REJECTED".equals(normalizedStatus) || PAYMENT_REF_REFUNDED_STATUSES.contains(normalizedStatus)) {
            return normalizedStatus;
        }
        if (!success && !normalize(errorCode).isBlank() && !"0".equals(normalize(errorCode))) {
            return "REJECTED";
        }
        return normalizedStatus.isBlank() ? "REJECTED" : normalizedStatus;
    }

    void archiveAndClearCurrentPaymentRef(CommonInvoice invoice, String reason) {
        archiveCurrentPaymentRef(invoice, reason);
        clearCurrentPaymentRef(invoice);
    }

    void archiveCurrentPaymentRef(CommonInvoice invoice, String reason) {
        if (invoice == null) {
            return;
        }
        if (normalize(invoice.getTbankOrderId()).isBlank() && normalize(invoice.getTbankPaymentId()).isBlank()) {
            List<CommonInvoicePaymentRef> tochkaRefs = paymentRefRepository.findProviderRefsForUpdate(invoice.getId(), PROVIDER_TOCHKA, Set.of(PAYMENT_REF_INIT_PREPARED, PAYMENT_REF_INIT_CONFLICT, PAYMENT_REF_CURRENT));
            for (CommonInvoicePaymentRef ref : tochkaRefs) {
                // Tochka does not expose cancel for a CREATED payment. Keep the provider attempt
                // unresolved and let GET reconciliation wait for EXPIRED (or refund APPROVED).
                ref.setStatus(PAYMENT_REF_CANCEL_PENDING);
                setTochkaReasonUnlessRefundClaimed(ref, "tochka_archive_pending:" + normalize(reason));
                paymentRefRepository.save(ref);
            }
            if (!tochkaRefs.isEmpty()) {
                invoice.setPaymentUrl(null);
            }
            return;
        }
        Optional<CommonInvoicePaymentRef> existing = lockedPaymentRefByProviderBinding(invoice.getTbankOrderId(), invoice.getTbankPaymentId());
        if (existing.isPresent()) {
            CommonInvoicePaymentRef ref = existing.get();
            if (!Objects.equals(invoice.getId(), paymentRefInvoiceId(ref))) {
                throw invoiceMembershipChanged("T-Bank ссылка принадлежит другому общему счету");
            }
            copyCurrentPaymentBindingToRef(invoice, ref);
            String status = normalize(ref.getStatus());
            if (status.isBlank() || PAYMENT_REF_INIT_PREPARED.equals(status) || PAYMENT_REF_INIT_CONFLICT.equals(status) || PAYMENT_REF_CURRENT.equals(status) || PAYMENT_REF_ARCHIVED.equals(status)) {
                ref.setStatus(canCancelCurrentPaymentRef(invoice) ? PAYMENT_REF_CANCEL_PENDING : PAYMENT_REF_ARCHIVED);
            }
            ref.setReason(limit(reason, 160));
            paymentRefRepository.save(ref);
            flushPaymentRefProviderEvidence(ref);
            return;
        }
        CommonInvoicePaymentRef ref = new CommonInvoicePaymentRef();
        ref.setInvoice(invoice);
        ref.setTbankOrderId(normalize(invoice.getTbankOrderId()).isBlank() ? null : invoice.getTbankOrderId());
        ref.setTbankPaymentId(normalize(invoice.getTbankPaymentId()).isBlank() ? null : invoice.getTbankPaymentId());
        ref.setTbankTerminalKey(normalize(invoice.getTbankTerminalKey()).isBlank() ? null : invoice.getTbankTerminalKey());
        ref.setAmountKopecks(invoice.getTbankPaymentAmountKopecks());
        ref.setStatus(canCancelCurrentPaymentRef(invoice) ? PAYMENT_REF_CANCEL_PENDING : PAYMENT_REF_ARCHIVED);
        ref.setReason(limit(reason, 160));
        paymentRefRepository.save(ref);
        flushPaymentRefProviderEvidence(ref);
    }

    boolean canCancelCurrentPaymentRef(CommonInvoice invoice) {
        return invoice != null && invoice.getStatus() != CommonInvoiceStatus.PAID && !normalize(invoice.getTbankPaymentId()).isBlank() && !normalize(invoice.getTbankTerminalKey()).isBlank() && invoice.getTbankPaymentAmountKopecks() != null && invoice.getTbankPaymentAmountKopecks() > 0;
    }

    Long paymentRefInvoiceId(CommonInvoicePaymentRef ref) {
        return ref == null || ref.getInvoice() == null ? null : ref.getInvoice().getId();
    }

    void recordCurrentPaymentRef(CommonInvoice invoice, String status, String reason) {
        if (invoice == null || (normalize(invoice.getTbankOrderId()).isBlank() && normalize(invoice.getTbankPaymentId()).isBlank())) {
            return;
        }
        Optional<CommonInvoicePaymentRef> existing = lockedPaymentRefByProviderBinding(invoice.getTbankOrderId(), invoice.getTbankPaymentId());
        if (existing.isPresent()) {
            CommonInvoicePaymentRef ref = existing.get();
            if (!Objects.equals(invoice.getId(), paymentRefInvoiceId(ref))) {
                throw invoiceMembershipChanged("T-Bank ссылка принадлежит другому общему счету");
            }
            copyCurrentPaymentBindingToRef(invoice, ref);
            ref.setStatus(limit(status, 32));
            ref.setProviderStatus(limit(status, 32));
            ref.setReason(limit(reason, 160));
            paymentRefRepository.save(ref);
            flushPaymentRefProviderEvidence(ref);
            clearCurrentPaymentRef(invoice);
            return;
        }
        CommonInvoicePaymentRef ref = new CommonInvoicePaymentRef();
        ref.setInvoice(invoice);
        ref.setTbankOrderId(normalize(invoice.getTbankOrderId()).isBlank() ? null : invoice.getTbankOrderId());
        ref.setTbankPaymentId(normalize(invoice.getTbankPaymentId()).isBlank() ? null : invoice.getTbankPaymentId());
        ref.setTbankTerminalKey(normalize(invoice.getTbankTerminalKey()).isBlank() ? null : invoice.getTbankTerminalKey());
        ref.setAmountKopecks(invoice.getTbankPaymentAmountKopecks());
        copyCurrentPaymentBindingToRef(invoice, ref);
        ref.setStatus(limit(status, 32));
        ref.setProviderStatus(limit(status, 32));
        ref.setReason(limit(reason, 160));
        paymentRefRepository.save(ref);
        flushPaymentRefProviderEvidence(ref);
        clearCurrentPaymentRef(invoice);
    }

    void flushPaymentRefProviderEvidence(CommonInvoicePaymentRef ref) {
        if (ref != null && (!normalize(ref.getTbankPaymentId()).isBlank() || !providerPaymentId(ref).isBlank())) {
            entityManager.flush();
        }
    }

    Optional<CommonInvoicePaymentRef> lockedPaymentRefByProviderBinding(String tbankOrderId, String tbankPaymentId) {
        Optional<CommonInvoicePaymentRef> candidate = normalize(tbankOrderId).isBlank() ? Optional.empty() : paymentRefRepository.findByTbankOrderId(tbankOrderId);
        if (candidate.isEmpty() && !normalize(tbankPaymentId).isBlank()) {
            candidate = paymentRefRepository.findByTbankPaymentId(tbankPaymentId);
        }
        if (candidate.isEmpty() || candidate.get().getId() == null) {
            return Optional.empty();
        }
        return paymentRefRepository.findByIdForUpdate(candidate.get().getId());
    }

    Optional<CommonInvoicePaymentRef> findProviderPaymentRefCandidate(String tbankOrderId, String tbankPaymentId) {
        Optional<CommonInvoicePaymentRef> candidate = normalize(tbankOrderId).isBlank() ? Optional.empty() : paymentRefRepository.findByTbankOrderId(tbankOrderId);
        if (candidate.isEmpty() && !normalize(tbankPaymentId).isBlank()) {
            candidate = paymentRefRepository.findByTbankPaymentId(tbankPaymentId);
        }
        return candidate;
    }

    void quarantineMissingCurrentPaymentAnchor(CommonInvoice invoice, String webhookOrderId, String webhookPaymentId) {
        if (invoice == null) {
            return;
        }
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setNextReminderAt(null);
        invoice.setPaymentUrl(null);
        invoice.setLastError(limit("payment_registry_missing: текущая T-Bank ссылка " + paymentRefLabel(webhookOrderId, webhookPaymentId) + " не найдена в durable-реестре; webhook не применен, нужна ручная сверка", 512));
        invoiceRepository.save(invoice);
    }

    void copyCurrentPaymentBindingToRef(CommonInvoice invoice, CommonInvoicePaymentRef ref) {
        if (invoice == null || ref == null) {
            return;
        }
        ref.setProvider(PROVIDER_TBANK);
        if (!normalize(invoice.getTbankOrderId()).isBlank()) {
            ref.setTbankOrderId(limit(invoice.getTbankOrderId(), 36));
            ref.setProviderOrderId(limit(invoice.getTbankOrderId(), 64));
        }
        if (!normalize(invoice.getTbankPaymentId()).isBlank()) {
            ref.setTbankPaymentId(limit(invoice.getTbankPaymentId(), 64));
            ref.setProviderPaymentId(limit(invoice.getTbankPaymentId(), 64));
        }
        if (!normalize(invoice.getTbankTerminalKey()).isBlank()) {
            ref.setTbankTerminalKey(limit(invoice.getTbankTerminalKey(), 64));
            ref.setProviderMerchantId(limit(invoice.getTbankTerminalKey(), 64));
        }
        if (!normalize(invoice.getPaymentUrl()).isBlank()) {
            ref.setProviderPaymentUrl(limit(invoice.getPaymentUrl(), 1024));
        }
        if (invoice.getTbankPaymentAmountKopecks() != null) {
            ref.setAmountKopecks(invoice.getTbankPaymentAmountKopecks());
        }
    }

    boolean canCancelInitializedPaymentRef(CommonInvoicePaymentRef ref) {
        return ref != null && !normalize(ref.getTbankPaymentId()).isBlank() && !normalize(ref.getTbankTerminalKey()).isBlank() && ref.getAmountKopecks() != null && ref.getAmountKopecks() > 0;
    }

    void clearCurrentPaymentRef(CommonInvoice invoice) {
        if (invoice == null) {
            return;
        }
        invoice.setPaymentUrl(null);
        invoice.setTbankOrderId(null);
        invoice.setTbankPaymentId(null);
        invoice.setTbankTerminalKey(null);
        invoice.setTbankPaymentAmountKopecks(null);
        invoice.setTbankPaymentCreatedAt(null);
    }

    VerifiedWebhookProfile verifyWebhook(Map<String, String> payload) {
        String terminalKey = normalize(payload.get("TerminalKey"));
        if (terminalKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "TerminalKey не передан");
        }
        var profile = paymentProfileService.findByTerminalKey(terminalKey).orElseGet(() -> paymentProfileService.defaultEntityProfile());
        TbankPaymentProfile runtimeProfile = paymentProfileService.toRuntimeForTerminal(profile, terminalKey);
        if (!runtimeProfile.hasCredentials()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Не заданы TerminalKey или Password Т-Банка");
        }
        if (!tokenSigner.matches(payload, runtimeProfile.password(), payload.get("Token"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректная подпись уведомления Т-Банка");
        }
        return new VerifiedWebhookProfile(runtimeProfile);
    }

    void validateWebhookTerminal(CommonInvoice invoice, TbankPaymentProfile runtimeProfile) {
        String invoiceTerminal = normalize(invoice.getTbankTerminalKey());
        if (!invoiceTerminal.isBlank() && !invoiceTerminal.equals(runtimeProfile.terminalKey())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "TerminalKey webhook не совпадает с общим счетом");
        }
    }

    boolean matchesCurrentPaymentRef(CommonInvoice invoice, String orderId, String paymentId) {
        String invoiceOrderId = normalize(invoice == null ? null : invoice.getTbankOrderId());
        String invoicePaymentId = normalize(invoice == null ? null : invoice.getTbankPaymentId());
        boolean orderProvided = !normalize(orderId).isBlank();
        boolean paymentProvided = !normalize(paymentId).isBlank();
        boolean orderMatches = orderProvided && !invoiceOrderId.isBlank() && invoiceOrderId.equals(orderId);
        boolean paymentMatches = paymentProvided && !invoicePaymentId.isBlank() && invoicePaymentId.equals(paymentId);
        if (!orderMatches && !paymentMatches) {
            return false;
        }
        if (orderProvided && !invoiceOrderId.isBlank() && !invoiceOrderId.equals(orderId)) {
            return false;
        }
        return !paymentProvided || invoicePaymentId.isBlank() || invoicePaymentId.equals(paymentId);
    }

    void validateArchivedWebhookTerminal(CommonInvoicePaymentRef ref, TbankPaymentProfile runtimeProfile) {
        String refTerminal = normalize(ref.getTbankTerminalKey());
        if (!refTerminal.isBlank() && !refTerminal.equals(runtimeProfile.terminalKey())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "TerminalKey webhook не совпадает с архивной ссылкой общего счета");
        }
    }

    void validateWebhookAmount(CommonInvoice invoice, Map<String, String> payload) {
        String amount = normalize(payload.get("Amount"));
        if (amount.isBlank() || invoice.getTbankPaymentAmountKopecks() == null) {
            return;
        }
        try {
            long webhookAmount = Long.parseLong(amount);
            if (webhookAmount != invoice.getTbankPaymentAmountKopecks()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Сумма webhook не совпадает с общим счетом");
            }
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректная сумма webhook", e);
        }
    }

    void validateArchivedWebhookAmount(CommonInvoicePaymentRef ref, Map<String, String> payload) {
        String amount = normalize(payload.get("Amount"));
        if (amount.isBlank() || ref.getAmountKopecks() == null) {
            return;
        }
        try {
            long webhookAmount = Long.parseLong(amount);
            if (webhookAmount != ref.getAmountKopecks()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Сумма webhook не совпадает с архивной ссылкой общего счета");
            }
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректная сумма webhook", e);
        }
    }

    String paymentRefLabel(CommonInvoicePaymentRef ref) {
        return paymentRefLabel(ref == null ? null : ref.getTbankOrderId(), ref == null ? null : ref.getTbankPaymentId());
    }

    String paymentRefLabel(String tbankOrderId, String tbankPaymentId) {
        String orderId = normalize(tbankOrderId);
        String paymentId = normalize(tbankPaymentId);
        if (!orderId.isBlank() && !paymentId.isBlank()) {
            return orderId + "/" + paymentId;
        }
        return orderId.isBlank() ? paymentId : orderId;
    }

    String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    String limit(String value, int max) {
        String clean = normalize(value);
        return clean.length() <= max ? clean : clean.substring(0, max);
    }

    String readableException(RuntimeException e) {
        if (e == null) {
            return "unknown_error";
        }
        String message = normalize(e.getMessage());
        return message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    record InvoiceOrderBinding(Long invoiceId, Long accountId) {
    }

    record ConfirmedStandaloneApplication(CommonInvoiceOrder item, PaymentLink link, long amountKopecks) {
    }

    record VerifiedWebhookProfile(TbankPaymentProfile runtimeProfile) {
    }

    static class AmountCalculationException extends RuntimeException {

        private AmountCalculationException(Long orderId, RuntimeException cause) {
            super("failed_to_calculate_common_invoice_amount: orderId=" + orderId, cause);
        }
    }
}
