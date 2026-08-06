package com.hunt.otziv.payments.service;

import com.hunt.otziv.bad_reviews.dto.BadReviewTaskSummary;
import com.hunt.otziv.bad_reviews.services.BadReviewTaskService;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.client_messages.service.PaymentInvoiceRetryScheduler;
import com.hunt.otziv.client_messages.service.ScheduledClientMessageService;
import com.hunt.otziv.common_billing.service.CommonBillingService;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.manager.services.ManagerAccessService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.services.service.OrderTransactionService;
import com.hunt.otziv.payments.config.TbankPaymentProperties;
import com.hunt.otziv.payments.dto.AdminPaymentLinkResponse;
import com.hunt.otziv.payments.dto.AdminPaymentLinksPageResponse;
import com.hunt.otziv.payments.dto.AdminPaymentLinkSummaryResponse;
import com.hunt.otziv.payments.dto.ManagerPaymentLinkResponse;
import com.hunt.otziv.payments.dto.PaymentLinkAdminSummary;
import com.hunt.otziv.payments.dto.PaymentLinkArchiveRunResponse;
import com.hunt.otziv.payments.dto.PublicPaymentInitResponse;
import com.hunt.otziv.payments.dto.PublicPaymentLinkResponse;
import com.hunt.otziv.payments.dto.PublicSbpBankResponse;
import com.hunt.otziv.payments.dto.TbankCancelCommand;
import com.hunt.otziv.payments.dto.TbankCancelResponse;
import com.hunt.otziv.payments.dto.TbankGetQrBankListCommand;
import com.hunt.otziv.payments.dto.TbankGetQrBankListResponse;
import com.hunt.otziv.payments.dto.TbankGetQrCommand;
import com.hunt.otziv.payments.dto.TbankGetQrResponse;
import com.hunt.otziv.payments.dto.TbankGetStateResponse;
import com.hunt.otziv.payments.dto.TbankInitCommand;
import com.hunt.otziv.payments.dto.TbankInitResponse;
import com.hunt.otziv.payments.dto.TbankPaymentProfile;
import com.hunt.otziv.payments.model.ManualPaymentSource;
import com.hunt.otziv.payments.model.ManualPaymentTask;
import com.hunt.otziv.payments.model.ManualPaymentType;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.model.PaymentMethod;
import com.hunt.otziv.payments.model.PaymentPolicy;
import com.hunt.otziv.payments.model.PaymentProfile;
import com.hunt.otziv.payments.model.PaymentReceiptStatus;
import com.hunt.otziv.payments.model.TbankPaymentPageMode;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.u_users.model.Manager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.hunt.otziv.logs.LogMasking.maskPaymentId;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentLinkService {

    private static final String PAYMENT_SERVICE_NAME = "Репутационное сопровождение компании в сети Интернет";
    private static final String OFFER_PATH = "/offer";
    private static final String PRIVACY_PATH = "/privacy";
    private static final String RECEIPT_CONSENT_PATH = "/receipt-consent";
    private static final String STATUS_PAYMENT = "Оплачено";
    private static final String MANUAL_PAID_RETIRED_REASON = "Заказ отмечен оплаченным вручную; старая ссылка закрыта";
    private static final String MANUAL_CARD_PAYMENT_AUDIT_PREFIX = "Оплачено переводом на карту после отмены T-Bank";
    private static final String MANUAL_CARD_PAYMENT_EVIDENCE_PREFIX = "Проверен перевод на карту; ожидается закрытие T-Bank";
    private static final String MANUAL_CARD_PAYMENT_PENDING_PREFIX = "manual_card_payment_pending:";
    private static final String MANUAL_CARD_PAYMENT_COMPLETED_PREFIX = "manual_card_payment_completed:";
    private static final String MANUAL_UNPAID_CLOSED_AUDIT_PREFIX = "manual_payment_absent_verified";
    private static final String PREPAID_WAITING_ORDER_COMPLETION = "prepaid_waiting_order_completion";
    private static final Set<PaymentLinkStatus> REUSABLE_STATUSES = Set.of(
            PaymentLinkStatus.CREATED,
            PaymentLinkStatus.INITIATED,
            PaymentLinkStatus.AUTHORIZED,
            PaymentLinkStatus.WAITING_MANUAL_PAYMENT,
            PaymentLinkStatus.MANUAL_REPORTED
    );
    private static final Set<PaymentLinkStatus> RECREATABLE_STALE_STATUSES = Set.of(
            PaymentLinkStatus.CREATED,
            PaymentLinkStatus.INITIATED,
            PaymentLinkStatus.WAITING_MANUAL_PAYMENT
    );
    private static final Set<PaymentLinkStatus> REFUNDABLE_STATUSES = Set.of(
            PaymentLinkStatus.AUTHORIZED,
            PaymentLinkStatus.TEST_CONFIRMED,
            PaymentLinkStatus.CONFIRMED,
            PaymentLinkStatus.AMOUNT_MISMATCH
    );
    private static final Set<PaymentLinkStatus> PAID_STATUSES = Set.of(
            PaymentLinkStatus.AUTHORIZED,
            PaymentLinkStatus.TEST_CONFIRMED,
            PaymentLinkStatus.CONFIRMED,
            PaymentLinkStatus.AMOUNT_MISMATCH
    );
    private static final Set<PaymentLinkStatus> REFUNDED_STATUSES = Set.of(
            PaymentLinkStatus.REVERSED,
            PaymentLinkStatus.PARTIAL_REVERSED,
            PaymentLinkStatus.REFUNDED,
            PaymentLinkStatus.PARTIAL_REFUNDED,
            PaymentLinkStatus.CANCELED
    );
    private static final Set<PaymentLinkStatus> FAILED_STATUSES = Set.of(
            PaymentLinkStatus.REJECTED,
            PaymentLinkStatus.FAILED,
            PaymentLinkStatus.NEEDS_RECONCILIATION,
            PaymentLinkStatus.EXPIRED
    );
    private static final Set<PaymentLinkStatus> REJECTED_STATUSES = Set.of(
            PaymentLinkStatus.REJECTED,
            PaymentLinkStatus.FAILED,
            PaymentLinkStatus.NEEDS_RECONCILIATION
    );
    private static final Set<PaymentLinkStatus> SYNCABLE_BANK_STATUSES = Set.of(
            PaymentLinkStatus.INITIATED,
            PaymentLinkStatus.AUTHORIZED,
            PaymentLinkStatus.NEEDS_RECONCILIATION,
            PaymentLinkStatus.PARTIAL_REVERSED,
            PaymentLinkStatus.PARTIAL_REFUNDED
    );
    private static final Set<PaymentLinkStatus> CONFIRMED_LIKE_BANK_STATUSES = Set.of(
            PaymentLinkStatus.CONFIRMED,
            PaymentLinkStatus.TEST_CONFIRMED,
            PaymentLinkStatus.AMOUNT_MISMATCH
    );
    private static final Set<PaymentLinkStatus> REFUND_OR_REVERSAL_BANK_STATUSES = Set.of(
            PaymentLinkStatus.REVERSED,
            PaymentLinkStatus.PARTIAL_REVERSED,
            PaymentLinkStatus.REFUNDED,
            PaymentLinkStatus.PARTIAL_REFUNDED
    );
    private static final Set<PaymentLinkStatus> ORDER_RECONCILIATION_CANDIDATE_STATUSES = Set.of(
            PaymentLinkStatus.CREATED,
            PaymentLinkStatus.INITIATED,
            PaymentLinkStatus.AUTHORIZED,
            PaymentLinkStatus.WAITING_MANUAL_PAYMENT,
            PaymentLinkStatus.MANUAL_REPORTED,
            PaymentLinkStatus.NEEDS_RECONCILIATION
    );
    private static final Set<PaymentLinkStatus> RECONCILIATION_BLOCKING_STATUSES = Set.of(
            PaymentLinkStatus.NEEDS_RECONCILIATION
    );
    private static final Set<PaymentLinkStatus> MANUAL_USAGE_STATUSES = Set.of(
            PaymentLinkStatus.WAITING_MANUAL_PAYMENT,
            PaymentLinkStatus.MANUAL_REPORTED,
            PaymentLinkStatus.CONFIRMED
    );
    private static final Set<PaymentLinkStatus> MANUAL_PENDING_STATUSES = Set.of(
            PaymentLinkStatus.WAITING_MANUAL_PAYMENT,
            PaymentLinkStatus.MANUAL_REPORTED
    );
    private static final Set<PaymentMethod> MANUAL_METHODS = Set.of(
            PaymentMethod.MANUAL_MOBILE_BANK,
            PaymentMethod.MANUAL_EXTERNAL_LINK
    );
    private static final Set<PaymentMethod> MANUAL_PAYMENT_METHODS = Set.of(
            PaymentMethod.MANUAL_MOBILE_BANK,
            PaymentMethod.MANUAL_EXTERNAL_LINK
    );
    private static final List<String> FEATURED_SBP_BANK_PATTERNS = List.of(
            "сбер",
            "т-банк",
            "t-bank",
            "тинькофф",
            "альфа",
            "втб",
            "газпром",
            "райфф",
            "совком",
            "мтс",
            "ozon",
            "озон",
            "яндекс",
            "псб",
            "промсвяз"
    );
    private static final ZoneId MOSCOW_ZONE = ZoneId.of("Europe/Moscow");
    private static final Duration BANK_INIT_LEASE = Duration.ofMinutes(5);
    private static final String BANK_INIT_AMBIGUOUS_PREFIX = "bank_init_ambiguous:";
    private static final Duration BANK_CANCEL_LEASE = Duration.ofMinutes(5);
    private static final Duration BANK_CANCEL_WATCH = Duration.ofHours(24);
    private static final Duration PUBLIC_BANK_STATE_MIN_INTERVAL = Duration.ofSeconds(10);
    private static final String BANK_CANCEL_IN_PROGRESS_PREFIX = "bank_cancel_in_progress:";
    private static final String BANK_CANCEL_AMBIGUOUS_PREFIX = "bank_cancel_ambiguous:";

    private final PaymentLinkRepository paymentLinkRepository;
    private final OrderRepository orderRepository;
    private final BadReviewTaskService badReviewTaskService;
    private final OrderTransactionService orderTransactionService;
    private final TbankPaymentProperties properties;
    private final TbankRuntimeSettingsService runtimeSettingsService;
    private final PaymentProfileService paymentProfileService;
    private final TbankClient tbankClient;
    private final TbankTokenSigner tokenSigner;
    private final PaymentSuccessNotificationDeliveryService paymentSuccessNotificationDeliveryService;
    private final ManualPaymentTaskService manualPaymentTaskService;
    private final PaymentInvoiceRetryScheduler paymentInvoiceRetryScheduler;
    private final PaymentLinkArchiveService paymentLinkArchiveService;
    private final AppSettingService appSettingService;
    private final ObjectProvider<CommonBillingService> commonBillingServiceProvider;
    private final OrderPaymentIntegrityService orderPaymentIntegrityService;
    private final ManagerAccessService managerAccessService;
    private final PaymentLinkTransactionExecutor transactionExecutor;
    private final SecureRandom secureRandom = new SecureRandom();
    private final ConcurrentMap<String, LocalDateTime> publicBankStateClaims = new ConcurrentHashMap<>();

    @Transactional(noRollbackFor = ResponseStatusException.class)
    public ManagerPaymentLinkResponse createForOrder(Long orderId) {
        requirePaymentLinksEnabled();
        return createForOrder(orderId, null, false);
    }

    /**
     * Manager-facing entry point. The current order row is locked before the
     * object-scope check, so a concurrent reassignment cannot invalidate the
     * authorization between the check and payment-link creation.
     */
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public ManagerPaymentLinkResponse createForOrderAuthorized(
            Long orderId,
            Authentication authentication
    ) {
        return createForOrder(orderId, authentication, true);
    }

    private ManagerPaymentLinkResponse createForOrder(
            Long orderId,
            Authentication authentication,
            boolean requireAuthorization
    ) {
        Optional<Order> lockedOrder = orderRepository.findByIdForCounterUpdate(orderId);
        Order order = (requireAuthorization
                ? lockedOrder
                : lockedOrder.or(() -> orderRepository.findByIdForMutation(orderId)))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ не найден"));

        if (requireAuthorization) {
            managerAccessService.requireOrderAccess(orderId, authentication);
            requirePaymentLinksEnabled();
        }

        ensureOrderNotCoveredByActiveCommonInvoice(orderId);

        LocalDateTime now = LocalDateTime.now();
        expireStaleManualLinks(now);
        // expireManualLinks is a bulk update with clearAutomatically=true. Reload the
        // order after that clear so its lazy status/manager/company proxies remain
        // attached for payment-integrity checks and payment-link construction.
        order = orderRepository.findByIdForCounterUpdate(orderId)
                .or(() -> orderRepository.findByIdForMutation(orderId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ не найден"));
        List<PaymentLink> lockedOrderLinks = paymentLinkRepository.findByOrderIdForUpdate(orderId);
        recoverOrderBankInitReservationsBeforeCreation(lockedOrderLinks, now);
        ensureNoPendingVerifiedManualRouteTransition(lockedOrderLinks);

        orderPaymentIntegrityService.assertPaymentCycleAllowed(order);
        if (paymentLinkRepository.existsByOrder_IdAndStatusIn(orderId, RECONCILIATION_BLOCKING_STATUSES)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Предыдущий платеж уже создан в банке и требует сверки. Новый счет заблокирован."
            );
        }

        long amountKopecks = amountKopecks(payableSum(order));
        if (amountKopecks <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "У заказа нет суммы к оплате");
        }

        Manager manager = orderManager(order);
        PaymentProfile profile = paymentProfileService.selectForManager(manager);
        profile = paymentProfileService.lockForRouting(profile);

        Optional<PaymentLink> existing = paymentLinkRepository
                .findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(orderId, REUSABLE_STATUSES, now);
        if (existing.isPresent()) {
            PaymentLink link = existing.get();
            if (hasCompetingBlockingPayment(link, lockedOrderLinks)) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "У заказа уже есть другой банковский платеж. Проверьте его статус перед продолжением."
                );
            }
            ensurePaymentProfile(link);
            // Once T-Bank has returned a PaymentId, BANK_FORM versus SBP_QR is
            // the customer's choice inside the same public payment link, not a
            // change of the order's configured payment route. Keep returning
            // that immutable provider binding while its amount is current.
            // Creating (or even preparing) a replacement here would either
            // produce a false "old requisites" conflict or risk a duplicate
            // provider payment.
            if (canReuseStartedBankLink(link, amountKopecks)) {
                return toManagerResponse(link);
            }
            PaymentLink candidate = preparedCandidate(order, manager, profile, amountKopecks, now, link.getId());
            if (canReuseLink(link, candidate)) {
                return toManagerResponse(link);
            }

            if (canRetireStaleLink(link)) {
                retireStaleReusableLink(link);
            } else {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "У заказа уже есть платеж в процессе по старым реквизитам или сумме. Проверьте платеж в журнале перед созданием нового счета."
                );
            }
        }

        if (lockedOrderLinks.stream().anyMatch(this::blocksCreationOfAnotherBankPayment)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "У заказа уже есть созданный банковский платеж. Проверьте его статус перед новым счетом."
            );
        }

        PaymentLink link = preparedCandidate(order, manager, profile, amountKopecks, now, null);
        return toManagerResponse(paymentLinkRepository.save(link));
    }

    private void ensureOrderNotCoveredByActiveCommonInvoice(Long orderId) {
        CommonBillingService commonBillingService = commonBillingServiceProvider.getIfAvailable();
        if (commonBillingService == null) {
            return;
        }
        final boolean covered;
        try {
            covered = commonBillingService.isOrderInActiveCommonInvoice(orderId);
        } catch (RuntimeException e) {
            log.warn("Не удалось проверить общий счет заказа {} перед созданием отдельной ссылки", orderId, e);
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Не удалось безопасно проверить общий счет заказа. Отдельная платежная ссылка не создана.",
                    e
            );
        }
        if (covered) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Заказ уже включен в активный общий счет. Используйте единую ссылку общего счета;"
                            + " отдельная ссылка для этого заказа заблокирована."
            );
        }
    }

    private void requirePaymentLinksEnabled() {
        if (!runtimeSettingsService.isPaymentLinksEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Платежные ссылки выключены в настройках");
        }
    }

    @Transactional
    public int expireStaleLinksForOrder(Long orderId) {
        if (orderId == null || orderId <= 0) {
            return 0;
        }
        expireStaleManualLinks(LocalDateTime.now());
        List<PaymentLink> links = paymentLinkRepository.findByOrder_IdAndStatusIn(orderId, REUSABLE_STATUSES);
        int expired = 0;
        for (PaymentLink link : links) {
            if (expireIfAmountChanged(link)) {
                expired++;
            }
        }
        return expired;
    }

    /**
     * Refreshes the bank state of the current payment before an explicit
     * automation retry. A payment that has already finished is applied through
     * the normal bank-status path; an unstarted stale link may be retired. A
     * genuinely active payment is deliberately left untouched so the retry
     * cannot create a duplicate charge.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PaymentLinkReconcileResult reconcileActiveLinkForOrder(Long orderId) {
        if (orderId == null || orderId <= 0) {
            return new PaymentLinkReconcileResult(null, null, null, false);
        }
        LocalDateTime now = LocalDateTime.now();
        Optional<PaymentLink> candidate = paymentLinkRepository
                .findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
                        orderId,
                        ORDER_RECONCILIATION_CANDIDATE_STATUSES,
                        now
                );
        if (candidate.isEmpty()) {
            return new PaymentLinkReconcileResult(null, null, null, false);
        }

        PaymentLink candidateLink = candidate.get();
        Long linkId = candidateLink.getId();
        PaymentLink snapshot = shouldObserveTbankState(candidateLink) && linkId != null
                ? paymentLinkRepository.findByIdWithOrder(linkId).orElse(candidateLink)
                : candidateLink;
        BankStateObservation observation = observeTbankState(snapshot);
        return transactionExecutor.required(() ->
                reconcileActiveLinkLocked(orderId, linkId, observation)
        );
    }

    private PaymentLinkReconcileResult reconcileActiveLinkLocked(
            Long orderId,
            Long linkId,
            BankStateObservation observation
    ) {
        if (orderRepository.findByIdForCounterUpdate(orderId).isEmpty()) {
            return new PaymentLinkReconcileResult(linkId, null, null, false);
        }
        PaymentLink link = linkId == null
                ? null
                : paymentLinkRepository.findByIdForUpdate(linkId).orElse(null);
        if (!hasOrderBinding(link, orderId)) {
            return new PaymentLinkReconcileResult(linkId, null, null, false);
        }

        PaymentLinkStatus before = link.getStatus();
        applyObservedTbankStateIfCurrent(link, observation, orderId);
        expireIfPastDue(link);
        if (REUSABLE_STATUSES.contains(link.getStatus()) && expireIfAmountChanged(link)) {
            link = paymentLinkRepository.findById(link.getId()).orElse(link);
        }
        return new PaymentLinkReconcileResult(
                link.getId(),
                before,
                link.getStatus(),
                before != link.getStatus()
        );
    }

    private PaymentLink preparedCandidate(
            Order order,
            Manager manager,
            PaymentProfile profile,
            long amountKopecks,
            LocalDateTime now,
            Long excludedLinkId
    ) {
        PaymentLink link = newPaymentLink(order, amountKopecks, now);
        applyPaymentProfile(link, profile);
        routePayment(link, manager, profile, amountKopecks, now, excludedLinkId);
        return link;
    }

    private PaymentLink newPaymentLink(Order order, long amountKopecks, LocalDateTime now) {
        PaymentLink link = new PaymentLink();
        link.setToken(newToken());
        link.setOrder(order);
        link.setAmountKopecks(amountKopecks);
        link.setReservedAmountKopecks(amountKopecks);
        link.setDescription(description(order));
        String defaultEmail = defaultPayerEmail(order);
        if (!defaultEmail.isBlank()) {
            link.setPayerEmail(defaultEmail);
        }
        link.setExpiresAt(now.plus(properties.getLinkTtl()));
        return link;
    }

    private void routePayment(
            PaymentLink link,
            Manager manager,
            PaymentProfile profile,
            long amountKopecks,
            LocalDateTime now,
            Long excludedLinkId
    ) {
        Optional<ManualPaymentTask> manualTask = manualPaymentTaskService.findRoutableTask(
                manager,
                profile,
                amountKopecks,
                excludedLinkId
        );
        if (manualTask.isPresent()) {
            applyManualTaskPayment(link, manualTask.get());
        } else if (shouldUseManualPayment(profile, amountKopecks, now, excludedLinkId)) {
            applyManualProfilePayment(link, profile);
        } else {
            link.setStatus(PaymentLinkStatus.CREATED);
            link.setPaymentMethod(PaymentMethod.BANK_FORM);
            link.setManualSource(null);
            link.setManualPaymentTask(null);
            link.setManualPaymentType(null);
            link.setManualPaymentUrl(null);
            link.setManualPaymentButtonLabel(null);
        }
    }

    private boolean canReuseLink(PaymentLink current, PaymentLink candidate) {
        return current.getAmountKopecks() == candidate.getAmountKopecks()
                && current.getReservedAmountKopecks() == candidate.getReservedAmountKopecks()
                && current.getPaymentMethod() == candidate.getPaymentMethod()
                && sameId(current.getPaymentProfile(), candidate.getPaymentProfile())
                && current.getManualSource() == candidate.getManualSource()
                && sameId(current.getManualPaymentTask(), candidate.getManualPaymentTask())
                && current.getManualPaymentType() == candidate.getManualPaymentType()
                && normalize(current.getManualPhone()).equals(normalize(candidate.getManualPhone()))
                && normalize(current.getManualRecipientName()).equals(normalize(candidate.getManualRecipientName()))
                && normalize(current.getManualPaymentUrl()).equals(normalize(candidate.getManualPaymentUrl()))
                && normalize(current.getManualPaymentButtonLabel()).equals(normalize(candidate.getManualPaymentButtonLabel()))
                && normalize(current.getManualComment()).equals(normalize(candidate.getManualComment()));
    }

    private boolean canReuseStartedBankLink(PaymentLink link, long currentAmountKopecks) {
        if (link == null
                || (link.getPaymentMethod() != PaymentMethod.BANK_FORM
                && link.getPaymentMethod() != PaymentMethod.SBP_QR)
                || normalize(link.getTbankPaymentId()).isBlank()
                || hasBankCancelReservation(link)
                || link.getBankCancelOriginStatus() != null
                || link.getAmountKopecks() != currentAmountKopecks) {
            return false;
        }
        Long reservedAmountKopecks = link.getReservedAmountKopecks();
        return reservedAmountKopecks == null || reservedAmountKopecks == currentAmountKopecks;
    }

    private boolean canRetireStaleLink(PaymentLink link) {
        return link != null
                && RECREATABLE_STALE_STATUSES.contains(link.getStatus())
                && !hasStartedBankPayment(link);
    }

    private boolean hasStartedBankPayment(PaymentLink link) {
        if (link == null) {
            return false;
        }
        if (hasBankInitReservation(link)) {
            return true;
        }
        return (link.getPaymentMethod() == PaymentMethod.BANK_FORM || link.getPaymentMethod() == PaymentMethod.SBP_QR)
                && !normalize(link.getTbankPaymentId()).isBlank();
    }

    private boolean hasBankInitReservation(PaymentLink link) {
        return link != null && !normalize(link.getBankInitNonce()).isBlank();
    }

    private boolean sameId(PaymentProfile left, PaymentProfile right) {
        Long leftId = left == null ? null : left.getId();
        Long rightId = right == null ? null : right.getId();
        return leftId == null ? rightId == null : leftId.equals(rightId);
    }

    private boolean sameId(ManualPaymentTask left, ManualPaymentTask right) {
        Long leftId = left == null ? null : left.getId();
        Long rightId = right == null ? null : right.getId();
        return leftId == null ? rightId == null : leftId.equals(rightId);
    }

    private void retireStaleReusableLink(PaymentLink link) {
        link.setStatus(PaymentLinkStatus.EXPIRED);
        link.setLastError("Платежная ссылка пересоздана из-за изменения суммы или маршрута оплаты");
        paymentLinkRepository.save(link);
    }

    private void expireStaleManualLinks(LocalDateTime now) {
        paymentLinkRepository.expireManualLinks(
                MANUAL_PAYMENT_METHODS,
                Set.of(PaymentLinkStatus.WAITING_MANUAL_PAYMENT, PaymentLinkStatus.MANUAL_REPORTED),
                PaymentLinkStatus.EXPIRED,
                "Срок действия ручной платежной ссылки истек",
                now
        );
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PublicPaymentLinkResponse publicLink(String token) {
        PaymentLink snapshot = findPublicLink(token);
        PublicBankStateProbe probe = observePublicTbankState(snapshot);
        Long snapshotOrderId = snapshot.getOrder() == null ? null : snapshot.getOrder().getId();

        PublicLinkRefresh refreshed = transactionExecutor.required(() ->
                refreshPublicLink(token, probe, snapshotOrderId)
        );
        if (!refreshed.replacementRequired() || refreshed.orderId() == null) {
            return refreshed.response();
        }

        Long replacementId = transactionExecutor.required(() ->
                resolveReplacementOrderFirst(refreshed.orderId(), refreshed.linkId())
        );
        if (replacementId == null || replacementId.equals(refreshed.linkId())) {
            return refreshed.response();
        }

        PaymentLink replacementSnapshot = paymentLinkRepository
                .findByIdWithOrder(replacementId)
                .orElse(null);
        if (replacementSnapshot == null) {
            return refreshed.response();
        }
        PublicBankStateProbe replacementProbe = observePublicTbankState(replacementSnapshot);
        PublicPaymentLinkResponse currentReplacement = transactionExecutor.required(() ->
                refreshPublicReplacement(replacementId, replacementProbe)
        );
        return currentReplacement == null ? refreshed.response() : currentReplacement;
    }

    private PublicLinkRefresh refreshPublicLink(
            String token,
            PublicBankStateProbe probe,
            Long snapshotOrderId
    ) {
        BankStateObservation observation = probe.observation();
        Long lockedOrderId = snapshotOrderId == null
                ? lockObservedOrderFirst(observation)
                : orderRepository.findByIdForCounterUpdate(snapshotOrderId).map(Order::getId).orElse(null);
        PaymentLink link = findPublicLinkForUpdateStrict(token);
        markPublicBankStateAttempt(link, probe);
        if (hasOrderBinding(link, lockedOrderId)) {
            recoverExpiredBankInitReservationLocked(link, LocalDateTime.now(), "public_get");
        }
        applyObservedTbankStateIfCurrent(link, observation, lockedOrderId);
        expireIfPastDue(link);
        expireIfAmountChanged(link);
        LocalDateTime now = LocalDateTime.now();
        return new PublicLinkRefresh(
                toPublicResponse(link),
                link.getId(),
                link.getOrder() == null ? null : link.getOrder().getId(),
                shouldResolveReplacementPublicLink(link, now)
        );
    }

    /**
     * Runs only after the old payment-link transaction has committed. The
     * canonical order is therefore the first write lock in the replacement
     * flow, matching manager-side creation and avoiding link/order inversion.
     */
    private Long resolveReplacementOrderFirst(Long orderId, Long sourceLinkId) {
        if (orderId == null || orderId <= 0) {
            return null;
        }
        if (orderRepository.findByIdForCounterUpdate(orderId).isEmpty()) {
            return null;
        }

        LocalDateTime now = LocalDateTime.now();
        Optional<PaymentLink> existing = paymentLinkRepository
                .findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
                        orderId,
                        REUSABLE_STATUSES,
                        now
                )
                .filter(candidate -> candidate.getId() == null || !candidate.getId().equals(sourceLinkId));
        if (existing.isPresent()) {
            return existing.get().getId();
        }
        return createReplacementPublicLink(orderId, now)
                .map(PaymentLink::getId)
                .orElse(null);
    }

    private PublicPaymentLinkResponse refreshPublicReplacement(
            Long linkId,
            PublicBankStateProbe probe
    ) {
        BankStateObservation observation = probe.observation();
        Long lockedOrderId = lockObservedOrderFirst(observation);
        PaymentLink link = paymentLinkRepository.findByIdForUpdate(linkId).orElse(null);
        if (link == null) {
            return null;
        }
        markPublicBankStateAttempt(link, probe);
        applyObservedTbankStateIfCurrent(link, observation, lockedOrderId);
        expireIfPastDue(link);
        expireIfAmountChanged(link);
        return toPublicResponse(link);
    }

    /**
     * Performs the provider request without a surrounding database transaction.
     * Its result is only an observation; all changes are applied after a fresh
     * pessimistic read of the payment-link row.
     */
    private BankStateObservation observeTbankState(PaymentLink link) {
        if (!shouldObserveTbankState(link)) {
            return null;
        }

        return requestTbankState(link);
    }

    private BankStateObservation observeTbankStateForManualCardPayment(PaymentLink link) {
        if (link == null
                || !runtimeSettingsService.isTbankEnabled()
                || (link.getPaymentMethod() != PaymentMethod.BANK_FORM
                    && link.getPaymentMethod() != PaymentMethod.SBP_QR)
                || normalize(link.getTbankPaymentId()).isBlank()) {
            return null;
        }
        return requestTbankState(link);
    }

    private BankStateObservation requestTbankState(PaymentLink link) {
        String paymentId = normalize(link.getTbankPaymentId());
        try {
            PaymentProfile profile = resolvePaymentProfile(link);
            TbankPaymentProfile runtimeProfile = runtimeProfileForLink(profile, link);
            TbankGetStateResponse state = tbankClient.getState(runtimeProfile, paymentId);
            return new BankStateObservation(
                    link.getId(),
                    link.getOrder() == null ? null : link.getOrder().getId(),
                    normalize(link.getToken()),
                    paymentId,
                    normalize(link.getTbankOrderId()),
                    normalize(runtimeProfile.terminalKey()),
                    link.getAmountKopecks(),
                    link.getStatus(),
                    state
            );
        } catch (ResponseStatusException e) {
            log.warn(
                    "T-Bank GetState sync skipped: linkId={}, paymentId={}, status={}, reason={}",
                    link.getId(),
                    maskPaymentId(paymentId),
                    e.getStatusCode(),
                    normalize(e.getReason())
            );
        } catch (RuntimeException e) {
            log.warn(
                    "T-Bank GetState sync failed: linkId={}, paymentId={}",
                    link.getId(),
                    maskPaymentId(paymentId),
                    e
            );
        }
        return null;
    }

    /**
     * A public payment page may be reloaded several times at once (focus,
     * pageshow and a manual refresh). Keep those reads public, but do not turn
     * every reload into another provider request. The durable timestamp also
     * coordinates the public path with scheduled reconciliation; the local
     * atomic claim closes the gap before that timestamp is committed.
     */
    private PublicBankStateProbe observePublicTbankState(PaymentLink link) {
        if (!shouldObserveTbankState(link)) {
            return PublicBankStateProbe.skipped();
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime eligibleBefore = now.minus(PUBLIC_BANK_STATE_MIN_INTERVAL);
        if (link.getBankReconciliationAttemptedAt() != null
                && link.getBankReconciliationAttemptedAt().isAfter(eligibleBefore)) {
            return PublicBankStateProbe.skipped();
        }

        String claimKey = link.getId() == null
                ? "token:" + normalize(link.getToken())
                : "id:" + link.getId();
        AtomicBoolean claimed = new AtomicBoolean(false);
        publicBankStateClaims.compute(claimKey, (ignored, previous) -> {
            if (previous != null && previous.isAfter(eligibleBefore)) {
                return previous;
            }
            claimed.set(true);
            return now;
        });
        if (!claimed.get()) {
            return PublicBankStateProbe.skipped();
        }
        if (publicBankStateClaims.size() > 10_000) {
            publicBankStateClaims.entrySet().removeIf(entry -> !entry.getValue().isAfter(eligibleBefore));
        }
        return new PublicBankStateProbe(true, now, observeTbankState(link));
    }

    private void markPublicBankStateAttempt(PaymentLink link, PublicBankStateProbe probe) {
        if (link != null && probe.attempted() && probe.attemptedAt() != null) {
            link.setBankReconciliationAttemptedAt(probe.attemptedAt());
        }
    }

    private boolean shouldObserveTbankState(PaymentLink link) {
        return link != null
                && runtimeSettingsService.isTbankEnabled()
                && (SYNCABLE_BANK_STATUSES.contains(link.getStatus())
                    || link.getBankCancelOriginStatus() != null)
                && !normalize(link.getTbankPaymentId()).isBlank();
    }

    private void applyObservedTbankStateIfCurrent(
            PaymentLink link,
            BankStateObservation observation,
            Long lockedOrderId
    ) {
        if (link == null
                || observation == null
                || !sameObservedLink(link, observation)
                || lockedOrderId == null
                || !lockedOrderId.equals(observation.orderId())
                || link.getOrder() == null
                || !lockedOrderId.equals(link.getOrder().getId())
                || link.getAmountKopecks() != observation.amountKopecks()
                || !normalize(link.getTbankOrderId()).equals(observation.tbankOrderId())
                || !normalize(link.getTbankPaymentId()).equals(observation.paymentId())) {
            return;
        }

        String incomingStatus = normalize(observation.state().status()).toUpperCase();
        boolean unchangedSnapshot = link.getStatus() == observation.status();
        boolean monotonicRefundProgress = isRefundOrReversalBankStatus(incomingStatus)
                && !shouldIgnoreStaleBankStatus(link, incomingStatus);
        boolean delayedCancelResolution = "CANCELED".equals(incomingStatus)
                && link.getBankCancelOriginStatus() != null;
        if (!unchangedSnapshot && !monotonicRefundProgress && !delayedCancelResolution) {
            return;
        }

        try {
            PaymentProfile profile = resolvePaymentProfile(link);
            TbankPaymentProfile runtimeProfile = runtimeProfileForLink(profile, link);
            if (!normalize(runtimeProfile.terminalKey()).equals(observation.terminalKey())) {
                log.warn(
                        "T-Bank GetState observation ignored after payment profile changed: linkId={}",
                        link.getId()
                );
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
            applyBankStatus(
                    link,
                    incomingStatus,
                    state.success(),
                    normalize(state.errorCode())
            );
            clearResolvedCancelReservation(link, incomingStatus);
            paymentLinkRepository.save(link);
        } catch (ResponseStatusException e) {
            log.warn(
                    "T-Bank GetState observation apply skipped: linkId={}, status={}, reason={}",
                    link.getId(),
                    e.getStatusCode(),
                    normalize(e.getReason())
            );
        } catch (RuntimeException e) {
            log.warn("T-Bank GetState observation apply failed: linkId={}", link.getId(), e);
        }
    }

    private boolean sameObservedLink(PaymentLink link, BankStateObservation observation) {
        if (link.getId() != null && observation.linkId() != null) {
            return link.getId().equals(observation.linkId());
        }
        return normalize(link.getToken()).equals(observation.token());
    }

    private Long lockObservedOrderFirst(BankStateObservation observation) {
        if (observation == null || observation.orderId() == null) {
            return null;
        }
        return orderRepository.findByIdForCounterUpdate(observation.orderId())
                .map(Order::getId)
                .orElse(null);
    }

    private boolean hasOrderBinding(PaymentLink link, Long orderId) {
        return link != null
                && orderId != null
                && link.getOrder() != null
                && orderId.equals(link.getOrder().getId());
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

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<PublicSbpBankResponse> publicSbpBanks(String token, String deviceType, String os) {
        SbpBankListRequest request = transactionExecutor.readOnly(() -> {
            PaymentLink link = resolveReplacementPublicLink(findPublicLink(token), LocalDateTime.now(), false);
            validatePayable(link);
            validateTbankPayment(link);

            PaymentProfile profile = resolvePaymentProfile(link);
            return new SbpBankListRequest(
                    runtimeProfileForLink(profile, link),
                    new TbankGetQrBankListCommand(
                            "qr",
                            cleanDeviceType(deviceType),
                            limit(os, 255)
                    )
            );
        });
        TbankGetQrBankListResponse response = tbankClient.getQrBankList(
                request.runtimeProfile(),
                request.command()
        );

        return response.safeBanks().stream()
                .map(this::toPublicSbpBankResponse)
                .filter(bank -> !bank.bankId().isBlank() && !bank.name().isBlank())
                .sorted(Comparator
                        .comparingInt((PublicSbpBankResponse bank) -> featuredBankRank(bank.name()))
                        .thenComparing(bank -> bank.order() == null ? Integer.MAX_VALUE : bank.order())
                        .thenComparing(PublicSbpBankResponse::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Transactional
    public AdminPaymentLinksPageResponse adminLinks(
            int page,
            int size,
            String statusFilter,
            String search,
            LocalDate from,
            LocalDate to,
            String source
    ) {
        expireStaleManualLinks(LocalDateTime.now());

        int resolvedPage = Math.max(0, page);
        int resolvedSize = Math.max(10, Math.min(size, 100));
        String resolvedFilter = normalizeStatusFilter(statusFilter);
        String resolvedSearch = normalize(search);
        String resolvedSource = normalizeSource(source);
        String searchText = resolvedSearch.isBlank() ? null : "%" + resolvedSearch.toLowerCase(Locale.ROOT) + "%";
        Long searchId = parseLongOrNull(resolvedSearch);
        LocalDateTime fromAt = from == null ? null : from.atStartOfDay();
        LocalDateTime toAt = to == null ? null : to.plusDays(1).atStartOfDay();

        if ("ARCHIVE".equals(resolvedSource)) {
            return paymentLinkArchiveService.archivedLinks(
                    resolvedPage,
                    resolvedSize,
                    resolvedFilter,
                    resolvedSearch,
                    searchId,
                    from,
                    to
            );
        }

        Page<PaymentLink> links = paymentLinkRepository.findAdminPage(
                resolvedFilter,
                searchText,
                searchId,
                fromAt,
                toAt,
                REUSABLE_STATUSES,
                PAID_STATUSES,
                REFUNDED_STATUSES,
                FAILED_STATUSES,
                MANUAL_METHODS,
                PageRequest.of(resolvedPage, resolvedSize)
        );
        PaymentLinkAdminSummary summary = paymentLinkRepository.summarizeAdminPage(
                resolvedFilter,
                searchText,
                searchId,
                fromAt,
                toAt,
                REUSABLE_STATUSES,
                PAID_STATUSES,
                REFUNDED_STATUSES,
                FAILED_STATUSES,
                MANUAL_METHODS,
                MANUAL_PENDING_STATUSES,
                REFUNDABLE_STATUSES,
                REJECTED_STATUSES,
                PaymentReceiptStatus.PENDING,
                LocalDateTime.now().minusHours(24)
        );

        return new AdminPaymentLinksPageResponse(
                links.stream().map(this::toAdminResponse).toList(),
                links.getNumber(),
                links.getSize(),
                links.getTotalElements(),
                links.getTotalPages(),
                resolvedSource,
                toSummaryResponse(summary)
        );
    }

    @Transactional
    public PaymentLinkArchiveRunResponse archiveClosedLinks(boolean dryRun, Integer batchSize) {
        return paymentLinkArchiveService.run(dryRun, batchSize);
    }

    private String normalizeStatusFilter(String statusFilter) {
        String value = normalize(statusFilter).toLowerCase(Locale.ROOT);
        return switch (value) {
            case "active", "paid", "refunded", "failed", "created", "manual" -> value;
            default -> "all";
        };
    }

    private String normalizeSource(String source) {
        String value = normalize(source).toUpperCase(Locale.ROOT);
        return "ARCHIVE".equals(value) ? "ARCHIVE" : "LIVE";
    }

    private Long parseLongOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private AdminPaymentLinkSummaryResponse toSummaryResponse(PaymentLinkAdminSummary summary) {
        PaymentLinkAdminSummary safe = summary == null
                ? new PaymentLinkAdminSummary(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L)
                : summary;
        return new AdminPaymentLinkSummaryResponse(
                safe.safeTotalElements(),
                amountRubles(safe.safeTotalAmountKopecks()),
                safe.safeTotalAmountKopecks(),
                safe.safePaid(),
                safe.safeManualPending(),
                safe.safeConfirmed(),
                safe.safeNotificationsSent(),
                safe.safeNotificationErrors(),
                safe.safeRefundable(),
                safe.safeRefunded(),
                safe.safeRejected(),
                safe.safeReceiptPending(),
                safe.safeReceiptOverdue()
        );
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AdminPaymentLinkResponse cancel(Long linkId) {
        PaymentLink snapshot = paymentLinkRepository.findByIdWithOrder(linkId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежная ссылка не найдена"));
        Long orderId = snapshot.getOrder() == null ? null : snapshot.getOrder().getId();
        CancelReservation reservation = transactionExecutor.requiredNoRollback(() ->
                reserveCancelLocked(linkId, orderId)
        );

        PaymentLinkStatus incoming;
        try {
            TbankCancelResponse response = tbankClient.cancel(
                    reservation.runtimeProfile(),
                    new TbankCancelCommand(reservation.paymentId(), reservation.amountKopecks())
            );
            validateCancelResponse(reservation, response);
            incoming = statusAfterCancel(response.status());
        } catch (RuntimeException failure) {
            recordAmbiguousCancelFailure(reservation, failure);
            log.warn(
                    "T-Bank Cancel outcome is ambiguous: linkId={}, orderId={}, paymentId={}, status={}, reason={}",
                    reservation.linkId(),
                    reservation.orderId(),
                    maskPaymentId(reservation.paymentId()),
                    failure instanceof ResponseStatusException statusException
                            ? statusException.getStatusCode()
                            : HttpStatus.BAD_GATEWAY,
                    providerFailureReason(failure)
            );
            throw failure;
        }
        PaymentLinkStatus observedStatus = incoming;
        return transactionExecutor.requiredNoRollback(() ->
                applyCancelObservation(reservation, observedStatus)
        );
    }

    /**
     * Safely settles an order that was paid by a direct transfer while its
     * T-Bank payment page was still open. The provider state is read first. A
     * NEW payment session is canceled through T-Bank and the order is credited
     * only after an explicit CANCELED response. Any paid, authorized,
     * inconsistent or unknown provider state fails closed.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AdminPaymentLinkResponse confirmPaidByManualCardTransferForOrder(
            Long orderId,
            boolean recipientStatementChecked,
            boolean paymentReceived,
            Long receivedAmountKopecks,
            String note,
            String receiptUrl,
            String actor,
            Authentication authentication
    ) {
        reconcileLegacyTerminalBankRoutesForManualCardPayment(orderId, authentication);
        Long linkId = transactionExecutor.required(() -> {
            if (orderId == null || orderRepository.findByIdForCounterUpdate(orderId).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ не найден");
            }
            managerAccessService.requireOrderAccess(orderId, authentication);
            List<PaymentLink> orderLinks = paymentLinkRepository.findByOrderIdForUpdate(orderId);
            PaymentLink selected = selectManualCardPaymentRoute(orderLinks);
            if (!isCompletedManualCardPayment(selected)) {
                ensureOrderNotCoveredByActiveCommonInvoice(orderId);
                boolean competingPayment = orderLinks.stream()
                        .filter(candidate -> !sameLinkId(selected, candidate))
                        .anyMatch(this::isCompetingManualCardPaymentRoute);
                if (competingPayment) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "У заказа найден другой активный или подтвержденный способ оплаты. "
                                    + "Оплата переводом не зачислена; нужна ручная сверка."
                    );
                }
            }
            return selected.getId();
        });
        return confirmPaidByManualCardTransfer(
                linkId,
                recipientStatementChecked,
                paymentReceived,
                receivedAmountKopecks,
                note,
                receiptUrl,
                actor,
                authentication
        );
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AdminPaymentLinkResponse confirmPaidByManualCardTransfer(
            Long linkId,
            boolean recipientStatementChecked,
            boolean paymentReceived,
            Long receivedAmountKopecks,
            String note,
            String receiptUrl,
            String actor,
            Authentication authentication
    ) {
        String cleanNote = normalize(note);
        String cleanReceiptUrl = normalize(receiptUrl);
        String cleanActor = normalize(actor);
        if (!recipientStatementChecked
                || !paymentReceived
                || receivedAmountKopecks == null
                || receivedAmountKopecks <= 0
                || cleanNote.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Подтвердите проверку выписки получателя, поступление перевода, точную сумму и укажите обязательную заметку"
            );
        }

        PaymentLink initialSnapshot = paymentLinkRepository.findByIdWithOrder(linkId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежная ссылка не найдена"));
        Long orderId = initialSnapshot.getOrder() == null ? null : initialSnapshot.getOrder().getId();
        if (orderId == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ платежной ссылки не найден");
        }
        reconcileLegacyTerminalBankRoutesForManualCardPayment(orderId, authentication);
        PaymentLink snapshot = paymentLinkRepository.findByIdWithOrder(linkId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежная ссылка не найдена"));
        if (!hasOrderBinding(snapshot, orderId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Заказ платежной ссылки изменился во время сверки");
        }
        transactionExecutor.required(() -> {
            if (orderRepository.findByIdForCounterUpdate(orderId).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ платежной ссылки не найден");
            }
            managerAccessService.requireOrderAccess(orderId, authentication);
            return null;
        });
        if (isCompletedManualCardPayment(snapshot)) {
            return toAdminResponse(snapshot);
        }
        ensureOrderNotCoveredByActiveCommonInvoice(orderId);
        if (receivedAmountKopecks != snapshot.getAmountKopecks()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Сумма перевода не равна полной сумме заказа. Оплата не зачислена; проверьте выписку и заказ."
            );
        }

        ManualCardPaymentPlan plan;
        if (isPendingManualCardPayment(snapshot)) {
            plan = manualCardPaymentPlan(snapshot, null);
        } else if (isUnstartedCreatedBankRoute(snapshot)) {
            plan = transactionExecutor.requiredNoRollback(() ->
                    prepareUnstartedManualCardPayment(
                            linkId,
                            orderId,
                            cleanNote,
                            cleanReceiptUrl,
                            cleanActor,
                            authentication
                    )
            );
        } else if (isSafeHistoricalBankRoute(snapshot)) {
            plan = manualCardPaymentPlan(snapshot, null);
            ManualCardPaymentPlan verifiedPlan = plan;
            transactionExecutor.requiredNoRollback(() -> {
                markManualCardPaymentPending(
                        verifiedPlan,
                        cleanNote,
                        cleanReceiptUrl,
                        cleanActor,
                        authentication
                );
                return null;
            });
        } else {
            BankStateObservation observation = observeTbankStateForManualCardPayment(snapshot);
            if (observation == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "Не удалось получить свежий статус платежа в T-Bank. Ручная оплата не зачислена."
                );
            }
            plan = transactionExecutor.requiredNoRollback(() ->
                    prepareManualCardPayment(
                            linkId,
                            orderId,
                            observation,
                            cleanNote,
                            cleanReceiptUrl,
                            cleanActor,
                            authentication
                    )
            );
        }
        CancelReservation reservation = plan.cancelReservation();
        if (reservation != null) {
            PaymentLinkStatus incoming;
            try {
                TbankCancelResponse response = tbankClient.cancel(
                        reservation.runtimeProfile(),
                        new TbankCancelCommand(reservation.paymentId(), reservation.amountKopecks())
                );
                validateCancelResponse(reservation, response);
                incoming = statusAfterCancel(response.status());
            } catch (RuntimeException failure) {
                recordAmbiguousCancelFailure(reservation, failure);
                throw failure;
            }
            PaymentLinkStatus observedStatus = incoming;
            transactionExecutor.requiredNoRollback(() -> {
                applyCancelObservation(reservation, observedStatus);
                if (observedStatus == PaymentLinkStatus.CANCELED) {
                    markManualCardPaymentPending(plan, cleanNote, cleanReceiptUrl, cleanActor, authentication);
                }
                return null;
            });
            if (incoming != PaymentLinkStatus.CANCELED) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "T-Bank не подтвердил простое закрытие неоплаченной сессии. "
                                + "Получен статус " + incoming + "; ручная оплата не зачислена, нужна сверка."
                );
            }
        }

        return transactionExecutor.required(() -> applyManualCardPayment(
                plan,
                cleanNote,
                cleanReceiptUrl,
                cleanActor,
                authentication
        ));
    }

    private void reconcileLegacyTerminalBankRoutesForManualCardPayment(
            Long orderId,
            Authentication authentication
    ) {
        List<Long> legacyLinkIds = transactionExecutor.required(() -> {
            if (orderId == null || orderRepository.findByIdForCounterUpdate(orderId).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ не найден");
            }
            managerAccessService.requireOrderAccess(orderId, authentication);
            return paymentLinkRepository.findByOrderIdForUpdate(orderId).stream()
                    .filter(this::isSelectableTerminalBankRouteForVerification)
                    .filter(link -> !isSafeHistoricalBankRoute(link))
                    .map(PaymentLink::getId)
                    .filter(Objects::nonNull)
                    .toList();
        });

        for (Long legacyLinkId : legacyLinkIds) {
            PaymentLink snapshot = paymentLinkRepository.findByIdWithOrder(legacyLinkId)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Старая T-Bank ссылка изменилась до сверки. Ручная оплата не зачислена."
                    ));
            BankStateObservation observation = observeTbankStateForManualCardPayment(snapshot);
            if (observation == null || observation.state() == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "Не удалось получить свежий статус старой T-Bank ссылки. Ручная оплата не зачислена."
                );
            }
            transactionExecutor.requiredNoRollback(() -> {
                applyLegacyTerminalObservationForManualCardPayment(
                        orderId,
                        legacyLinkId,
                        observation,
                        authentication
                );
                return null;
            });
        }
    }

    private void applyLegacyTerminalObservationForManualCardPayment(
            Long orderId,
            Long linkId,
            BankStateObservation observation,
            Authentication authentication
    ) {
        if (orderRepository.findByIdForCounterUpdate(orderId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ не найден");
        }
        managerAccessService.requireOrderAccess(orderId, authentication);
        PaymentLink link = paymentLinkRepository.findByIdForUpdate(linkId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Старая T-Bank ссылка изменилась до применения сверки."
                ));
        if (!hasOrderBinding(link, orderId)
                || !sameObservedLink(link, observation)
                || observation.orderId() == null
                || !observation.orderId().equals(orderId)
                || link.getStatus() != observation.status()
                || link.getAmountKopecks() != observation.amountKopecks()
                || !normalize(link.getTbankPaymentId()).equals(observation.paymentId())
                || !normalize(link.getTbankOrderId()).equals(observation.tbankOrderId())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Старая T-Bank ссылка изменилась во время сверки. Ручная оплата не зачислена."
            );
        }
        if (isSafeHistoricalBankRoute(link)) {
            return;
        }
        if (!isSelectableTerminalBankRouteForVerification(link)) {
            throw ambiguousManualCardPaymentRoute();
        }

        applyObservedTbankStateIfCurrent(link, observation, orderId);
        if (hasAuthoritativeProviderTerminalStatus(link)) {
            return;
        }

        String providerStatus = normalize(observation.state().status()).toUpperCase(Locale.ROOT);
        throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Старая T-Bank ссылка имеет статус "
                        + (providerStatus.isBlank() ? "UNKNOWN" : providerStatus)
                        + ". Ручная оплата не зачислена: платеж может быть активен, оплачен или требовать возврата."
        );
    }

    private ManualCardPaymentPlan prepareUnstartedManualCardPayment(
            Long linkId,
            Long orderId,
            String note,
            String receiptUrl,
            String actor,
            Authentication authentication
    ) {
        if (orderRepository.findByIdForCounterUpdate(orderId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ платежной ссылки не найден");
        }
        managerAccessService.requireOrderAccess(orderId, authentication);
        ensureOrderNotCoveredByActiveCommonInvoice(orderId);
        PaymentLink link = paymentLinkRepository.findByIdForUpdate(linkId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежная ссылка не найдена"));
        if (!hasOrderBinding(link, orderId) || !isUnstartedCreatedBankRoute(link)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Платежная ссылка изменилась до закрытия. Оплата переводом не зачислена."
            );
        }
        link.setStatus(PaymentLinkStatus.CANCELED);
        link.setManualComment(manualCardPaymentEvidence(note, receiptUrl));
        link.setLastError(limit(
                MANUAL_CARD_PAYMENT_PENDING_PREFIX + " local_route_closed; checked_by="
                        + limit(actor.isBlank() ? "admin" : actor, 80),
                512
        ));
        paymentLinkRepository.save(link);
        return manualCardPaymentPlan(link, null);
    }

    private ManualCardPaymentPlan prepareManualCardPayment(
            Long linkId,
            Long orderId,
            BankStateObservation observation,
            String note,
            String receiptUrl,
            String actor,
            Authentication authentication
    ) {
        Order order = orderRepository.findByIdForCounterUpdate(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ платежной ссылки не найден"));
        managerAccessService.requireOrderAccess(orderId, authentication);
        ensureOrderNotCoveredByActiveCommonInvoice(orderId);
        PaymentLink link = paymentLinkRepository.findByIdForUpdate(linkId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежная ссылка не найдена"));
        if (!hasOrderBinding(link, orderId)
                || !sameObservedLink(link, observation)
                || observation.orderId() == null
                || !observation.orderId().equals(orderId)
                || link.getStatus() != observation.status()
                || link.getAmountKopecks() != observation.amountKopecks()
                || !normalize(link.getTbankPaymentId()).equals(observation.paymentId())
                || !normalize(link.getTbankOrderId()).equals(observation.tbankOrderId())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Платеж изменился во время сверки. Ручная оплата не зачислена; обновите журнал."
            );
        }
        if (link.getPaymentMethod() != PaymentMethod.BANK_FORM
                && link.getPaymentMethod() != PaymentMethod.SBP_QR) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Выбрана не банковская платежная ссылка");
        }
        boolean stableOpenSession = link.getStatus() == PaymentLinkStatus.INITIATED;
        boolean stableSafeTerminalSession = isSafeTerminalBeforeManualCardPayment(link.getStatus());
        if ((!stableOpenSession && !stableSafeTerminalSession)
                || normalize(link.getTbankPaymentId()).isBlank()
                || hasBankInitReservation(link)
                || hasBankCancelReservation(link)
                || link.getBankCancelOriginStatus() != null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Безопасное зачисление доступно только для стабильной открытой T-Bank сессии"
            );
        }

        TbankGetStateResponse state = observation.state();
        String errorCode = normalize(state == null ? null : state.errorCode());
        if (state == null || !state.success() || (!errorCode.isBlank() && !"0".equals(errorCode))) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "T-Bank не подтвердил актуальное состояние платежа. Ручная оплата не зачислена."
            );
        }
        PaymentProfile profile = resolvePaymentProfile(link);
        TbankPaymentProfile runtimeProfile = runtimeProfileForLink(profile, link);
        if (!normalize(runtimeProfile.terminalKey()).equals(observation.terminalKey())
                || !isStateConsistent(link, state, runtimeProfile)) {
            paymentLinkRepository.save(link);
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Реквизиты ответа T-Bank не совпали со ссылкой. Ручная оплата не зачислена."
            );
        }

        String providerStatus = normalize(state.status()).toUpperCase(Locale.ROOT);
        applyObservedTbankStateIfCurrent(link, observation, orderId);
        ManualCardPaymentPlan plan = manualCardPaymentPlan(link, null);
        if ("NEW".equals(providerStatus) && link.getStatus() == PaymentLinkStatus.INITIATED) {
            // This evidence marker is stored before the remote Cancel call. If
            // that call times out but later reconciliation observes CANCELED,
            // a manager retry can safely resume instead of leaving a permanent
            // NEEDS_RECONCILIATION record.
            link.setManualComment(manualCardPaymentEvidence(note, receiptUrl));
            return manualCardPaymentPlan(link, reserveBankCancel(link, orderId));
        }
        if (("CANCELED".equals(providerStatus) && link.getStatus() == PaymentLinkStatus.CANCELED)
                || ("REJECTED".equals(providerStatus) && link.getStatus() == PaymentLinkStatus.REJECTED)
                || ("DEADLINE_EXPIRED".equals(providerStatus) && link.getStatus() == PaymentLinkStatus.EXPIRED)) {
            markManualCardPaymentPending(plan, note, receiptUrl, actor, authentication);
            return plan;
        }

        throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "T-Bank вернул статус " + (providerStatus.isBlank() ? "UNKNOWN" : providerStatus)
                        + ". Банковский платеж может быть активен, оплачен или требовать возврата; "
                        + "ручная оплата не зачислена."
        );
    }

    private void markManualCardPaymentPending(
            ManualCardPaymentPlan plan,
            String note,
            String receiptUrl,
            String actor,
            Authentication authentication
    ) {
        if (orderRepository.findByIdForCounterUpdate(plan.orderId()).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ платежной ссылки не найден");
        }
        managerAccessService.requireOrderAccess(plan.orderId(), authentication);
        PaymentLink link = paymentLinkRepository.findByIdForUpdate(plan.linkId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежная ссылка не найдена"));
        requireManualCardPlanBinding(link, plan);
        if (!isSafeHistoricalBankRoute(link)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "T-Bank сессия не имеет подтвержденного безопасного завершения. Ручная оплата не зачислена."
            );
        }
        link.setManualComment(manualCardPaymentEvidence(note, receiptUrl));
        link.setLastError(limit(
                MANUAL_CARD_PAYMENT_PENDING_PREFIX + " checked_by=" + limit(actor.isBlank() ? "admin" : actor, 80),
                512
        ));
        paymentLinkRepository.save(link);
    }

    private AdminPaymentLinkResponse applyManualCardPayment(
            ManualCardPaymentPlan plan,
            String note,
            String receiptUrl,
            String actor,
            Authentication authentication
    ) {
        Order order = orderRepository.findByIdForCounterUpdate(plan.orderId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ платежной ссылки не найден"));
        managerAccessService.requireOrderAccess(plan.orderId(), authentication);
        ensureOrderNotCoveredByActiveCommonInvoice(plan.orderId());
        PaymentLink link = paymentLinkRepository.findByIdForUpdate(plan.linkId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежная ссылка не найдена"));
        requireManualCardPlanBinding(link, plan);
        if (isCompletedManualCardPayment(link)) {
            return toAdminResponse(link);
        }
        if (!isPendingManualCardPayment(link) || !isSafeTerminalBeforeManualCardPayment(link.getStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Закрытие T-Bank сессии не подтверждено. Ручная оплата не зачислена."
            );
        }
        if (!canApplyOrderPaymentNow(order)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Заказ еще не выполнен полностью; ручную оплату нельзя зачислить"
            );
        }
        if (isAmountChanged(link)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Сумма заказа изменилась после сверки T-Bank. Ручная оплата не зачислена."
            );
        }
        if (orderPaymentIntegrityService.hasSettledPaymentEvidence(order)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Заказ уже имеет признаки оплаты. Автоматическое повторное зачисление заблокировано."
            );
        }

        List<PaymentLink> links = paymentLinkRepository.findByOrderIdForUpdate(plan.orderId());
        boolean competingPayment = links.stream()
                .filter(candidate -> !sameLinkId(link, candidate))
                .anyMatch(this::isCompetingManualCardPaymentRoute);
        if (competingPayment) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "У заказа найден другой активный или подтвержденный способ оплаты. Нужна ручная сверка."
            );
        }

        try {
            handlePaymentStatusWithoutPrematureRepeat(order);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Не удалось зачислить ручную оплату заказа", e);
        }
        LocalDateTime now = LocalDateTime.now();
        PaymentLink manualEvidence = manualCardPaymentEvidenceLink(link, order, note, receiptUrl, actor, now);
        paymentLinkRepository.save(manualEvidence);
        link.setManualComment(manualCardPaymentAudit(note, receiptUrl));
        link.setManualConfirmedAt(null);
        link.setManualConfirmedBy(null);
        link.setConfirmedAmountKopecks(null);
        link.setReceiptStatus(null);
        link.setLastError(limit(
                MANUAL_CARD_PAYMENT_COMPLETED_PREFIX + " evidence_token=" + manualEvidence.getToken(),
                512
        ));
        paymentLinkRepository.save(link);
        closeManualPaymentAutomationAfterCommit(order);
        log.warn(
                "Order paid by manual card transfer after bank-route reconciliation: orderId={}, linkId={}, actor={}",
                plan.orderId(),
                plan.linkId(),
                actor
        );
        return toAdminResponse(link);
    }

    private void requireManualCardPlanBinding(PaymentLink link, ManualCardPaymentPlan plan) {
        if (!hasOrderBinding(link, plan.orderId())
                || !normalize(link.getTbankPaymentId()).equals(plan.paymentId())
                || !normalize(link.getTbankOrderId()).equals(plan.tbankOrderId())
                || link.getAmountKopecks() != plan.amountKopecks()
                || hasBankCancelReservation(link)
                || link.getBankCancelOriginStatus() != null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Платеж изменился после сверки. Ручная оплата не зачислена."
            );
        }
    }

    private ManualCardPaymentPlan manualCardPaymentPlan(PaymentLink link, CancelReservation reservation) {
        if (link == null || link.getOrder() == null || link.getOrder().getId() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ платежной ссылки не найден");
        }
        return new ManualCardPaymentPlan(
                link.getId(),
                link.getOrder().getId(),
                normalize(link.getTbankPaymentId()),
                normalize(link.getTbankOrderId()),
                link.getAmountKopecks(),
                reservation
        );
    }

    private boolean isPendingManualCardPayment(PaymentLink link) {
        return isBankPaymentRoute(link)
                && isSafeTerminalBeforeManualCardPayment(link.getStatus())
                && link.getManualConfirmedAt() == null
                && link.getConfirmedAmountKopecks() == null
                && (normalize(link.getLastError()).startsWith(MANUAL_CARD_PAYMENT_PENDING_PREFIX)
                    || normalize(link.getManualComment()).startsWith(MANUAL_CARD_PAYMENT_EVIDENCE_PREFIX));
    }

    private boolean isCompletedManualCardPayment(PaymentLink link) {
        if (!isBankPaymentRoute(link)
                || !isSafeTerminalBeforeManualCardPayment(link.getStatus())
                || !normalize(link.getLastError()).startsWith(MANUAL_CARD_PAYMENT_COMPLETED_PREFIX)
                || !normalize(link.getManualComment()).startsWith(MANUAL_CARD_PAYMENT_AUDIT_PREFIX)) {
            return false;
        }
        // The order transition and these immutable audit fields are committed
        // in one transaction. The audit marker therefore makes retries
        // idempotent without dereferencing a possibly detached/lazy order.
        return true;
    }

    private PaymentLink selectManualCardPaymentRoute(List<PaymentLink> orderLinks) {
        List<PaymentLink> links = orderLinks == null ? List.of() : orderLinks;
        List<PaymentLink> marked = links.stream()
                .filter(link -> isCompletedManualCardPayment(link) || isPendingManualCardPayment(link))
                .toList();
        if (marked.size() > 1) {
            throw ambiguousManualCardPaymentRoute();
        }

        List<PaymentLink> terminalToVerify = links.stream()
                .filter(this::isSelectableTerminalBankRouteForVerification)
                .filter(link -> !isSafeHistoricalBankRoute(link))
                .toList();
        if (terminalToVerify.size() > 1) {
            throw ambiguousManualCardPaymentRoute();
        }

        boolean unsafeBankState = links.stream().anyMatch(this::isUnsafeManualCardPaymentBankRoute);
        if (unsafeBankState) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "У заказа есть оплаченная, возвратная или неоднозначная T-Bank ссылка. "
                            + "Оплата переводом не зачислена; нужна ручная сверка."
            );
        }

        List<PaymentLink> active = links.stream()
                .filter(this::isActivePublicBankRouteForManualCardPayment)
                .toList();
        int selectedRouteKinds = (marked.isEmpty() ? 0 : 1)
                + (active.isEmpty() ? 0 : 1)
                + (terminalToVerify.isEmpty() ? 0 : 1);
        if (active.size() > 1 || selectedRouteKinds > 1) {
            throw ambiguousManualCardPaymentRoute();
        }
        if (!marked.isEmpty()) {
            return marked.getFirst();
        }
        if (!active.isEmpty()) {
            return active.getFirst();
        }
        if (!terminalToVerify.isEmpty()) {
            return terminalToVerify.getFirst();
        }

        return links.stream()
                .filter(this::isSelectableProviderVerifiedTerminalBankRoute)
                .max(Comparator
                        .comparing(PaymentLink::getUpdatedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(PaymentLink::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(PaymentLink::getId, Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "У заказа нет активной или проверяемой завершенной T-Bank ссылки. "
                                + "Оплата переводом не зачислена; откройте журнал платежей для сверки."
                ));
    }

    private ResponseStatusException ambiguousManualCardPaymentRoute() {
        return new ResponseStatusException(
                HttpStatus.CONFLICT,
                "У заказа найдено несколько активных или неоднозначных T-Bank ссылок. "
                        + "Оплата переводом не зачислена; нужна ручная сверка."
        );
    }

    private boolean isActivePublicBankRouteForManualCardPayment(PaymentLink link) {
        return isBankPaymentRoute(link)
                && !hasBankInitReservation(link)
                && !hasBankCancelReservation(link)
                && link.getBankCancelOriginStatus() == null
                && (link.getStatus() == PaymentLinkStatus.CREATED
                    || link.getStatus() == PaymentLinkStatus.INITIATED);
    }

    private boolean isSelectableProviderVerifiedTerminalBankRoute(PaymentLink link) {
        return isSafeHistoricalBankRoute(link)
                && !normalize(link.getTbankPaymentId()).isBlank();
    }

    private boolean isSelectableTerminalBankRouteForVerification(PaymentLink link) {
        return isBankPaymentRoute(link)
                && isSafeTerminalBeforeManualCardPayment(link.getStatus())
                && !normalize(link.getTbankPaymentId()).isBlank()
                && !hasBankInitReservation(link)
                && !hasBankCancelReservation(link)
                && link.getBankCancelOriginStatus() == null;
    }

    private boolean isSafeHistoricalBankRoute(PaymentLink link) {
        if (!isBankPaymentRoute(link)
                || !isSafeTerminalBeforeManualCardPayment(link.getStatus())
                || hasBankInitReservation(link)
                || hasBankCancelReservation(link)
                || link.getBankCancelOriginStatus() != null) {
            return false;
        }
        if (isPendingManualCardPayment(link) || isCompletedManualCardPayment(link)) {
            return true;
        }
        if (normalize(link.getTbankPaymentId()).isBlank()) {
            return true;
        }
        return hasAuthoritativeProviderTerminalStatus(link);
    }

    private boolean hasAuthoritativeProviderTerminalStatus(PaymentLink link) {
        if (link == null) {
            return false;
        }
        String providerStatus = normalize(link.getProviderTerminalStatus());
        return switch (link.getStatus()) {
            case CANCELED -> "CANCELED".equalsIgnoreCase(providerStatus);
            case REJECTED -> "REJECTED".equalsIgnoreCase(providerStatus);
            case EXPIRED -> "DEADLINE_EXPIRED".equalsIgnoreCase(providerStatus);
            default -> false;
        };
    }

    private boolean isUnsafeManualCardPaymentBankRoute(PaymentLink link) {
        if (!isBankPaymentRoute(link)) {
            return false;
        }
        if (hasBankInitReservation(link)
                || hasBankCancelReservation(link)
                || link.getBankCancelOriginStatus() != null) {
            return true;
        }
        if (isCompletedManualCardPayment(link)
                || isPendingManualCardPayment(link)
                || isActivePublicBankRouteForManualCardPayment(link)
                || isSelectableTerminalBankRouteForVerification(link)
                || isSafeHistoricalBankRoute(link)) {
            return false;
        }
        // Every remaining bank status is paid, refund/reversal-related,
        // reconciliation-only, failed without authoritative proof, or an
        // otherwise unexpected state. All of them stay fail-closed.
        return true;
    }

    private boolean isCompetingManualCardPaymentRoute(PaymentLink link) {
        if (link == null || isSafeHistoricalBankRoute(link)) {
            return false;
        }
        if (isBankPaymentRoute(link)) {
            return true;
        }
        return switch (link.getStatus()) {
            case CANCELED, REJECTED, EXPIRED, FAILED -> false;
            default -> true;
        };
    }

    private boolean isBankPaymentRoute(PaymentLink link) {
        return link != null
                && (link.getPaymentMethod() == PaymentMethod.BANK_FORM
                    || link.getPaymentMethod() == PaymentMethod.SBP_QR);
    }

    private boolean isUnstartedCreatedBankRoute(PaymentLink link) {
        return link != null
                && (link.getPaymentMethod() == PaymentMethod.BANK_FORM
                    || link.getPaymentMethod() == PaymentMethod.SBP_QR)
                && link.getStatus() == PaymentLinkStatus.CREATED
                && normalize(link.getTbankPaymentId()).isBlank()
                && !hasBankInitReservation(link)
                && !hasBankCancelReservation(link)
                && link.getBankCancelOriginStatus() == null;
    }

    private boolean isSafeTerminalBeforeManualCardPayment(PaymentLinkStatus status) {
        return status == PaymentLinkStatus.CANCELED
                || status == PaymentLinkStatus.REJECTED
                || status == PaymentLinkStatus.EXPIRED;
    }

    private String manualCardPaymentAudit(String note, String receiptUrl) {
        String receipt = normalize(receiptUrl);
        return limit(
                MANUAL_CARD_PAYMENT_AUDIT_PREFIX + ": " + normalize(note)
                        + (receipt.isBlank() ? "" : "; документ=" + receipt),
                255
        );
    }

    private String manualCardPaymentEvidence(String note, String receiptUrl) {
        String receipt = normalize(receiptUrl);
        return limit(
                MANUAL_CARD_PAYMENT_EVIDENCE_PREFIX + ": " + normalize(note)
                        + (receipt.isBlank() ? "" : "; документ=" + receipt),
                255
        );
    }

    private PaymentLink manualCardPaymentEvidenceLink(
            PaymentLink bankLink,
            Order order,
            String note,
            String receiptUrl,
            String actor,
            LocalDateTime now
    ) {
        PaymentLink evidence = new PaymentLink();
        evidence.setToken(newToken());
        evidence.setOrder(order);
        evidence.setAmountKopecks(bankLink.getAmountKopecks());
        evidence.setConfirmedAmountKopecks(bankLink.getAmountKopecks());
        evidence.setDescription(limit("Оплата переводом на карту: " + description(order), 140));
        evidence.setStatus(PaymentLinkStatus.CONFIRMED);
        evidence.setPaymentMethod(PaymentMethod.MANUAL_MOBILE_BANK);
        evidence.setManualPaymentType(ManualPaymentType.MOBILE_BANK);
        evidence.setManualComment(manualCardPaymentAudit(note, receiptUrl));
        evidence.setManualConfirmedAt(now);
        evidence.setManualConfirmedBy(limit(actor.isBlank() ? "admin" : actor, 160));
        evidence.setPaidAt(now);
        evidence.setReceiptStatus(PaymentReceiptStatus.PENDING);
        evidence.setPaymentSuccessNotificationRetryEligible(false);
        evidence.setExpiresAt(now.plus(properties.getLinkTtl()));
        return evidence;
    }

    private void closeManualPaymentAutomationAfterCommit(Order order) {
        Long orderId = order == null ? null : order.getId();
        Runnable cleanup = () -> {
            try {
                paymentInvoiceRetryScheduler.cancelPaymentAutomation(
                        orderId,
                        "Заказ оплачен переводом на карту; T-Bank сессия закрыта"
                );
            } catch (RuntimeException e) {
                log.error("Не удалось закрыть платежные расписания после ручной оплаты orderId={}", orderId, e);
            }
        };
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            cleanup.run();
            return;
        }
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status == STATUS_COMMITTED) {
                            cleanup.run();
                        }
                    }
                }
        );
    }

    private void cancelBadReviewAutoBanAfterCommit(Order order, String reason) {
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
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
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
            log.warn(
                    "Пропущена синхронная очистка авто-бана без transaction synchronization orderId={}",
                    orderId
            );
            return;
        }
        cleanup.run();
    }

    private CancelReservation reserveCancelLocked(Long linkId, Long orderId) {
        if (orderId == null || orderRepository.findByIdForCounterUpdate(orderId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Заказ платежной ссылки изменился до возврата");
        }
        PaymentLink link = paymentLinkRepository.findByIdForUpdate(linkId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежная ссылка не найдена"));
        if (!hasOrderBinding(link, orderId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Платежная ссылка сменила заказ до возврата");
        }
        if (hasBankCancelReservation(link)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Предыдущий возврат еще выполняется или требует сверки"
            );
        }
        if (!isRefundable(link)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Платеж не готов к возврату через T-Bank");
        }
        if (hasBankInitReservation(link)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Инициализация платежа еще не завершена");
        }

        return reserveBankCancel(link, orderId);
    }

    private CancelReservation reserveBankCancel(PaymentLink link, Long orderId) {
        PaymentProfile profile = resolvePaymentProfile(link);
        TbankPaymentProfile runtimeProfile = runtimeProfileForLink(profile, link);
        PaymentLinkStatus originalStatus = link.getStatus();
        String nonce = UUID.randomUUID().toString();
        link.setBankCancelNonce(nonce);
        link.setBankCancelLeaseUntil(LocalDateTime.now().plus(BANK_CANCEL_LEASE));
        link.setBankCancelOriginStatus(originalStatus);
        link.setBankCancelOriginError(link.getLastError());
        link.setStatus(PaymentLinkStatus.NEEDS_RECONCILIATION);
        link.setBankReconciliationAttemptedAt(null);
        link.setLastError(limit(
                BANK_CANCEL_IN_PROGRESS_PREFIX + " previous_status=" + originalStatus.name(),
                512
        ));
        paymentLinkRepository.save(link);
        return new CancelReservation(
                link.getId(),
                orderId,
                originalStatus,
                nonce,
                normalize(link.getTbankPaymentId()),
                normalize(link.getTbankOrderId()),
                link.getAmountKopecks(),
                normalize(link.getTbankTerminalKey()),
                runtimeProfile
        );
    }

    private AdminPaymentLinkResponse applyCancelObservation(
            CancelReservation reservation,
            PaymentLinkStatus incoming
    ) {
        PaymentLink link = lockCancelBinding(reservation);
        if (link == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Состояние платежа изменилось во время возврата; требуется повторная сверка"
            );
        }

        String currentNonce = normalize(link.getBankCancelNonce());
        boolean ownsReservation = reservation.nonce().equals(currentNonce);
        if (!ownsReservation) {
            if (!currentNonce.isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Начат другой возврат; ответ предыдущего запроса не применен"
                );
            }
            if (link.getStatus() == incoming || link.getStatus() == PaymentLinkStatus.CANCELED) {
                if (incoming == PaymentLinkStatus.CANCELED
                        && link.getStatus() == PaymentLinkStatus.CANCELED) {
                    link.setProviderTerminalStatus("CANCELED");
                    paymentLinkRepository.save(link);
                }
                return toAdminResponse(link);
            }
            if (REFUND_OR_REVERSAL_BANK_STATUSES.contains(link.getStatus())) {
                if (incoming == PaymentLinkStatus.CANCELED
                        || !isAllowedRefundProgress(link.getStatus(), incoming.name())) {
                    return toAdminResponse(link);
                }
            }
            boolean delayedCanceledResolution = incoming == PaymentLinkStatus.CANCELED
                    && link.getBankCancelOriginStatus() != null;
            if (!REFUND_OR_REVERSAL_BANK_STATUSES.contains(incoming)
                    && !delayedCanceledResolution) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Ответ возврата устарел; сохранено более новое состояние банка"
                );
            }
        }

        PaymentLinkStatus current = link.getStatus() == PaymentLinkStatus.NEEDS_RECONCILIATION
                ? reservation.status()
                : link.getStatus();
        PaymentLinkStatus merged = mergeCancelObservation(
                reservation.status(),
                current,
                incoming
        );
        if (merged == null) {
            quarantineAmbiguousCancel(link, "payment_state_changed_before_cancel_result");
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Статус платежа изменился во время возврата; платеж оставлен на сверке"
            );
        }
        link.setStatus(merged);
        if (merged == PaymentLinkStatus.CANCELED && incoming == PaymentLinkStatus.CANCELED) {
            link.setProviderTerminalStatus("CANCELED");
        }
        clearBankCancelContext(link);
        link.setBankReconciliationAttemptedAt(null);
        link.setLastError(null);
        paymentLinkRepository.save(link);
        return toAdminResponse(link);
    }

    private PaymentLink lockCancelBinding(CancelReservation reservation) {
        if (reservation.orderId() == null
                || orderRepository.findByIdForCounterUpdate(reservation.orderId()).isEmpty()) {
            return null;
        }
        PaymentLink link = paymentLinkRepository.findByIdForUpdate(reservation.linkId()).orElse(null);
        if (!hasOrderBinding(link, reservation.orderId())
                || !normalize(link.getTbankPaymentId()).equals(reservation.paymentId())
                || !normalize(link.getTbankOrderId()).equals(reservation.tbankOrderId())
                || link.getAmountKopecks() != reservation.amountKopecks()
                || !normalize(link.getTbankTerminalKey()).equals(reservation.terminalKey())) {
            return null;
        }
        return link;
    }

    private void recordAmbiguousCancelFailure(CancelReservation reservation, RuntimeException failure) {
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

    private void clearBankCancelContext(PaymentLink link) {
        clearBankCancelAttempt(link);
        link.setBankCancelOriginStatus(null);
        link.setBankCancelOriginError(null);
    }

    private boolean hasBankCancelReservation(PaymentLink link) {
        return link != null && !normalize(link.getBankCancelNonce()).isBlank();
    }

    /**
     * Merges an explicit Cancel result with webhooks that arrived while the
     * provider request was in flight. A final refund may advance a concurrent
     * confirmation/partial refund, while a delayed less-specific result never
     * overwrites a more advanced refund state.
     */
    private PaymentLinkStatus mergeCancelObservation(
            PaymentLinkStatus snapshot,
            PaymentLinkStatus current,
            PaymentLinkStatus incoming
    ) {
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
        if (CONFIRMED_LIKE_BANK_STATUSES.contains(current)
                && REFUND_OR_REVERSAL_BANK_STATUSES.contains(incoming)) {
            return incoming;
        }
        if (current == PaymentLinkStatus.CANCELED) {
            return current;
        }
        return null;
    }

    /**
     * Recovery for the intentionally conservative no-PaymentId quarantine.
     * The endpoint requires an explicit administrator assertion that the
     * stable T-Bank OrderId was checked and no payment exists.
     */
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public AdminPaymentLinkResponse releaseAmbiguousBankInit(
            Long linkId,
            boolean bankPaymentAbsent,
            String note,
            String actor
    ) {
        PaymentLink snapshot = paymentLinkRepository.findByIdWithOrder(linkId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежная ссылка не найдена"));
        Long orderId = snapshot.getOrder() == null ? null : snapshot.getOrder().getId();
        if (orderId == null || orderRepository.findByIdForCounterUpdate(orderId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ платежной ссылки не найден");
        }
        PaymentLink link = paymentLinkRepository.findByIdForUpdate(linkId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежная ссылка не найдена"));
        String cleanNote = normalize(note);
        if (!hasOrderBinding(link, orderId)
                || !bankPaymentAbsent
                || cleanNote.isBlank()
                || link.getStatus() != PaymentLinkStatus.NEEDS_RECONCILIATION
                || !normalize(link.getTbankPaymentId()).isBlank()
                || !normalize(link.getLastError()).startsWith(BANK_INIT_AMBIGUOUS_PREFIX)
                || (!normalize(link.getBankInitNonce()).isBlank()
                    && link.getBankInitLeaseUntil() != null
                    && link.getBankInitLeaseUntil().isAfter(LocalDateTime.now()))) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Снять блокировку можно только после подтвержденной сверки неоднозначного Init без PaymentId"
            );
        }

        String previousOrderId = normalize(link.getTbankOrderId());
        clearBankInitReservation(link);
        link.setTbankOrderId(null);
        link.setPaymentUrl(null);
        link.setStatus(PaymentLinkStatus.CREATED);
        link.setInitiatedAt(null);
        link.setLastError(limit(
                "bank_init_released_by=" + normalize(actor)
                        + "; checked_order_id=" + previousOrderId
                        + "; note=" + cleanNote,
                512
        ));
        paymentLinkRepository.save(link);
        log.warn(
                "Ambiguous T-Bank Init released after operator verification: linkId={}, orderId={}, actor={}",
                linkId,
                orderId,
                normalize(actor)
        );
        return toAdminResponse(link);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AdminPaymentLinkResponse confirmManual(Long linkId, String confirmedBy) {
        PaymentLink snapshot = paymentLinkRepository.findByIdWithOrder(linkId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежная ссылка не найдена"));
        Long orderId = snapshot.getOrder() == null ? null : snapshot.getOrder().getId();
        transactionExecutor.required(() -> {
            confirmManualLocked(linkId, orderId, confirmedBy);
            return null;
        });
        // afterCommit delivery has completed (or durably remained retryable)
        // before the executor returns, so preserve the previous response
        // contract by reading the final notification fields.
        return paymentLinkRepository.findByIdWithOrder(linkId)
                .map(this::toAdminResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежная ссылка не найдена"));
    }

    /**
     * Retires only the selected manual payment instruction after an operator
     * has checked the recipient statement and explicitly asserted that the
     * transfer is absent. This operation deliberately does not mutate the
     * order status and does not apply any payment to a common invoice.
     */
    @Transactional
    public AdminPaymentLinkResponse closeManualAsUnpaid(
            Long linkId,
            boolean recipientStatementChecked,
            boolean paymentAbsent,
            String note,
            String actor,
            Authentication authentication
    ) {
        String cleanNote = normalize(note);
        String cleanActor = normalize(actor);
        if (!recipientStatementChecked || !paymentAbsent || cleanNote.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Подтвердите проверку выписки получателя, отсутствие перевода и укажите обязательную заметку"
            );
        }

        PaymentLink snapshot = paymentLinkRepository.findByIdWithOrder(linkId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежная ссылка не найдена"));
        Long orderId = snapshot.getOrder() == null ? null : snapshot.getOrder().getId();
        if (orderId == null || orderRepository.findByIdForCounterUpdate(orderId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ платежной ссылки не найден");
        }
        managerAccessService.requireOrderAccess(orderId, authentication);
        PaymentLink link = paymentLinkRepository.findByIdForUpdate(linkId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежная ссылка не найдена"));
        if (!hasOrderBinding(link, orderId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Заказ платежной ссылки изменился");
        }
        ensureManualPayment(link);
        validateManualUnpaidClosable(link);

        link.setStatus(PaymentLinkStatus.CANCELED);
        link.setLastError(limit(
                MANUAL_UNPAID_CLOSED_AUDIT_PREFIX
                        + ": перевод не поступил; checked_by=" + limit(cleanActor.isBlank() ? "admin" : cleanActor, 160)
                        + "; note=" + cleanNote,
                512
        ));
        paymentLinkRepository.save(link);
        log.warn(
                "Manual payment instruction closed after recipient statement verification: linkId={}, orderId={}, actor={}",
                linkId,
                orderId,
                cleanActor
        );
        return toAdminResponse(link);
    }

    private void confirmManualLocked(Long linkId, Long orderId, String confirmedBy) {
        if (orderId == null || orderRepository.findByIdForCounterUpdate(orderId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ платежной ссылки не найден");
        }
        PaymentLink link = paymentLinkRepository.findByIdForUpdate(linkId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежная ссылка не найдена"));
        if (!hasOrderBinding(link, orderId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Заказ платежной ссылки изменился");
        }
        ensureManualPayment(link);
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
            boolean updated = handlePaymentStatusWithoutPrematureRepeat(link.getOrder());
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
        PaymentLink link = findLinkByIdForUpdate(linkId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежная ссылка не найдена"));
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
        PaymentLink link = findLinkByIdForUpdate(linkId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежная ссылка не найдена"));
        ensureManualPayment(link);
        if (link.getStatus() != PaymentLinkStatus.CONFIRMED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Статус без чека доступен только для подтвержденной ручной оплаты");
        }
        if (link.getPaidAt() == null || link.getPaidAt().isAfter(LocalDateTime.now().minusDays(30))) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Без чека можно закрыть только старую ручную оплату старше 30 дней"
            );
        }
        link.setReceiptStatus(PaymentReceiptStatus.LEGACY_NOT_REQUIRED);
        if (normalize(link.getManualConfirmedBy()).isBlank()) {
            link.setManualConfirmedBy(limit(confirmedBy, 160));
        }
        link.setLastError(null);
        paymentLinkRepository.save(link);
        return toAdminResponse(link);
    }

    @Transactional(noRollbackFor = ResponseStatusException.class)
    public PublicPaymentLinkResponse reportManualPayment(String token) {
        PaymentLink link = findPublicLinkForUpdateStrict(token);
        validatePayable(link);
        ensureManualPayment(link);
        validateManualPaymentTargetAvailable(link);
        if (link.getStatus() == PaymentLinkStatus.WAITING_MANUAL_PAYMENT) {
            LocalDateTime now = LocalDateTime.now();
            link.setStatus(PaymentLinkStatus.MANUAL_REPORTED);
            link.setManualReportedAt(now);
            if (link.getInitiatedAt() == null) {
                link.setInitiatedAt(now);
            }
            link.setLastError(null);
            paymentLinkRepository.save(link);
        }
        return toPublicResponse(link);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PublicPaymentInitResponse init(
            String token,
            String email,
            boolean offerConsent,
            boolean privacyConsent,
            boolean receiptConsent,
            String clientIp,
            String userAgent
    ) {
        String cleanEmail = normalizeEmail(email);
        if (cleanEmail.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Укажите e-mail для электронного чека");
        }
        BankInitReservation reservation = reserveBankInitialization(
                token,
                cleanEmail,
                offerConsent,
                privacyConsent,
                receiptConsent,
                null,
                clientIp,
                userAgent,
                BankInitMode.BANK_FORM
        );
        if (reservation.cachedResponse() != null) {
            return reservation.cachedResponse();
        }

        TbankInitResponse response;
        try {
            response = tbankClient.init(reservation.runtimeProfile(), new TbankInitCommand(
                    reservation.tbankOrderId(),
                    reservation.amountKopecks(),
                    reservation.description(),
                    reservation.email(),
                    properties.notificationUrl(),
                    properties.successUrl(),
                    properties.failUrl(),
                    OffsetDateTime.now(MOSCOW_ZONE).plus(properties.getRedirectDue())
            ));
        } catch (RuntimeException e) {
            recordAmbiguousBankInitFailure(reservation, e);
            log.warn(
                    "T-Bank Init failed: linkId={}, orderId={}, profile={}, terminal={}, status={}, reason={}",
                    reservation.linkId(),
                    reservation.orderId(),
                    reservation.runtimeProfile().code(),
                    reservation.terminalKey(),
                    e instanceof ResponseStatusException statusException
                            ? statusException.getStatusCode()
                            : HttpStatus.BAD_GATEWAY,
                    providerFailureReason(e)
            );
            throw e;
        }
        return requireSuccessfulBankInit(transactionExecutor.required(() ->
                applyBankInitResponse(reservation, response, false)
        ));
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PublicPaymentInitResponse initSbp(
            String token,
            String email,
            boolean offerConsent,
            boolean privacyConsent,
            boolean receiptConsent,
            String sbpBankId,
            String clientIp,
            String userAgent
    ) {
        String cleanEmail = normalizeEmail(email);
        if (cleanEmail.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Укажите e-mail для электронного чека");
        }
        String cleanBankId = normalize(sbpBankId);
        BankInitReservation reservation = reserveBankInitialization(
                token,
                cleanEmail,
                offerConsent,
                privacyConsent,
                receiptConsent,
                cleanBankId,
                clientIp,
                userAgent,
                BankInitMode.SBP_QR
        );
        if (reservation.cachedResponse() != null) {
            return reservation.cachedResponse();
        }

        String paymentId = reservation.paymentId();
        String paymentUrl = reservation.paymentUrl();
        if (paymentId.isBlank()) {
            TbankInitResponse response;
            try {
                response = tbankClient.init(reservation.runtimeProfile(), new TbankInitCommand(
                        reservation.tbankOrderId(),
                        reservation.amountKopecks(),
                        reservation.description(),
                        reservation.email(),
                        properties.notificationUrl(),
                        properties.successUrl(),
                        properties.failUrl(),
                        OffsetDateTime.now(MOSCOW_ZONE).plus(properties.getRedirectDue())
                ));
            } catch (RuntimeException e) {
                recordAmbiguousBankInitFailure(reservation, e);
                log.warn(
                        "T-Bank Init before SBP payload failed: linkId={}, orderId={}, profile={}, terminal={}, status={}, reason={}",
                        reservation.linkId(),
                        reservation.orderId(),
                        reservation.runtimeProfile().code(),
                        reservation.terminalKey(),
                        e instanceof ResponseStatusException statusException
                                ? statusException.getStatusCode()
                                : HttpStatus.BAD_GATEWAY,
                        providerFailureReason(e)
                );
                throw e;
            }
            BankInitApplyResult initResult = transactionExecutor.required(() ->
                    applyBankInitResponse(reservation, response, true)
            );
            requireSuccessfulBankInit(initResult);
            paymentId = initResult.paymentId();
            paymentUrl = initResult.paymentUrl();
        }

        TbankGetQrResponse qrResponse;
        try {
            qrResponse = tbankClient.getQr(reservation.runtimeProfile(), new TbankGetQrCommand(
                    paymentId,
                    "PAYLOAD",
                    cleanBankId.isBlank() ? null : cleanBankId
            ));
        } catch (RuntimeException e) {
            recordQrFailure(reservation, paymentId, e);
            log.warn(
                    "T-Bank GetQr failed: linkId={}, orderId={}, paymentId={}, profile={}, terminal={}, status={}, reason={}",
                    reservation.linkId(),
                    reservation.orderId(),
                    maskPaymentId(paymentId),
                    reservation.runtimeProfile().code(),
                    reservation.terminalKey(),
                    e instanceof ResponseStatusException statusException
                            ? statusException.getStatusCode()
                            : HttpStatus.BAD_GATEWAY,
                    providerFailureReason(e)
            );
            throw e;
        }
        String finalPaymentId = paymentId;
        String finalPaymentUrl = paymentUrl;
        return requireSuccessfulBankInit(transactionExecutor.required(() ->
                applyQrResponse(reservation, finalPaymentId, finalPaymentUrl, qrResponse)
        ));
    }

    private BankInitReservation reserveBankInitialization(
            String token,
            String email,
            boolean offerConsent,
            boolean privacyConsent,
            boolean receiptConsent,
            String bankId,
            String clientIp,
            String userAgent,
            BankInitMode mode
    ) {
        validateConsents(offerConsent, privacyConsent, receiptConsent);
        PaymentLink snapshot = findPublicLink(token);
        Long observedOrderId = snapshot.getOrder() == null ? null : snapshot.getOrder().getId();
        return transactionExecutor.requiredNoRollback(() -> reserveBankInitializationLocked(
                observedOrderId,
                token,
                email,
                bankId,
                clientIp,
                userAgent,
                mode
        ));
    }

    private BankInitReservation reserveBankInitializationLocked(
            Long observedOrderId,
            String token,
            String email,
            String bankId,
            String clientIp,
            String userAgent,
            BankInitMode mode
    ) {
        if (observedOrderId == null || orderRepository.findByIdForCounterUpdate(observedOrderId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ платежной ссылки не найден");
        }

        PaymentLink source = findPublicLinkForUpdate(token);
        if (!hasOrderBinding(source, observedOrderId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Платежная ссылка изменилась; повторите запрос");
        }
        PaymentLink resolved = resolveReplacementPublicLink(source, LocalDateTime.now(), true);
        PaymentLink link = sameLinkId(source, resolved)
                ? source
                : lockResolvedPublicLink(resolved);
        if (!hasOrderBinding(link, observedOrderId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Платежная ссылка изменилась; повторите запрос");
        }

        LocalDateTime now = LocalDateTime.now();
        ensureNoCompetingBankPaymentForInit(
                link,
                paymentLinkRepository.findByOrderIdForUpdate(observedOrderId),
                now
        );
        handleExistingBankInitReservationBeforePayableValidation(link, now);
        validatePayable(link);
        validateTbankPayment(link);
        PaymentProfile profile = ensurePaymentProfile(link);
        TbankPaymentProfile runtimeProfile = normalize(link.getTbankPaymentId()).isBlank()
                ? paymentProfileService.toRuntime(profile)
                : runtimeProfileForLink(profile, link);

        rejectUnsafeCachedBankTargets(link, mode);
        PublicPaymentInitResponse cached = cachedBankInitResponse(link, bankId, clientIp, userAgent, mode);
        if (cached != null) {
            return bankInitReservation(link, runtimeProfile, email, bankId, mode, null, cached);
        }

        link.setPayerEmail(email);
        applyConsentTrace(link, clientIp, userAgent);
        if (normalize(link.getTbankOrderId()).isBlank()) {
            link.setTbankOrderId(tbankOrderId(link));
        }
        link.setTbankTerminalKey(runtimeProfile.terminalKey());
        String nonce = UUID.randomUUID().toString();
        link.setBankInitNonce(nonce);
        link.setBankInitLeaseUntil(now.plus(BANK_INIT_LEASE));
        paymentLinkRepository.save(link);
        return bankInitReservation(link, runtimeProfile, email, bankId, mode, nonce, null);
    }

    private void handleExistingBankInitReservationBeforePayableValidation(
            PaymentLink link,
            LocalDateTime now
    ) {
        BankInitReservationRecovery recovery = recoverExpiredBankInitReservationLocked(
                link,
                now,
                "public_retry"
        );
        if (recovery == BankInitReservationRecovery.ACTIVE) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Инициализация платежа уже выполняется. Повторите запрос через несколько секунд."
            );
        }
        if (recovery == BankInitReservationRecovery.QUARANTINED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Результат предыдущей инициализации неизвестен. Платеж требует сверки администратором."
            );
        }
    }

    private void recoverOrderBankInitReservationsBeforeCreation(
            List<PaymentLink> orderLinks,
            LocalDateTime now
    ) {
        boolean quarantined = false;
        for (PaymentLink link : orderLinks == null ? List.<PaymentLink>of() : orderLinks) {
            BankInitReservationRecovery recovery = recoverExpiredBankInitReservationLocked(
                    link,
                    now,
                    "manager_create"
            );
            if (recovery == BankInitReservationRecovery.ACTIVE) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Инициализация предыдущего платежа еще выполняется. Новый счет заблокирован."
                );
            }
            quarantined |= recovery == BankInitReservationRecovery.QUARANTINED;
        }
        if (quarantined) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Предыдущая инициализация платежа требует сверки. Новый счет заблокирован."
            );
        }
    }

    private void ensureNoCompetingBankPaymentForInit(
            PaymentLink current,
            List<PaymentLink> orderLinks,
            LocalDateTime now
    ) {
        boolean quarantined = false;
        List<PaymentLink> safeLinks = orderLinks == null ? List.of() : orderLinks;
        for (PaymentLink link : safeLinks) {
            BankInitReservationRecovery recovery = recoverExpiredBankInitReservationLocked(
                    link,
                    now,
                    "public_order_guard"
            );
            if (recovery == BankInitReservationRecovery.ACTIVE) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Инициализация платежа по заказу уже выполняется"
                );
            }
            quarantined |= recovery == BankInitReservationRecovery.QUARANTINED;
        }
        if (quarantined) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Предыдущий платеж по заказу требует сверки администратором"
            );
        }
        boolean competingPayment = safeLinks.stream()
                .filter(link -> !sameLinkId(current, link))
                .anyMatch(this::blocksCreationOfAnotherBankPayment);
        if (competingPayment) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "По заказу уже существует другой банковский платеж"
            );
        }
    }

    private BankInitReservationRecovery recoverExpiredBankInitReservationLocked(
            PaymentLink link,
            LocalDateTime now,
            String source
    ) {
        if (!hasBankInitReservation(link)) {
            return BankInitReservationRecovery.NONE;
        }
        if (link.getBankInitLeaseUntil() != null && link.getBankInitLeaseUntil().isAfter(now)) {
            return BankInitReservationRecovery.ACTIVE;
        }

        boolean missingPaymentId = normalize(link.getTbankPaymentId()).isBlank();
        boolean expired = link.getExpiresAt() != null && link.getExpiresAt().isBefore(now);
        boolean amountChanged = isAmountChanged(link);
        if (missingPaymentId || expired || amountChanged) {
            String reason = missingPaymentId
                    ? "reservation_expired_without_provider_result"
                    : expired
                            ? "reservation_expired_after_link_deadline"
                            : "reservation_expired_after_amount_change";
            quarantineAmbiguousBankInit(
                    link,
                    link.getTbankPaymentId(),
                    reason + "; source=" + normalize(source)
            );
            return BankInitReservationRecovery.QUARANTINED;
        }

        // Init already supplied a PaymentId and only a follow-up operation
        // (for example GetQr) was interrupted. Retrying that follow-up on the
        // same immutable payment binding is safe.
        clearBankInitReservation(link);
        paymentLinkRepository.save(link);
        return BankInitReservationRecovery.RETRYABLE_RECOVERED;
    }

    private boolean blocksCreationOfAnotherBankPayment(PaymentLink link) {
        if (link == null) {
            return false;
        }
        if (hasBankInitReservation(link)
                || hasBankCancelReservation(link)
                || link.getBankCancelOriginStatus() != null) {
            return true;
        }
        if (link.getStatus() == PaymentLinkStatus.NEEDS_RECONCILIATION) {
            return true;
        }
        if (normalize(link.getTbankPaymentId()).isBlank()) {
            return false;
        }
        return switch (link.getStatus()) {
            case REJECTED, REVERSED, REFUNDED -> false;
            case CANCELED, EXPIRED -> !normalize(link.getLastError()).isBlank();
            default -> true;
        };
    }

    private boolean hasCompetingBlockingPayment(PaymentLink current, List<PaymentLink> orderLinks) {
        return (orderLinks == null ? List.<PaymentLink>of() : orderLinks).stream()
                .filter(link -> !sameLinkId(current, link))
                .anyMatch(this::blocksCreationOfAnotherBankPayment);
    }

    private void rejectUnsafeCachedBankTargets(PaymentLink link, BankInitMode mode) {
        boolean unsafeCachedPaymentUrl = PaymentUrlPolicy.isUnsafeConfigured(
                link.getPaymentUrl(),
                PaymentUrlPolicy.Purpose.TBANK_PAYMENT
        );
        boolean missingCachedPaymentUrl = mode == BankInitMode.BANK_FORM
                && link.getStatus() == PaymentLinkStatus.INITIATED
                && !normalize(link.getTbankPaymentId()).isBlank()
                && PaymentUrlPolicy.safe(link.getPaymentUrl(), PaymentUrlPolicy.Purpose.TBANK_PAYMENT).isBlank();
        if (unsafeCachedPaymentUrl || missingCachedPaymentUrl) {
            quarantineUnsafeProviderTarget(
                    link,
                    mode.paymentMethod(),
                    "unsafe_cached_tbank_payment_url",
                    "Сохраненная резервная ссылка Т-Банка отсутствует или имеет недопустимый формат"
            );
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Сохраненная резервная ссылка Т-Банка отсутствует или имеет недопустимый формат"
            );
        }
        if (mode == BankInitMode.SBP_QR && PaymentUrlPolicy.isUnsafeConfigured(
                link.getSbpQrPayload(),
                PaymentUrlPolicy.Purpose.SBP_PAYLOAD
        )) {
            quarantineUnsafeProviderTarget(
                    link,
                    PaymentMethod.SBP_QR,
                    "unsafe_cached_tbank_sbp_payload",
                    "Сохраненная ссылка СБП имеет недопустимый формат"
            );
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Сохраненная ссылка СБП имеет недопустимый формат");
        }
    }

    private PublicPaymentInitResponse cachedBankInitResponse(
            PaymentLink link,
            String bankId,
            String clientIp,
            String userAgent,
            BankInitMode mode
    ) {
        if (mode == BankInitMode.BANK_FORM
                && !normalize(link.getPaymentUrl()).isBlank()
                && link.getStatus() == PaymentLinkStatus.INITIATED) {
            String paymentUrl = PaymentUrlPolicy.require(
                    link.getPaymentUrl(),
                    PaymentUrlPolicy.Purpose.TBANK_PAYMENT,
                    HttpStatus.BAD_GATEWAY,
                    "Сохраненная ссылка Т-Банка имеет недопустимый формат"
            );
            applyConsentTrace(link, clientIp, userAgent);
            link.setPaymentMethod(PaymentMethod.BANK_FORM);
            paymentLinkRepository.save(link);
            return new PublicPaymentInitResponse(paymentUrl, link.getTbankPaymentId(), link.getStatus().name());
        }
        if (mode == BankInitMode.SBP_QR
                && link.getPaymentMethod() == PaymentMethod.SBP_QR
                && !normalize(link.getSbpQrPayload()).isBlank()
                && PaymentUrlPolicy.isGenericSbpPayload(link.getSbpQrPayload())
                && normalize(bankId).isBlank()
                && link.getStatus() == PaymentLinkStatus.INITIATED) {
            String paymentUrl = PaymentUrlPolicy.optional(
                    link.getPaymentUrl(),
                    PaymentUrlPolicy.Purpose.TBANK_PAYMENT,
                    HttpStatus.BAD_GATEWAY,
                    "Сохраненная резервная ссылка Т-Банка имеет недопустимый формат"
            );
            String qrPayload = PaymentUrlPolicy.require(
                    link.getSbpQrPayload(),
                    PaymentUrlPolicy.Purpose.SBP_PAYLOAD,
                    HttpStatus.BAD_GATEWAY,
                    "Сохраненная ссылка СБП имеет недопустимый формат"
            );
            applyConsentTrace(link, clientIp, userAgent);
            paymentLinkRepository.save(link);
            return new PublicPaymentInitResponse(
                    paymentUrl,
                    link.getTbankPaymentId(),
                    link.getStatus().name(),
                    PaymentMethod.SBP_QR.name(),
                    qrPayload,
                    null
            );
        }
        return null;
    }

    private BankInitReservation bankInitReservation(
            PaymentLink link,
            TbankPaymentProfile runtimeProfile,
            String email,
            String bankId,
            BankInitMode mode,
            String nonce,
            PublicPaymentInitResponse cachedResponse
    ) {
        return new BankInitReservation(
                link.getId(),
                link.getOrder() == null ? null : link.getOrder().getId(),
                normalize(link.getToken()),
                nonce,
                normalize(link.getTbankOrderId()),
                normalize(link.getTbankPaymentId()),
                normalize(link.getPaymentUrl()),
                link.getAmountKopecks(),
                normalize(link.getDescription()),
                email,
                normalize(bankId),
                normalize(runtimeProfile.terminalKey()),
                runtimeProfile,
                mode,
                cachedResponse
        );
    }

    private BankInitApplyResult applyBankInitResponse(
            BankInitReservation reservation,
            TbankInitResponse response,
            boolean keepLeaseForQr
    ) {
        PaymentLink link = lockBankInitReservation(reservation);
        if (link == null) {
            return BankInitApplyResult.error(HttpStatus.CONFLICT, "Платежная ссылка изменилась во время инициализации");
        }
        String responsePaymentId = normalize(response == null ? null : response.paymentId());
        if (!reservation.nonce().equals(normalize(link.getBankInitNonce()))) {
            if (normalize(link.getTbankPaymentId()).isBlank() && !responsePaymentId.isBlank()) {
                quarantineAmbiguousBankInit(link, responsePaymentId, "stale_init_response");
            }
            return BankInitApplyResult.error(
                    HttpStatus.CONFLICT,
                    "Получен устаревший ответ банка; платеж отправлен на сверку"
            );
        }
        if (!canApplyBankInitResponseTo(link.getStatus())) {
            quarantineAmbiguousBankInit(link, responsePaymentId, "link_retired_while_init_in_flight");
            return BankInitApplyResult.error(
                    HttpStatus.CONFLICT,
                    "Платежная ссылка закрылась во время инициализации; платеж отправлен на сверку"
            );
        }

        if (!consistentInitResponse(reservation, response) || responsePaymentId.isBlank()) {
            quarantineAmbiguousBankInit(link, responsePaymentId, "inconsistent_provider_response");
            return BankInitApplyResult.error(HttpStatus.BAD_GATEWAY, "Т-Банк вернул несогласованный ответ Init");
        }

        String paymentUrl;
        try {
            paymentUrl = reservation.mode() == BankInitMode.BANK_FORM
                    ? PaymentUrlPolicy.require(
                            response.paymentUrl(),
                            PaymentUrlPolicy.Purpose.TBANK_PAYMENT,
                            HttpStatus.BAD_GATEWAY,
                            "Т-Банк вернул недопустимую ссылку оплаты"
                    )
                    : PaymentUrlPolicy.optional(
                            response.paymentUrl(),
                            PaymentUrlPolicy.Purpose.TBANK_PAYMENT,
                            HttpStatus.BAD_GATEWAY,
                            "Т-Банк вернул недопустимую резервную ссылку оплаты"
                    );
        } catch (ResponseStatusException e) {
            boolean quarantined = quarantineAmbiguousBankInit(
                    link,
                    responsePaymentId,
                    "unsafe_tbank_payment_url: " + normalize(e.getReason())
            );
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
        BankInitApplyResult invalidated = rejectLateBankInitResultIfOrderChanged(
                link,
                responsePaymentId,
                "init_response"
        );
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
        PublicPaymentInitResponse publicResponse = keepLeaseForQr
                ? null
                : new PublicPaymentInitResponse(paymentUrl, responsePaymentId, link.getStatus().name());
        return BankInitApplyResult.success(publicResponse, responsePaymentId, paymentUrl);
    }

    private BankInitApplyResult applyQrResponse(
            BankInitReservation reservation,
            String paymentId,
            String paymentUrl,
            TbankGetQrResponse response
    ) {
        PaymentLink link = lockBankInitReservation(reservation);
        if (link == null
                || !reservation.nonce().equals(normalize(link.getBankInitNonce()))
                || !normalize(link.getTbankPaymentId()).equals(normalize(paymentId))) {
            return BankInitApplyResult.error(HttpStatus.CONFLICT, "Платеж изменился во время получения СБП-ссылки");
        }

        String responseTerminal = normalize(response == null ? null : response.terminalKey());
        String responsePaymentId = normalize(response == null ? null : response.paymentId());
        String responseErrorCode = normalize(response == null ? null : response.errorCode());
        if (response == null
                || !response.success()
                || (!responseErrorCode.isBlank() && !"0".equals(responseErrorCode))
                || (!responseTerminal.isBlank() && !responseTerminal.equals(reservation.terminalKey()))
                || (!responsePaymentId.isBlank() && !responsePaymentId.equals(normalize(paymentId)))) {
            quarantineAmbiguousBankInit(link, paymentId, "inconsistent_get_qr_response");
            return BankInitApplyResult.error(HttpStatus.BAD_GATEWAY, "Т-Банк вернул несогласованный ответ GetQr");
        }

        String qrPayload = normalize(response == null ? null : response.data());
        try {
            qrPayload = PaymentUrlPolicy.require(
                    qrPayload,
                    PaymentUrlPolicy.Purpose.SBP_PAYLOAD,
                    HttpStatus.BAD_GATEWAY,
                    qrPayload.isBlank() ? "Т-Банк не вернул ссылку СБП" : "Т-Банк вернул недопустимую ссылку СБП"
            );
            paymentUrl = PaymentUrlPolicy.optional(
                    paymentUrl,
                    PaymentUrlPolicy.Purpose.TBANK_PAYMENT,
                    HttpStatus.BAD_GATEWAY,
                    "Т-Банк вернул недопустимую резервную ссылку оплаты"
            );
        } catch (ResponseStatusException e) {
            boolean quarantined = quarantineAmbiguousBankInit(
                    link,
                    paymentId,
                    "unsafe_tbank_sbp_payload: " + normalize(e.getReason())
            );
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

        BankInitApplyResult invalidated = rejectLateBankInitResultIfOrderChanged(
                link,
                paymentId,
                "get_qr_response"
        );
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
        return BankInitApplyResult.success(
                new PublicPaymentInitResponse(
                        paymentUrl,
                        paymentId,
                        link.getStatus().name(),
                        PaymentMethod.SBP_QR.name(),
                        qrPayload,
                        null
                ),
                paymentId,
                paymentUrl
        );
    }

    private BankInitApplyResult rejectLateBankInitResultIfOrderChanged(
            PaymentLink link,
            String paymentId,
            String phase
    ) {
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
        String reason = expired
                ? "link_expired_during_" + normalize(phase)
                : amountChanged
                    ? "order_amount_changed_during_" + normalize(phase)
                    : "order_settled_during_" + normalize(phase);
        quarantineAmbiguousBankInit(link, paymentId, reason);
        return BankInitApplyResult.error(
                HttpStatus.CONFLICT,
                expired
                        ? "Срок платежной ссылки истек во время обращения к банку; платеж отправлен на сверку"
                        : amountChanged
                            ? "Сумма заказа изменилась во время обращения к банку; платеж отправлен на сверку"
                : "Заказ был оплачен во время обращения к банку; новый платеж отправлен на сверку"
        );
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
        if (reservation == null
                || reservation.orderId() == null
                || orderRepository.findByIdForCounterUpdate(reservation.orderId()).isEmpty()) {
            return null;
        }
        PaymentLink link = reservation.linkId() == null
                ? findPublicLinkForUpdate(reservation.token())
                : paymentLinkRepository.findByIdForUpdate(reservation.linkId()).orElse(null);
        if (!hasOrderBinding(link, reservation.orderId())
                || link.getAmountKopecks() != reservation.amountKopecks()
                || !normalize(link.getToken()).equals(reservation.token())
                || !normalize(link.getTbankOrderId()).equals(reservation.tbankOrderId())
                || !normalize(link.getTbankTerminalKey()).equals(reservation.terminalKey())) {
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
        return (responseOrderId.isBlank() || responseOrderId.equals(reservation.tbankOrderId()))
                && (responseTerminal.isBlank() || responseTerminal.equals(reservation.terminalKey()))
                && (response.amount() == null || response.amount() == reservation.amountKopecks());
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

    private void recordQrFailure(
            BankInitReservation reservation,
            String paymentId,
            RuntimeException failure
    ) {
        transactionExecutor.required(() -> {
            PaymentLink link = lockBankInitReservation(reservation);
            if (link != null
                    && reservation.nonce().equals(normalize(link.getBankInitNonce()))
                    && normalize(link.getTbankPaymentId()).equals(normalize(paymentId))) {
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
        String observedPaymentId = normalize(paymentId);
        if (normalize(link.getTbankPaymentId()).isBlank() && !observedPaymentId.isBlank()) {
            link.setTbankPaymentId(observedPaymentId);
        }
        boolean quarantined = !isBankInitBusinessStateAuthoritative(link.getStatus());
        if (quarantined) {
            link.setStatus(PaymentLinkStatus.NEEDS_RECONCILIATION);
        }
        if (link.getInitiatedAt() == null && !observedPaymentId.isBlank()) {
            link.setInitiatedAt(LocalDateTime.now());
        }
        if (quarantined) {
            link.setPaymentUrl(null);
        }
        clearBankInitReservation(link);
        if (quarantined) {
            link.setLastError(limit(BANK_INIT_AMBIGUOUS_PREFIX + " " + normalize(reason), 512));
        }
        paymentLinkRepository.save(link);
        return quarantined;
    }

    private void clearBankInitReservation(PaymentLink link) {
        link.setBankInitNonce(null);
        link.setBankInitLeaseUntil(null);
    }

    private boolean canApplyBankInitResponseTo(PaymentLinkStatus status) {
        return status == PaymentLinkStatus.CREATED
                || status == PaymentLinkStatus.INITIATED
                || status == PaymentLinkStatus.AUTHORIZED
                || CONFIRMED_LIKE_BANK_STATUSES.contains(status)
                || REFUND_OR_REVERSAL_BANK_STATUSES.contains(status);
    }

    private boolean isBankInitBusinessStateAuthoritative(PaymentLinkStatus status) {
        return status == PaymentLinkStatus.AUTHORIZED
                || status == PaymentLinkStatus.CANCELED
                || status == PaymentLinkStatus.EXPIRED
                || status == PaymentLinkStatus.REJECTED
                || CONFIRMED_LIKE_BANK_STATUSES.contains(status)
                || REFUND_OR_REVERSAL_BANK_STATUSES.contains(status);
    }

    private String providerFailureReason(RuntimeException failure) {
        if (failure instanceof ResponseStatusException statusException) {
            String reason = normalize(statusException.getReason());
            return reason.isBlank() ? "T-Bank provider call failed" : reason;
        }
        return failure == null || normalize(failure.getMessage()).isBlank()
                ? "T-Bank provider call failed"
                : normalize(failure.getMessage());
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
    public void handleTbankWebhook(Map<String, String> payload) {
        VerifiedWebhookProfile verified = verifyWebhook(payload);

        String orderId = normalize(payload.get("OrderId"));
        String paymentId = normalize(payload.get("PaymentId"));
        Optional<PaymentLink> linkCandidate = !orderId.isBlank()
                ? paymentLinkRepository.findByTbankOrderIdWithOrder(orderId)
                : Optional.empty();
        if (linkCandidate.isEmpty() && !paymentId.isBlank()) {
            linkCandidate = paymentLinkRepository.findByTbankPaymentIdWithOrder(paymentId);
        }

        if (linkCandidate.isEmpty()) {
            CommonBillingService commonBillingService = commonBillingServiceProvider.getIfAvailable();
            if (commonBillingService != null && commonBillingService.handleTbankWebhook(payload)) {
                return;
            }
            log.warn("T-Bank webhook ignored: payment link not found for OrderId={}, PaymentId={}",
                    maskPaymentId(orderId), maskPaymentId(paymentId));
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

    private void applyTbankWebhookLocked(
            Long linkId,
            Long orderId,
            Map<String, String> payload,
            VerifiedWebhookProfile verified
    ) {
        if (linkId == null || orderId == null || orderRepository.findByIdForCounterUpdate(orderId).isEmpty()) {
            log.warn("T-Bank webhook ignored because canonical payment binding disappeared: linkId={}, orderId={}",
                    linkId, orderId);
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
        String status = normalize(incomingStatus).toUpperCase(Locale.ROOT);
        boolean initiatedCancelAuthoritativeState = link != null
                && link.getBankCancelOriginStatus() == PaymentLinkStatus.INITIATED
                && ("CONFIRMED".equals(status)
                    || "AUTHORIZED".equals(status)
                    || "REJECTED".equals(status)
                    || "DEADLINE_EXPIRED".equals(status));
        if (!hasBankCancelReservation(link)
                || isExplicitCancelTerminalStatus(incomingStatus)
                || initiatedCancelAuthoritativeState
                || link.getBankCancelLeaseUntil() == null
                || !link.getBankCancelLeaseUntil().isAfter(LocalDateTime.now())) {
            return false;
        }
        link.setStatus(PaymentLinkStatus.NEEDS_RECONCILIATION);
        link.setLastError(limit(
                BANK_CANCEL_IN_PROGRESS_PREFIX + " awaiting_explicit_bank_result; observed="
                        + normalize(incomingStatus).toUpperCase(),
                512
        ));
        return true;
    }

    /**
     * Restores a previously paid local state without replaying order-payment
     * side effects when GetState merely confirms that an ambiguous Cancel did
     * not change the bank payment. Regressive or unknown observations remain
     * quarantined until the bank reports a conclusive state.
     */
    private boolean applyCancelRecoveryObservationIfNeeded(PaymentLink link, String incomingStatus) {
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
            if ("CONFIRMED".equals(status) || isExplicitCancelTerminalStatus(status)
                    || "REJECTED".equals(status) || "DEADLINE_EXPIRED".equals(status)) {
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
            if ("CANCELED".equals(status)
                    || "REJECTED".equals(status)
                    || "DEADLINE_EXPIRED".equals(status)
                    || "CONFIRMED".equals(status)
                    || "AUTHORIZED".equals(status)
                    || isRefundOrReversalBankStatus(status)) {
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
        link.setLastError(limit(
                BANK_CANCEL_AMBIGUOUS_PREFIX + " inconclusive_bank_status=" + normalize(observedStatus),
                512
        ));
    }

    private void clearResolvedCancelReservation(PaymentLink link, String incomingStatus) {
        if (!hasBankCancelReservation(link) && link.getBankCancelOriginStatus() == null) {
            return;
        }
        String status = normalize(incomingStatus).toUpperCase();
        if (isExplicitCancelTerminalStatus(status)
                || "REJECTED".equals(status)
                || "DEADLINE_EXPIRED".equals(status)) {
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

    private boolean isStateConsistent(PaymentLink link, TbankGetStateResponse state, TbankPaymentProfile runtimeProfile) {
        String responseTerminal = normalize(state.terminalKey());
        if (!responseTerminal.isBlank() && !responseTerminal.equals(runtimeProfile.terminalKey())) {
            link.setLastError("TerminalKey GetState не совпадает с платежной ссылкой");
            log.warn(
                    "T-Bank GetState terminal mismatch: linkId={}, expected={}, actual={}",
                    link.getId(),
                    runtimeProfile.terminalKey(),
                    responseTerminal
            );
            return false;
        }

        if (state.amount() != null && state.amount() != link.getAmountKopecks()) {
            link.setLastError("Сумма GetState не совпадает с платежной ссылкой");
            log.warn(
                    "T-Bank GetState amount mismatch: linkId={}, expected={}, actual={}",
                    link.getId(),
                    link.getAmountKopecks(),
                    state.amount()
            );
            return false;
        }

        String responsePaymentId = normalize(state.paymentId());
        if (!responsePaymentId.isBlank()
                && !responsePaymentId.equals(normalize(link.getTbankPaymentId()))) {
            link.setLastError("PaymentId GetState не совпадает с платежной ссылкой");
            log.warn("T-Bank GetState payment binding mismatch: linkId={}", link.getId());
            return false;
        }

        String responseOrderId = normalize(state.orderId());
        String currentOrderId = normalize(link.getTbankOrderId());
        if (!responseOrderId.isBlank()
                && !currentOrderId.isBlank()
                && !responseOrderId.equals(currentOrderId)) {
            link.setLastError("OrderId GetState не совпадает с платежной ссылкой");
            log.warn("T-Bank GetState order binding mismatch: linkId={}", link.getId());
            return false;
        }

        return true;
    }

    private void applyBankStatus(PaymentLink link, String status, boolean success, String errorCode) {
        if (shouldIgnoreStaleBankStatus(link, status)) {
            log.info(
                    "Stale T-Bank status ignored for terminal payment: linkId={}, current={}, incoming={}",
                    link.getId(),
                    link.getStatus(),
                    status
            );
            return;
        }
        switch (status) {
            case "CONFIRMED" -> confirmPayment(link);
            case "AUTHORIZED" -> {
                if (!isFinalStatus(link.getStatus())) {
                    link.setStatus(PaymentLinkStatus.AUTHORIZED);
                    link.setLastError(null);
                }
            }
            case "NEW" -> {
                if (link.getStatus() == PaymentLinkStatus.CREATED) {
                    link.setStatus(PaymentLinkStatus.INITIATED);
                }
            }
            case "REJECTED" -> {
                link.setStatus(PaymentLinkStatus.REJECTED);
                link.setProviderTerminalStatus("REJECTED");
                link.setLastError(errorCode);
            }
            case "CANCELED" -> markFinalBankStatus(link, PaymentLinkStatus.CANCELED, status);
            case "REVERSED" -> markFinalBankStatus(link, PaymentLinkStatus.REVERSED, status);
            case "PARTIAL_REVERSED" -> markFinalBankStatus(link, PaymentLinkStatus.PARTIAL_REVERSED, status);
            case "REFUNDED" -> markFinalBankStatus(link, PaymentLinkStatus.REFUNDED, status);
            case "PARTIAL_REFUNDED" -> markFinalBankStatus(link, PaymentLinkStatus.PARTIAL_REFUNDED, status);
            case "DEADLINE_EXPIRED" -> markFinalBankStatus(link, PaymentLinkStatus.EXPIRED, status);
            default -> {
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
    }

    private void confirmPayment(PaymentLink link) {
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
        if (!runtimeSettingsService.isApplyConfirmedPayments()
                || paymentProfileService.isTestTerminal(link.getTbankTerminalKey())) {
            link.setStatus(PaymentLinkStatus.TEST_CONFIRMED);
            link.setPaidAt(LocalDateTime.now());
            link.setConfirmedAmountKopecks(link.getAmountKopecks());
            link.setLastError(null);
            rememberCompanyPayerEmail(link);
            log.info(
                    "T-Bank payment confirmed in test mode without applying order transition: linkId={}, orderId={}",
                    link.getId(),
                    link.getOrder() == null ? null : link.getOrder().getId()
            );
            return;
        }
        if (!canApplyOrderPaymentNow(link.getOrder())) {
            markOrderPrepaid(link);
            prepareSuccessNotificationRetry(link);
            return;
        }
        try {
            boolean updated = handlePaymentStatusWithoutPrematureRepeat(link.getOrder());
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

    private boolean shouldIgnoreStaleBankStatus(PaymentLink link, String incomingStatus) {
        PaymentLinkStatus current = link == null ? null : link.getStatus();
        String incoming = normalize(incomingStatus).toUpperCase();
        if (CONFIRMED_LIKE_BANK_STATUSES.contains(current)) {
            if ("CANCELED".equals(incoming)
                    && link.getBankCancelOriginStatus() != null) {
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
        return "REVERSED".equals(status)
                || "PARTIAL_REVERSED".equals(status)
                || "REFUNDED".equals(status)
                || "PARTIAL_REFUNDED".equals(status);
    }

    private boolean isAllowedRefundProgress(PaymentLinkStatus current, String incoming) {
        if (current.name().equals(incoming)) {
            return true;
        }
        return switch (current) {
            case PARTIAL_REVERSED -> "REVERSED".equals(incoming)
                    || "PARTIAL_REFUNDED".equals(incoming)
                    || "REFUNDED".equals(incoming);
            case REVERSED -> "PARTIAL_REFUNDED".equals(incoming) || "REFUNDED".equals(incoming);
            case PARTIAL_REFUNDED -> "REFUNDED".equals(incoming);
            case REFUNDED -> false;
            default -> false;
        };
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean reconcileBankLink(Long linkId) {
        return reconcileBankLink(linkId, LocalDateTime.now().minusMinutes(5));
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean recoverExpiredBankInitReservation(Long linkId, LocalDateTime expiredBefore) {
        if (linkId == null || linkId <= 0) {
            return false;
        }
        PaymentLink snapshot = paymentLinkRepository.findByIdWithOrder(linkId).orElse(null);
        Long orderId = snapshot == null || snapshot.getOrder() == null
                ? null
                : snapshot.getOrder().getId();
        LocalDateTime cutoff = expiredBefore == null ? LocalDateTime.now() : expiredBefore;
        return transactionExecutor.requiredNoRollback(() -> {
            if (orderId == null || orderRepository.findByIdForCounterUpdate(orderId).isEmpty()) {
                return false;
            }
            PaymentLink link = paymentLinkRepository.findByIdForUpdate(linkId).orElse(null);
            if (!hasOrderBinding(link, orderId)
                    || !hasBankInitReservation(link)
                    || (link.getBankInitLeaseUntil() != null
                        && link.getBankInitLeaseUntil().isAfter(cutoff))) {
                return false;
            }
            BankInitReservationRecovery recovery = recoverExpiredBankInitReservationLocked(
                    link,
                    cutoff,
                    "scheduled_recovery"
            );
            return recovery == BankInitReservationRecovery.RETRYABLE_RECOVERED
                    || recovery == BankInitReservationRecovery.QUARANTINED;
        });
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
        BankStateObservation observation = observeTbankState(snapshot);
        Long orderId = snapshot.getOrder() == null ? null : snapshot.getOrder().getId();
        return transactionExecutor.required(() ->
                applyReconciliationObservation(linkId, orderId, eligibleBefore, observation)
        );
    }

    private boolean applyReconciliationObservation(
            Long linkId,
            Long orderId,
            LocalDateTime eligibleBefore,
            BankStateObservation observation
    ) {
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
        applyObservedTbankStateIfCurrent(link, observation, orderId);
        paymentLinkRepository.save(link);
        return before != link.getStatus();
    }

    private boolean isReconciliationEligible(PaymentLink link, LocalDateTime eligibleBefore) {
        return link != null
                && (SYNCABLE_BANK_STATUSES.contains(link.getStatus())
                    || link.getBankCancelOriginStatus() != null)
                && !normalize(link.getTbankPaymentId()).isBlank()
                && (link.getBankReconciliationAttemptedAt() == null
                    || !link.getBankReconciliationAttemptedAt().isAfter(eligibleBefore));
    }

    @Transactional
    public boolean applyConfirmedPrepaymentIfReady(Order order) {
        if (order == null || order.getId() == null || !canApplyOrderPaymentNow(order)) {
            return false;
        }
        Optional<PaymentLink> optionalLink = paymentLinkRepository
                .findFirstByOrder_IdAndStatusAndLastErrorOrderByPaidAtDesc(
                        order.getId(),
                        PaymentLinkStatus.CONFIRMED,
                        PREPAID_WAITING_ORDER_COMPLETION
                );
        if (optionalLink.isEmpty()) {
            return false;
        }

        PaymentLink link = optionalLink.get();
        if (markAmountMismatchIfNeeded(link)) {
            paymentLinkRepository.save(link);
            return false;
        }

        try {
            boolean updated = handlePaymentStatusWithoutPrematureRepeat(order);
            link.setLastError(null);
            paymentLinkRepository.save(link);
            if (updated) {
                cancelBadReviewAutoBanAfterCommit(order, "Предоплата применена после завершения заказа");
            }
            syncCommonInvoiceOrderPayment(link, "Предоплата заказа применена после завершения");
            log.info("Предоплата по ссылке {} применена после завершения заказа {}", link.getId(), order.getId());
            return true;
        } catch (Exception e) {
            link.setStatus(PaymentLinkStatus.FAILED);
            link.setLastError("Prepaid order payment transition failed");
            paymentLinkRepository.save(link);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Не удалось применить предоплату заказа", e);
        }
    }

    private boolean canApplyOrderPaymentNow(Order order) {
        return order != null
                && (order.isComplete() || order.getAmount() <= order.getCounter());
    }

    private boolean handlePaymentStatusWithoutPrematureRepeat(Order order) throws Exception {
        CommonBillingService commonBillingService = commonBillingServiceProvider.getIfAvailable();
        Long orderId = order == null ? null : order.getId();
        if (commonBillingService == null || orderId == null) {
            return orderTransactionService.handlePaymentStatus(order);
        }
        try {
            if (commonBillingService.isOrderInActiveCommonInvoice(orderId)) {
                return orderTransactionService.handlePaymentStatus(order, false);
            }
        } catch (RuntimeException e) {
            log.warn(
                    "Не удалось проверить общий счет заказа {} перед оплатой; следующий заказ временно не создается",
                    orderId,
                    e
            );
            return orderTransactionService.handlePaymentStatus(order, false);
        }
        return orderTransactionService.handlePaymentStatus(order);
    }

    private void syncCommonInvoiceOrderPayment(PaymentLink link, String reason) {
        CommonBillingService commonBillingService = commonBillingServiceProvider.getIfAvailable();
        Order order = link == null ? null : link.getOrder();
        Long orderId = order == null ? null : order.getId();
        if (commonBillingService == null || orderId == null) {
            return;
        }
        try {
            commonBillingService.applyConfirmedOrderPayment(orderId, link.getPaidAt(), reason);
        } catch (RuntimeException e) {
            log.warn("Не удалось зачесть оплату заказа {} в общий счет", orderId, e);
        }
    }

    private void markOrderPrepaid(PaymentLink link) {
        link.setStatus(PaymentLinkStatus.CONFIRMED);
        link.setPaidAt(LocalDateTime.now());
        link.setConfirmedAmountKopecks(link.getAmountKopecks());
        link.setReceiptStatus(PaymentReceiptStatus.PENDING);
        link.setLastError(PREPAID_WAITING_ORDER_COMPLETION);
        rememberCompanyPayerEmail(link);
        log.info(
                "Платеж по заказу {} принят как предоплата: linkId={}, amount={}",
                link.getOrder() == null ? null : link.getOrder().getId(),
                link.getId(),
                link.getAmountKopecks()
        );
    }

    private void markClosedLinkConfirmed(PaymentLink link) {
        link.setStatus(PaymentLinkStatus.AMOUNT_MISMATCH);
        link.setPaidAt(LocalDateTime.now());
        link.setConfirmedAmountKopecks(link.getAmountKopecks());
        link.setLastError("Платеж пришел по закрытой ссылке: заказ уже закрыт вручную или ссылка была недоступна");
        rememberCompanyPayerEmail(link);
        log.warn(
                "Payment confirmed for retired link: linkId={}, orderId={}, amount={}",
                link.getId(),
                link.getOrder() == null ? null : link.getOrder().getId(),
                link.getAmountKopecks()
        );
    }

    private boolean markAmountMismatchIfNeeded(PaymentLink link) {
        long currentAmount = currentAmountKopecks(link);
        if (currentAmount == link.getAmountKopecks()) {
            return false;
        }

        link.setStatus(PaymentLinkStatus.AMOUNT_MISMATCH);
        link.setPaidAt(LocalDateTime.now());
        link.setConfirmedAmountKopecks(link.getAmountKopecks());
        link.setLastError("Платеж пришел по устаревшей сумме: оплачено "
                + amountRubles(link.getAmountKopecks()).stripTrailingZeros().toPlainString()
                + " руб., актуально "
                + amountRubles(currentAmount).stripTrailingZeros().toPlainString()
                + " руб. Заказ не переведен в оплату.");
        rememberCompanyPayerEmail(link);
        log.warn(
                "Payment amount mismatch: linkId={}, orderId={}, paidAmount={}, currentAmount={}",
                link.getId(),
                link.getOrder() == null ? null : link.getOrder().getId(),
                link.getAmountKopecks(),
                currentAmount
        );
        return true;
    }

    private void prepareSuccessNotificationRetry(PaymentLink link) {
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
        return paymentLinkRepository.findByOrder_IdAndStatusIn(orderId, Set.of(PaymentLinkStatus.CONFIRMED))
                .stream()
                .anyMatch(existing -> !sameLinkId(existing, link)
                        && (currentLinkCreatedAt == null
                        || existing.getPaidAt() == null
                        || !existing.getPaidAt().isBefore(currentLinkCreatedAt)));
    }

    private void markDuplicateConfirmedPayment(PaymentLink link) {
        link.setStatus(PaymentLinkStatus.AMOUNT_MISMATCH);
        link.setPaidAt(LocalDateTime.now());
        link.setConfirmedAmountKopecks(link.getAmountKopecks());
        link.setLastError(
                "duplicate_confirmed_payment: по заказу уже есть другой подтвержденный платеж; "
                        + "сумма не зачислена повторно, требуется сверка и при необходимости возврат"
        );
        rememberCompanyPayerEmail(link);
        log.error(
                "Duplicate confirmed payment detected: linkId={}, orderId={}, amount={}",
                link.getId(),
                link.getOrder() == null ? null : link.getOrder().getId(),
                link.getAmountKopecks()
        );
    }

    private VerifiedWebhookProfile verifyWebhook(Map<String, String> payload) {
        String terminalKey = normalize(payload.get("TerminalKey"));
        PaymentProfile profile = paymentProfileService.findByTerminalKey(terminalKey)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "TerminalKey не совпадает с настройками"));
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

    private void validateCancelResponse(
            CancelReservation reservation,
            TbankCancelResponse response
    ) {
        if (response == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Т-Банк вернул пустой ответ на Cancel");
        }
        String errorCode = normalize(response.errorCode());
        if (!response.success() || (!errorCode.isBlank() && !"0".equals(errorCode))) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, response.errorText());
        }
        String responseTerminal = normalize(response.terminalKey());
        if (!responseTerminal.isBlank()
                && !responseTerminal.equals(normalize(reservation.runtimeProfile().terminalKey()))) {
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

    private PaymentLink findPublicLink(String token) {
        String cleanToken = normalize(token);
        if (cleanToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежная ссылка не найдена");
        }
        return paymentLinkRepository.findByTokenWithOrder(cleanToken)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежная ссылка не найдена"));
    }

    private PaymentLink findPublicLinkForUpdate(String token) {
        String cleanToken = normalize(token);
        if (cleanToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежная ссылка не найдена");
        }
        return paymentLinkRepository.findByTokenForUpdate(cleanToken)
                .or(() -> paymentLinkRepository.findByTokenWithOrder(cleanToken))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежная ссылка не найдена"));
    }

    private PaymentLink findPublicLinkForUpdateStrict(String token) {
        String cleanToken = normalize(token);
        if (cleanToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежная ссылка не найдена");
        }
        return paymentLinkRepository.findByTokenForUpdate(cleanToken)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежная ссылка не найдена"));
    }

    private PaymentLink lockResolvedPublicLink(PaymentLink link) {
        if (link == null || link.getId() == null) {
            return link;
        }
        return paymentLinkRepository.findByIdForUpdate(link.getId()).orElse(link);
    }

    private Optional<PaymentLink> findLinkByIdForUpdate(Long linkId) {
        return paymentLinkRepository.findByIdForUpdate(linkId)
                .or(() -> paymentLinkRepository.findByIdWithOrder(linkId));
    }

    private PaymentLink resolveReplacementPublicLink(PaymentLink link, LocalDateTime now, boolean createIfMissing) {
        if (!shouldResolveReplacementPublicLink(link, now)) {
            return link;
        }

        Long orderId = link.getOrder() == null ? null : link.getOrder().getId();
        Optional<PaymentLink> replacement = paymentLinkRepository
                .findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(orderId, REUSABLE_STATUSES, now)
                .filter(candidate -> !sameLinkId(candidate, link));
        if (replacement.isPresent() || !createIfMissing) {
            return replacement.orElse(link);
        }

        return createReplacementPublicLink(orderId, now).orElse(link);
    }

    private Optional<PaymentLink> createReplacementPublicLink(Long orderId, LocalDateTime now) {
        if (orderId == null || orderId <= 0 || !runtimeSettingsService.isPaymentLinksEnabled()) {
            return Optional.empty();
        }
        try {
            createForOrder(orderId);
        } catch (ResponseStatusException e) {
            log.warn(
                    "Public payment link replacement skipped: orderId={}, status={}, reason={}",
                    orderId,
                    e.getStatusCode(),
                    normalize(e.getReason())
            );
            return Optional.empty();
        } catch (RuntimeException e) {
            log.warn("Public payment link replacement failed: orderId={}", orderId, e);
            return Optional.empty();
        }

        return paymentLinkRepository
                .findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(orderId, REUSABLE_STATUSES, now)
                .filter(candidate -> candidate.getExpiresAt() != null && candidate.getExpiresAt().isAfter(now));
    }

    private boolean shouldResolveReplacementPublicLink(PaymentLink link, LocalDateTime now) {
        if (link == null || link.getOrder() == null || link.getOrder().getId() == null) {
            return false;
        }
        if (hasBankInitReservation(link)) {
            return false;
        }
        if (isManualPaidRetiredLink(link)) {
            return true;
        }
        if (link.getStatus() == PaymentLinkStatus.CONFIRMED
                || link.getStatus() == PaymentLinkStatus.TEST_CONFIRMED
                || link.getStatus() == PaymentLinkStatus.AMOUNT_MISMATCH
                || link.getStatus() == PaymentLinkStatus.AUTHORIZED
                || link.getStatus() == PaymentLinkStatus.NEEDS_RECONCILIATION
                || REFUNDED_STATUSES.contains(link.getStatus())) {
            return false;
        }
        return link.getStatus() == PaymentLinkStatus.EXPIRED
                || link.getStatus() == PaymentLinkStatus.FAILED
                || link.getStatus() == PaymentLinkStatus.REJECTED
                || (link.getExpiresAt() != null && !link.getExpiresAt().isAfter(now));
    }

    private boolean isManualPaidRetiredLink(PaymentLink link) {
        if (link.getStatus() != PaymentLinkStatus.CANCELED
                || !MANUAL_PAID_RETIRED_REASON.equals(normalize(link.getLastError()))
                || !normalize(link.getTbankPaymentId()).isBlank()) {
            return false;
        }
        Order order = link.getOrder();
        String statusTitle = order.getStatus() == null ? "" : normalize(order.getStatus().getTitle());
        return !STATUS_PAYMENT.equals(statusTitle);
    }

    private boolean sameLinkId(PaymentLink left, PaymentLink right) {
        Long leftId = left == null ? null : left.getId();
        Long rightId = right == null ? null : right.getId();
        return leftId != null && leftId.equals(rightId);
    }

    private void validatePayable(PaymentLink link) {
        orderPaymentIntegrityService.assertPaymentCycleAllowed(link == null ? null : link.getOrder());
        if (link.getStatus() == PaymentLinkStatus.NEEDS_RECONCILIATION) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Платеж уже создан в банке и требует сверки. Повторная оплата заблокирована."
            );
        }
        if (link.getStatus() == PaymentLinkStatus.AUTHORIZED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Платеж уже авторизован банком. Повторная инициализация заблокирована."
            );
        }
        if (link.getExpiresAt().isBefore(LocalDateTime.now())) {
            link.setStatus(PaymentLinkStatus.EXPIRED);
            throw new ResponseStatusException(HttpStatus.GONE, "Срок действия платежной ссылки истек");
        }
        if (expireIfAmountChanged(link)) {
            throw new ResponseStatusException(HttpStatus.GONE, "Сумма заказа изменилась. Создайте новую ссылку на оплату.");
        }
        if (isAmountChanged(link)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Сумма заказа изменилась, а платеж уже в процессе. Проверьте платеж вручную.");
        }
        if (link.getStatus() == PaymentLinkStatus.CONFIRMED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Заказ уже оплачен");
        }
        if (link.getStatus() == PaymentLinkStatus.AMOUNT_MISMATCH) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Платеж пришел по устаревшей сумме и требует ручной сверки");
        }
        if (link.getStatus() == PaymentLinkStatus.TEST_CONFIRMED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Тестовый платеж по ссылке уже подтвержден");
        }
        if (link.getStatus() == PaymentLinkStatus.CANCELED
                || link.getStatus() == PaymentLinkStatus.REVERSED
                || link.getStatus() == PaymentLinkStatus.PARTIAL_REVERSED
                || link.getStatus() == PaymentLinkStatus.REFUNDED
                || link.getStatus() == PaymentLinkStatus.PARTIAL_REFUNDED
                || link.getStatus() == PaymentLinkStatus.REJECTED
                || link.getStatus() == PaymentLinkStatus.FAILED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Платежная ссылка недоступна");
        }
    }

    private void expireIfPastDue(PaymentLink link) {
        if (link.getExpiresAt() != null
                && link.getExpiresAt().isBefore(LocalDateTime.now())
                && !hasBankInitReservation(link)
                && (link.getStatus() == PaymentLinkStatus.WAITING_MANUAL_PAYMENT
                || link.getStatus() == PaymentLinkStatus.MANUAL_REPORTED
                || link.getStatus() == PaymentLinkStatus.CREATED)) {
            link.setStatus(PaymentLinkStatus.EXPIRED);
            link.setLastError("Срок действия платежной ссылки истек");
            paymentLinkRepository.save(link);
        }
    }

    private boolean expireIfAmountChanged(PaymentLink link) {
        if (!canRetireStaleLink(link) || !isAmountChanged(link)) {
            return false;
        }
        long currentAmount = currentAmountKopecks(link);
        link.setStatus(PaymentLinkStatus.EXPIRED);
        link.setLastError("Сумма заказа изменилась: было "
                + amountRubles(link.getAmountKopecks()).stripTrailingZeros().toPlainString()
                + " руб., стало "
                + amountRubles(currentAmount).stripTrailingZeros().toPlainString()
                + " руб.");
        paymentLinkRepository.save(link);
        return true;
    }

    private boolean isAmountChanged(PaymentLink link) {
        return link != null && currentAmountKopecks(link) != link.getAmountKopecks();
    }

    private long currentAmountKopecks(PaymentLink link) {
        if (link == null || link.getOrder() == null || link.getOrder().getId() == null) {
            return link == null ? 0 : link.getAmountKopecks();
        }
        return amountKopecks(payableSum(link.getOrder()));
    }

    private void validateTbankPayment(PaymentLink link) {
        if (isManualPayment(link)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Эта ссылка создана для ручной оплаты");
        }
    }

    private void ensureManualPayment(PaymentLink link) {
        if (!isManualPayment(link)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Это не ручной платеж");
        }
    }

    private void ensureNoPendingVerifiedManualRouteTransition(List<PaymentLink> links) {
        boolean pendingCommonInvoiceTransition = links != null && links.stream().anyMatch(link ->
                link != null
                        && link.getStatus() == PaymentLinkStatus.CANCELED
                        && isManualPayment(link)
                        && normalize(link.getLastError()).startsWith(MANUAL_UNPAID_CLOSED_AUDIT_PREFIX + ":")
        );
        if (pendingCommonInvoiceTransition) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Ручная инструкция закрыта после проверки отсутствия перевода. "
                            + "Сначала завершите перенос заказа в общий счет; "
                            + "до этого новый отдельный способ оплаты заблокирован."
            );
        }
    }

    private void validateManualPaymentTargetAvailable(PaymentLink link) {
        boolean external = link.getPaymentMethod() == PaymentMethod.MANUAL_EXTERNAL_LINK
                || link.getManualPaymentType() == ManualPaymentType.EXTERNAL_LINK;
        boolean mobileBank = link.getPaymentMethod() == PaymentMethod.MANUAL_MOBILE_BANK
                || link.getManualPaymentType() == ManualPaymentType.MOBILE_BANK;

        if (external && manualPaymentUrlForRead(link.getManualPaymentUrl()).isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Ссылка ручной оплаты отсутствует или имеет недопустимый формат"
            );
        }
        if (mobileBank && normalize(link.getManualPhone()).isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Телефон для ручной оплаты через мобильный банк не указан"
            );
        }
    }

    private void validateManualConfirmable(PaymentLink link) {
        if (link.getStatus() == PaymentLinkStatus.CONFIRMED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ручная оплата уже подтверждена");
        }
        if (link.getStatus() != PaymentLinkStatus.WAITING_MANUAL_PAYMENT
                && link.getStatus() != PaymentLinkStatus.MANUAL_REPORTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ручная оплата недоступна для подтверждения");
        }
    }

    private void validateManualUnpaidClosable(PaymentLink link) {
        if (link.getStatus() != PaymentLinkStatus.WAITING_MANUAL_PAYMENT
                && link.getStatus() != PaymentLinkStatus.MANUAL_REPORTED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "С результатом «перевод не поступил» можно закрыть только ожидающую ручную инструкцию"
            );
        }
        boolean hasPaidEvidence = link.getPaidAt() != null
                || link.getManualConfirmedAt() != null
                || !normalize(link.getManualConfirmedBy()).isBlank()
                || (link.getConfirmedAmountKopecks() != null && link.getConfirmedAmountKopecks() > 0)
                || link.getReceiptStatus() == PaymentReceiptStatus.MARKED
                || link.getReceiptStatus() == PaymentReceiptStatus.LEGACY_NOT_REQUIRED
                || !normalize(link.getTbankPaymentId()).isBlank();
        if (hasPaidEvidence) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "У инструкции уже есть признаки оплаты или банковского платежа; требуется отдельная сверка"
            );
        }
    }

    private void validateAmountCurrentForManualConfirm(PaymentLink link) {
        if (expireIfAmountChanged(link) || isAmountChanged(link)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Сумма ручной оплаты изменилась. Создайте новый счет и сверяйте оплату вручную.");
        }
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

    private PublicPaymentLinkResponse toPublicResponse(PaymentLink link) {
        Order order = link.getOrder();
        return new PublicPaymentLinkResponse(
                link.getToken(),
                order == null ? null : order.getId(),
                companyTitle(order),
                filialTitle(order),
                link.getDescription(),
                amountRubles(link.getAmountKopecks()),
                link.getAmountKopecks(),
                link.getDescription(),
                "",
                link.getStatus().name(),
                paymentMethodName(link),
                link.getExpiresAt(),
                isPayable(link),
                paymentPageModeName(),
                runtimeSettingsService.isTpayEnabled(),
                runtimeSettingsService.isSberpayEnabled(),
                runtimeSettingsService.isMirpayEnabled(),
                manualPaymentTypeName(link),
                normalize(link.getManualPhone()),
                manualRecipientName(link),
                isManualPayment(link) ? manualPaymentUrlForRead(link.getManualPaymentUrl()) : "",
                manualButtonLabel(link),
                normalize(link.getManualComment()),
                link.getReceiptStatus() == null ? null : link.getReceiptStatus().name()
        );
    }

    private PublicSbpBankResponse toPublicSbpBankResponse(TbankGetQrBankListResponse.TbankSbpBank bank) {
        String name = normalize(bank.bankName());
        return new PublicSbpBankResponse(
                normalize(bank.bankId()),
                normalize(bank.nspkBankId()),
                name,
                normalize(bank.bankLogo()),
                bank.bankOrder(),
                featuredBankRank(name) < FEATURED_SBP_BANK_PATTERNS.size()
        );
    }

    private String paymentPageModeName() {
        TbankPaymentPageMode mode = runtimeSettingsService.paymentPageMode();
        return (mode == null ? TbankRuntimeSettingsService.DEFAULT_PAYMENT_PAGE_MODE : mode).name();
    }

    private ManagerPaymentLinkResponse toManagerResponse(PaymentLink link) {
        String url = publicPaymentUrl(link);
        return new ManagerPaymentLinkResponse(
                link.getToken(),
                url,
                link.getOrder() == null ? null : link.getOrder().getId(),
                amountRubles(link.getAmountKopecks()),
                link.getAmountKopecks(),
                link.getStatus().name(),
                paymentMethodName(link),
                link.getExpiresAt(),
                paymentInstructionText(link, url),
                paymentCopyText(link, url)
        );
    }

    private AdminPaymentLinkResponse toAdminResponse(PaymentLink link) {
        Order order = link.getOrder();
        return new AdminPaymentLinkResponse(
                link.getId(),
                link.getToken(),
                publicPaymentUrl(link),
                order == null ? null : order.getId(),
                companyTitle(order),
                filialTitle(order),
                link.getDescription(),
                amountRubles(link.getAmountKopecks()),
                link.getAmountKopecks(),
                link.getReservedAmountKopecks(),
                link.getConfirmedAmountKopecks(),
                link.getStatus().name(),
                paymentMethodName(link),
                paymentProfileCode(link),
                paymentProfileName(link),
                manualSourceName(link),
                manualTaskId(link),
                manualTaskTitle(link),
                normalize(link.getTbankTerminalKey()),
                link.getTbankPaymentId(),
                link.getTbankOrderId(),
                link.getPayerEmail(),
                PaymentUrlPolicy.safe(link.getPaymentUrl(), PaymentUrlPolicy.Purpose.TBANK_PAYMENT),
                manualPaymentTypeName(link),
                normalize(link.getManualPhone()),
                manualRecipientName(link),
                isManualPayment(link) ? manualPaymentUrlForRead(link.getManualPaymentUrl()) : "",
                manualButtonLabel(link),
                normalize(link.getManualComment()),
                link.getManualReportedAt(),
                normalize(link.getManualConfirmedBy()),
                link.getManualConfirmedAt(),
                link.getReceiptStatus() == null ? null : link.getReceiptStatus().name(),
                link.getPaymentSuccessNotifiedAt(),
                normalize(link.getPaymentSuccessNotificationError()),
                clientChatPlatform(order),
                clientChatReady(order),
                clientChatWarning(order),
                link.getLastError(),
                link.getCreatedAt(),
                link.getUpdatedAt(),
                link.getExpiresAt(),
                link.getInitiatedAt(),
                link.getPaidAt(),
                link.getSbpQrCreatedAt(),
                false,
                null,
                null,
                isRefundable(link)
        );
    }

    private String clientChatPlatform(Order order) {
        Company company = order == null ? null : order.getCompany();
        String value = company == null ? "" : normalize(company.getUrlChat());
        if (value.isBlank()) {
            return "UNKNOWN";
        }

        String normalized = value.toLowerCase(Locale.ROOT);
        if (normalized.matches("^(?:https?://)?chat\\.whatsapp\\.com/.+")) {
            return "WHATSAPP";
        }
        if (normalized.matches("^(?:https?://)?(?:t\\.me|telegram\\.me|telegram\\.dog)/.+")
                || normalized.startsWith("tg://resolve?")) {
            return "TELEGRAM";
        }
        if (normalized.matches("^(?:https?://)?(?:web\\.)?max\\.ru/.+")) {
            return "MAX";
        }
        return "UNKNOWN";
    }

    private boolean clientChatReady(Order order) {
        Company company = order == null ? null : order.getCompany();
        return switch (clientChatPlatform(order)) {
            case "WHATSAPP" -> company != null
                    && !normalize(company.getGroupId()).isBlank()
                    && !normalize(clientChatManager(order, company).map(Manager::getClientId).orElse(null)).isBlank();
            case "TELEGRAM" -> company != null && company.getTelegramGroupChatId() != null;
            case "MAX" -> company != null && company.getMaxGroupChatId() != null;
            default -> false;
        };
    }

    private String clientChatWarning(Order order) {
        Company company = order == null ? null : order.getCompany();
        String platform = clientChatPlatform(order);
        if ("UNKNOWN".equals(platform)) {
            return company == null || normalize(company.getUrlChat()).isBlank()
                    ? "ссылка на чат не указана"
                    : "ссылка на чат не распознана";
        }
        if (clientChatReady(order)) {
            return "";
        }
        return switch (platform) {
            case "WHATSAPP" -> {
                boolean hasGroup = company != null && !normalize(company.getGroupId()).isBlank();
                boolean hasClient = !normalize(clientChatManager(order, company).map(Manager::getClientId).orElse(null)).isBlank();
                if (!hasGroup && !hasClient) {
                    yield "для WhatsApp нужны groupId компании и clientId менеджера";
                }
                yield hasGroup ? "для WhatsApp не задан clientId менеджера" : "для WhatsApp не задан groupId компании";
            }
            case "TELEGRAM" -> "для Telegram не сохранен chatId группы";
            case "MAX" -> "для MAX не сохранен chatId группы";
            default -> "чат не готов";
        };
    }

    private Optional<Manager> clientChatManager(Order order, Company company) {
        if (order != null && order.getManager() != null) {
            return Optional.of(order.getManager());
        }
        return Optional.ofNullable(company == null ? null : company.getManager());
    }

    private boolean isPayable(PaymentLink link) {
        return !link.getExpiresAt().isBefore(LocalDateTime.now())
                && (!isManualPayment(link) || hasEffectiveManualPaymentTarget(link))
                && link.getStatus() != PaymentLinkStatus.CONFIRMED
                && link.getStatus() != PaymentLinkStatus.AMOUNT_MISMATCH
                && link.getStatus() != PaymentLinkStatus.TEST_CONFIRMED
                && link.getStatus() != PaymentLinkStatus.CANCELED
                && link.getStatus() != PaymentLinkStatus.REVERSED
                && link.getStatus() != PaymentLinkStatus.PARTIAL_REVERSED
                && link.getStatus() != PaymentLinkStatus.REFUNDED
                && link.getStatus() != PaymentLinkStatus.PARTIAL_REFUNDED
                && link.getStatus() != PaymentLinkStatus.REJECTED
                && link.getStatus() != PaymentLinkStatus.EXPIRED
                && link.getStatus() != PaymentLinkStatus.AUTHORIZED
                && link.getStatus() != PaymentLinkStatus.NEEDS_RECONCILIATION
                && link.getStatus() != PaymentLinkStatus.FAILED;
    }

    private boolean isRefundable(PaymentLink link) {
        return link.getTbankPaymentId() != null
                && !link.getTbankPaymentId().isBlank()
                && REFUNDABLE_STATUSES.contains(link.getStatus());
    }

    private PaymentProfile ensurePaymentProfile(PaymentLink link) {
        PaymentProfile profile = resolvePaymentProfile(link);
        applyPaymentProfile(link, profile);
        return profile;
    }

    private PaymentProfile resolvePaymentProfile(PaymentLink link) {
        if (link.getPaymentProfile() != null) {
            return link.getPaymentProfile();
        }

        String terminalKey = normalize(link.getTbankTerminalKey());
        if (!terminalKey.isBlank()) {
            return paymentProfileService.findByTerminalKey(terminalKey)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "TerminalKey платежной ссылки не найден в настройках T-Bank"
                    ));
        }

        String profileCode = normalize(link.getPaymentProfileCode());
        if (!profileCode.isBlank()) {
            return paymentProfileService.findByCode(profileCode)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Платежный профиль T-Bank не найден в настройках"
                    ));
        }

        return selectProfile(link.getOrder());
    }

    private TbankPaymentProfile runtimeProfileForLink(PaymentProfile profile, PaymentLink link) {
        String terminalKey = normalize(link.getTbankTerminalKey());
        if (terminalKey.isBlank()) {
            return paymentProfileService.toRuntime(profile);
        }
        return paymentProfileService.toRuntimeForTerminal(profile, terminalKey);
    }

    private PaymentProfile selectProfile(Order order) {
        return paymentProfileService.selectForManager(orderManager(order));
    }

    private Manager orderManager(Order order) {
        Manager manager = order == null ? null : order.getManager();
        if (manager == null && order != null) {
            Company company = order.getCompany();
            manager = company == null ? null : company.getManager();
        }
        return manager;
    }

    private boolean shouldUseManualPayment(
            PaymentProfile profile,
            long amountKopecks,
            LocalDateTime now,
            Long excludedLinkId
    ) {
        if (profile == null
                || profile.getPaymentPolicy() != PaymentPolicy.MANUAL_UNTIL_LIMIT_THEN_TBANK
                || !hasManualPaymentTarget(profile)) {
            return false;
        }
        long monthlyLimit = manualMonthlyHardLimit(profile);
        if (monthlyLimit <= 0 || amountKopecks <= 0 || profile.getId() == null) {
            return false;
        }

        LocalDateTime periodStart = now.toLocalDate().withDayOfMonth(1).atStartOfDay();
        LocalDateTime periodEnd = periodStart.plusMonths(1);
        long alreadyUsed = paymentLinkRepository.sumManualReservedAndConfirmedForPeriod(
                profile.getId(),
                MANUAL_PAYMENT_METHODS,
                MANUAL_USAGE_STATUSES,
                periodStart,
                periodEnd,
                now,
                PaymentLinkStatus.CONFIRMED,
                excludedLinkId
        );
        return alreadyUsed + amountKopecks <= monthlyLimit;
    }

    private long manualMonthlyHardLimit(PaymentProfile profile) {
        Long hardLimit = profile.getManualMonthlyHardLimitKopecks();
        if (hardLimit != null && hardLimit > 0) {
            return hardLimit;
        }
        Long softLimit = profile.getManualMonthlySoftLimitKopecks();
        return softLimit == null || softLimit <= 0
                ? PaymentProfile.DEFAULT_MANUAL_MONTHLY_LIMIT_KOPECKS
                : softLimit;
    }

    private boolean hasManualPaymentTarget(PaymentProfile profile) {
        if (manualPaymentType(profile) == ManualPaymentType.MOBILE_BANK) {
            return !normalize(profile.getManualPhone()).isBlank()
                    && !normalize(profile.getManualRecipientName()).isBlank();
        }
        return !manualPaymentUrlForRead(profile.getManualPaymentUrl()).isBlank();
    }

    private ManualPaymentType manualPaymentType(PaymentProfile profile) {
        return profile.getManualPaymentType() == null ? ManualPaymentType.MOBILE_BANK : profile.getManualPaymentType();
    }

    private ManualPaymentType manualPaymentType(ManualPaymentTask task) {
        return task.getManualPaymentType() == null ? ManualPaymentType.MOBILE_BANK : task.getManualPaymentType();
    }

    private PaymentMethod paymentMethodFor(ManualPaymentType type) {
        return type == ManualPaymentType.MOBILE_BANK
                ? PaymentMethod.MANUAL_MOBILE_BANK
                : PaymentMethod.MANUAL_EXTERNAL_LINK;
    }

    private String manualPaymentUrl(String value) {
        return PaymentUrlPolicy.requireOrDefault(
                value,
                ManualPaymentType.DEFAULT_EXTERNAL_PAYMENT_URL,
                PaymentUrlPolicy.Purpose.MANUAL_EXTERNAL,
                HttpStatus.BAD_GATEWAY,
                "Сохраненная ссылка ручной оплаты имеет недопустимый формат"
        );
    }

    private String manualPaymentUrlForRead(String value) {
        return PaymentUrlPolicy.safeOrDefault(
                value,
                ManualPaymentType.DEFAULT_EXTERNAL_PAYMENT_URL,
                PaymentUrlPolicy.Purpose.MANUAL_EXTERNAL
        );
    }

    private boolean hasEffectiveManualPaymentTarget(PaymentLink link) {
        boolean external = link.getPaymentMethod() == PaymentMethod.MANUAL_EXTERNAL_LINK
                || link.getManualPaymentType() == ManualPaymentType.EXTERNAL_LINK;
        if (external) {
            return !manualPaymentUrlForRead(link.getManualPaymentUrl()).isBlank();
        }
        boolean mobileBank = link.getPaymentMethod() == PaymentMethod.MANUAL_MOBILE_BANK
                || link.getManualPaymentType() == ManualPaymentType.MOBILE_BANK;
        return !mobileBank || !normalize(link.getManualPhone()).isBlank();
    }

    private String manualButtonLabel(String value) {
        String clean = limit(value, 80);
        return clean.isBlank() ? ManualPaymentType.DEFAULT_EXTERNAL_PAYMENT_BUTTON_LABEL : clean;
    }

    private String manualButtonLabel(PaymentLink link) {
        if (!isManualPayment(link)) {
            return "";
        }
        return manualButtonLabel(link.getManualPaymentButtonLabel());
    }

    private String manualRecipientName(String value) {
        String clean = limit(value, 160);
        return clean.isBlank() || ManualPaymentType.DEFAULT_EXTERNAL_PAYMENT_BUTTON_LABEL.equals(clean)
                ? ManualPaymentType.DEFAULT_MANUAL_RECIPIENT_NAME
                : clean;
    }

    private String manualRecipientName(PaymentLink link) {
        if (!isManualPayment(link)) {
            return "";
        }
        return manualRecipientName(link.getManualRecipientName());
    }

    private void applyManualProfilePayment(PaymentLink link, PaymentProfile profile) {
        ManualPaymentType type = manualPaymentType(profile);
        link.setPaymentMethod(paymentMethodFor(type));
        link.setManualPaymentType(type);
        link.setManualSource(ManualPaymentSource.PROFILE_MONTHLY_LIMIT);
        link.setManualPaymentTask(null);
        link.setStatus(PaymentLinkStatus.WAITING_MANUAL_PAYMENT);
        link.setManualPhone(limit(profile.getManualPhone(), 32));
        link.setManualRecipientName(manualRecipientName(profile.getManualRecipientName()));
        link.setManualPaymentUrl(manualPaymentUrl(profile.getManualPaymentUrl()));
        link.setManualPaymentButtonLabel(manualButtonLabel(profile.getManualPaymentButtonLabel()));
        link.setManualComment(manualComment(profile.getManualComment(), link));
        link.setReceiptStatus(PaymentReceiptStatus.PENDING);
    }

    private void applyManualTaskPayment(PaymentLink link, ManualPaymentTask task) {
        ManualPaymentType type = manualPaymentType(task);
        link.setPaymentMethod(paymentMethodFor(type));
        link.setManualPaymentType(type);
        link.setManualSource(ManualPaymentSource.MANUAL_TASK);
        link.setManualPaymentTask(task);
        link.setStatus(PaymentLinkStatus.WAITING_MANUAL_PAYMENT);
        link.setManualPhone(limit(task.getManualPhone(), 32));
        link.setManualRecipientName(manualRecipientName(task.getManualRecipientName()));
        link.setManualPaymentUrl(manualPaymentUrl(task.getManualPaymentUrl()));
        link.setManualPaymentButtonLabel(manualButtonLabel(task.getManualPaymentButtonLabel()));
        link.setManualComment(manualComment(task.getComment(), link));
        link.setReceiptStatus(PaymentReceiptStatus.PENDING);
    }

    private void applyPaymentProfile(PaymentLink link, PaymentProfile profile) {
        link.setPaymentProfile(profile);
        link.setPaymentProfileCode(profile.getCode());
        link.setPaymentProfileName(profile.getName());
    }

    private String paymentProfileCode(PaymentLink link) {
        String profileCode = normalize(link.getPaymentProfileCode());
        if (!profileCode.isBlank()) {
            return profileCode;
        }
        return profileForDisplay(link).getCode();
    }

    private String paymentProfileName(PaymentLink link) {
        String profileName = normalize(link.getPaymentProfileName());
        if (!profileName.isBlank()) {
            return profileName;
        }
        return profileForDisplay(link).getName();
    }

    private String manualSourceName(PaymentLink link) {
        return link.getManualSource() == null ? null : link.getManualSource().name();
    }

    private String manualPaymentTypeName(PaymentLink link) {
        if (!isManualPayment(link)) {
            return null;
        }
        return manualPaymentType(link).name();
    }

    private ManualPaymentType manualPaymentType(PaymentLink link) {
        if (link.getManualPaymentType() != null) {
            return link.getManualPaymentType();
        }
        return link.getPaymentMethod() == PaymentMethod.MANUAL_EXTERNAL_LINK
                ? ManualPaymentType.EXTERNAL_LINK
                : ManualPaymentType.MOBILE_BANK;
    }

    private Long manualTaskId(PaymentLink link) {
        ManualPaymentTask task = link.getManualPaymentTask();
        return task == null ? null : task.getId();
    }

    private String manualTaskTitle(PaymentLink link) {
        ManualPaymentTask task = link.getManualPaymentTask();
        if (task == null) {
            return "";
        }
        String recipient = normalize(task.getManualRecipientName());
        if (!recipient.isBlank()) {
            return recipient;
        }
        String label = normalize(task.getManualPaymentButtonLabel());
        return label.isBlank() ? "Ручное задание #" + task.getId() : label;
    }

    private PaymentProfile profileForDisplay(PaymentLink link) {
        if (link.getPaymentProfile() != null) {
            return link.getPaymentProfile();
        }
        String terminalKey = normalize(link.getTbankTerminalKey());
        if (!terminalKey.isBlank()) {
            Optional<PaymentProfile> byTerminal = paymentProfileService.findByTerminalKey(terminalKey);
            if (byTerminal.isPresent()) {
                return byTerminal.get();
            }
        }
        String profileCode = normalize(link.getPaymentProfileCode());
        if (!profileCode.isBlank()) {
            Optional<PaymentProfile> byCode = paymentProfileService.findByCode(profileCode);
            if (byCode.isPresent()) {
                return byCode.get();
            }
        }
        return selectProfile(link.getOrder());
    }

    private void markFinalBankStatus(
            PaymentLink link,
            PaymentLinkStatus status,
            String providerTerminalStatus
    ) {
        link.setStatus(status);
        link.setProviderTerminalStatus(normalize(providerTerminalStatus).toUpperCase(Locale.ROOT));
        link.setLastError(null);
        scheduleCommonInvoiceStandalonePaymentReversal(link, status);
    }

    private void scheduleCommonInvoiceStandalonePaymentReversal(
            PaymentLink link,
            PaymentLinkStatus terminalStatus
    ) {
        if (terminalStatus != PaymentLinkStatus.REVERSED
                && terminalStatus != PaymentLinkStatus.PARTIAL_REVERSED
                && terminalStatus != PaymentLinkStatus.REFUNDED
                && terminalStatus != PaymentLinkStatus.PARTIAL_REFUNDED) {
            return;
        }
        CommonBillingService commonBillingService = commonBillingServiceProvider.getIfAvailable();
        Order order = link == null ? null : link.getOrder();
        if (commonBillingService == null || link == null || link.getId() == null
                || order == null || order.getId() == null) {
            return;
        }
        Long orderId = order.getId();
        Long paymentLinkId = link.getId();
        Runnable reconciliation = () -> {
            try {
                commonBillingService.applyStandalonePaymentReversal(
                        orderId,
                        paymentLinkId,
                        terminalStatus
                );
            } catch (RuntimeException ex) {
                // The durable source link and provider status remain available for
                // the next common-invoice operation to fail closed. Do not roll
                // back an already committed provider webhook acknowledgement.
                log.error(
                        "Failed to quarantine common invoice after terminal payment reversal: orderId={}, linkId={}, status={}",
                        orderId,
                        paymentLinkId,
                        terminalStatus,
                        ex
                );
            }
        };
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
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
            log.warn(
                    "Skipped immediate common-invoice reversal quarantine because transaction synchronization is unavailable: orderId={}, linkId={}",
                    orderId,
                    paymentLinkId
            );
            return;
        }
        reconciliation.run();
    }

    private PaymentLinkStatus statusAfterUnsafeProviderUrl(String paymentId) {
        return normalize(paymentId).isBlank()
                ? PaymentLinkStatus.FAILED
                : PaymentLinkStatus.NEEDS_RECONCILIATION;
    }

    private void quarantineUnsafeProviderTarget(
            PaymentLink link,
            PaymentMethod paymentMethod,
            String errorCode,
            String reason
    ) {
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

    private boolean isFinalStatus(PaymentLinkStatus status) {
        return status == PaymentLinkStatus.TEST_CONFIRMED
                || status == PaymentLinkStatus.CONFIRMED
                || status == PaymentLinkStatus.AMOUNT_MISMATCH
                || status == PaymentLinkStatus.REJECTED
                || status == PaymentLinkStatus.CANCELED
                || status == PaymentLinkStatus.REVERSED
                || status == PaymentLinkStatus.PARTIAL_REVERSED
                || status == PaymentLinkStatus.REFUNDED
                || status == PaymentLinkStatus.PARTIAL_REFUNDED
                || status == PaymentLinkStatus.EXPIRED;
    }

    private PaymentLinkStatus statusAfterCancel(String status) {
        return switch (normalize(status).toUpperCase()) {
            case "REFUNDED" -> PaymentLinkStatus.REFUNDED;
            case "PARTIAL_REFUNDED" -> PaymentLinkStatus.PARTIAL_REFUNDED;
            case "REVERSED" -> PaymentLinkStatus.REVERSED;
            case "PARTIAL_REVERSED" -> PaymentLinkStatus.PARTIAL_REVERSED;
            case "CANCELED" -> PaymentLinkStatus.CANCELED;
            default -> throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Т-Банк вернул неподтвержденный статус возврата"
            );
        };
    }

    private BigDecimal payableSum(Order order) {
        BigDecimal baseSum = order.getSum() == null ? BigDecimal.ZERO : order.getSum();
        BadReviewTaskSummary summary = badReviewTaskService.getSummaryForOrder(order.getId());
        BigDecimal extra = summary == null ? BigDecimal.ZERO : summary.doneSum();
        return baseSum.add(extra);
    }

    private long amountKopecks(BigDecimal amount) {
        return amount
                .setScale(2, RoundingMode.HALF_UP)
                .movePointRight(2)
                .longValue();
    }

    private BigDecimal amountRubles(long amountKopecks) {
        return BigDecimal.valueOf(amountKopecks, 2);
    }

    private String description(Order order) {
        return PAYMENT_SERVICE_NAME;
    }

    private String paymentCopyText(PaymentLink link, String url) {
        String template = appSettingService.getString(
                AppSettingService.CLIENT_MESSAGES_PAYMENT_LINK_COPY_TEXT,
                ScheduledClientMessageService.DEFAULT_PAYMENT_LINK_COPY_TEXT
        );
        if (template == null || template.isBlank()) {
            template = ScheduledClientMessageService.DEFAULT_PAYMENT_LINK_COPY_TEXT;
        }
        String afterword = paymentAfterword(link);
        String text = renderPaymentTemplate(template, Map.ofEntries(
                Map.entry("company", companyTitle(link.getOrder())),
                Map.entry("filial", filialTitle(link.getOrder())),
                Map.entry("companyAndFilial", heading(link.getOrder())),
                Map.entry("sum", amountRubles(link.getAmountKopecks()).stripTrailingZeros().toPlainString()),
                Map.entry("paymentInstruction", paymentInstructionText(link, url)),
                Map.entry("paymentLink", paymentLinkValue(link, url)),
                Map.entry("tbankPaymentLink", url),
                Map.entry("recipient", manualRecipientName(link)),
                Map.entry("comment", manualComment(link)),
                Map.entry("paymentAfterword", afterword),
                Map.entry("afterword", afterword)
        ));
        return isManualPayment(link) ? text : removeReceiptRequest(text);
    }

    public record PaymentLinkReconcileResult(
            Long linkId,
            PaymentLinkStatus statusBefore,
            PaymentLinkStatus statusAfter,
            boolean changed
    ) {
    }

    private String paymentInstructionText(PaymentLink link, String url) {
        if (!isManualPayment(link)) {
            return "Ссылка на оплату: " + url;
        }
        String comment = manualComment(link);
        if (manualPaymentType(link) == ManualPaymentType.EXTERNAL_LINK) {
            return manualPaymentInstruction(
                    "Ссылка на оплату: " + manualPaymentUrlForRead(link.getManualPaymentUrl()),
                    manualRecipientName(link),
                    comment
            );
        }
        return manualPaymentInstruction(
                mobileBankPaymentLine(link),
                manualRecipientName(link),
                comment
        );
    }

    private String mobileBankPaymentLine(PaymentLink link) {
        String value = normalize(link.getManualPhone());
        String label = looksLikeCardNumber(value)
                ? "Оплата по номеру карты: "
                : "Оплата по мобильному банку: ";
        return label + value;
    }

    private boolean looksLikeCardNumber(String value) {
        String digits = normalize(value).replaceAll("\\D", "");
        return digits.length() >= 13 && digits.length() <= 19;
    }

    private String manualPaymentInstruction(String paymentLine, String recipient, String comment) {
        String cleanComment = normalize(comment);
        if (cleanComment.isBlank()) {
            return String.join("\n",
                    paymentLine,
                    "Получатель: " + recipient
            );
        }
        return String.join("\n",
                paymentLine,
                "Получатель: " + recipient,
                "Комментарий: " + cleanComment
        );
    }

    private String paymentAfterword(PaymentLink link) {
        if (!isManualPayment(link)) {
            return "";
        }
        return "После оплаты отправьте чек в этот чат.";
    }

    private String removeReceiptRequest(String text) {
        return normalizeText(text
                .replaceAll("(?iu)\\n?\\s*После оплаты отправьте чек в этот чат\\.?", "")
                .replaceAll("(?iu)\\n?\\s*Пришлите чек,? пожалуйста,? как оплатите\\.?", ""));
    }

    private String paymentLinkValue(PaymentLink link, String url) {
        if (!isManualPayment(link)) {
            return url;
        }
        if (manualPaymentType(link) == ManualPaymentType.EXTERNAL_LINK) {
            return manualPaymentUrlForRead(link.getManualPaymentUrl());
        }
        return normalize(link.getManualPhone());
    }

    private String renderPaymentTemplate(String template, Map<String, String> variables) {
        String result = template == null ? "" : template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue() == null ? "" : entry.getValue());
        }
        return result
                .replace("\r\n", "\n")
                .replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private String normalizeText(String result) {
        return (result == null ? "" : result)
                .replace("\r\n", "\n")
                .replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private String manualComment(PaymentLink link) {
        String stored = normalize(link.getManualComment());
        if (!stored.isBlank()) {
            return stored;
        }
        return "";
    }

    private String manualComment(String template, PaymentLink link) {
        String clean = limit(template, 255);
        if (clean.isBlank()) {
            return null;
        }
        String comment = limit(clean.replace("{orderId}", orderIdText(link)), 255);
        return comment.isBlank() ? null : comment;
    }

    private String orderIdText(PaymentLink link) {
        Long orderId = link.getOrder() == null ? null : link.getOrder().getId();
        return orderId == null ? "" : String.valueOf(orderId);
    }

    private String heading(Order order) {
        String company = companyTitle(order);
        String filial = filialTitle(order);
        if (company.isBlank()) {
            return filial;
        }
        if (filial.isBlank()) {
            return company;
        }
        return company + " - " + filial;
    }

    private String companyTitle(Order order) {
        Company company = order == null ? null : order.getCompany();
        return company == null ? "" : normalize(company.getTitle());
    }

    private String filialTitle(Order order) {
        Filial filial = order == null ? null : order.getFilial();
        return filial == null ? "" : normalize(filial.getTitle());
    }

    private String defaultPayerEmail(Order order) {
        Company company = order == null ? null : order.getCompany();
        return company == null ? "" : normalizeEmail(company.getLastPayerEmail());
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

    private String publicPaymentUrl(PaymentLink link) {
        return properties.getPublicBaseUrl() + "/pay/" + link.getToken();
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

    private String paymentMethodName(PaymentLink link) {
        return link.getPaymentMethod() == null ? PaymentMethod.BANK_FORM.name() : link.getPaymentMethod().name();
    }

    private boolean isManualPayment(PaymentLink link) {
        return link != null && MANUAL_PAYMENT_METHODS.contains(link.getPaymentMethod());
    }

    private String newToken() {
        byte[] bytes = new byte[24];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String normalizeEmail(String email) {
        return normalize(email).toLowerCase();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String cleanDeviceType(String value) {
        String clean = normalize(value).toLowerCase(Locale.ROOT);
        return "desktop".equals(clean) ? "desktop" : "mobile";
    }

    private int featuredBankRank(String bankName) {
        String clean = normalize(bankName).toLowerCase(Locale.ROOT);
        for (int i = 0; i < FEATURED_SBP_BANK_PATTERNS.size(); i++) {
            if (clean.contains(FEATURED_SBP_BANK_PATTERNS.get(i))) {
                return i;
            }
        }
        return FEATURED_SBP_BANK_PATTERNS.size();
    }

    private String limit(String value, int maxLength) {
        String clean = normalize(value);
        if (clean.length() <= maxLength) {
            return clean;
        }
        return clean.substring(0, maxLength);
    }

    private record VerifiedWebhookProfile(PaymentProfile profile, TbankPaymentProfile runtimeProfile) {
    }

    private record SbpBankListRequest(
            TbankPaymentProfile runtimeProfile,
            TbankGetQrBankListCommand command
    ) {
    }

    private record BankStateObservation(
            Long linkId,
            Long orderId,
            String token,
            String paymentId,
            String tbankOrderId,
            String terminalKey,
            long amountKopecks,
            PaymentLinkStatus status,
            TbankGetStateResponse state
    ) {
    }

    private record PublicBankStateProbe(
            boolean attempted,
            LocalDateTime attemptedAt,
            BankStateObservation observation
    ) {
        private static PublicBankStateProbe skipped() {
            return new PublicBankStateProbe(false, null, null);
        }
    }

    private record CancelReservation(
            Long linkId,
            Long orderId,
            PaymentLinkStatus status,
            String nonce,
            String paymentId,
            String tbankOrderId,
            long amountKopecks,
            String terminalKey,
            TbankPaymentProfile runtimeProfile
    ) {
    }

    private record ManualCardPaymentPlan(
            Long linkId,
            Long orderId,
            String paymentId,
            String tbankOrderId,
            long amountKopecks,
            CancelReservation cancelReservation
    ) {
    }

    private enum BankInitReservationRecovery {
        NONE,
        ACTIVE,
        RETRYABLE_RECOVERED,
        QUARANTINED
    }

    private enum BankInitMode {
        BANK_FORM(PaymentMethod.BANK_FORM),
        SBP_QR(PaymentMethod.SBP_QR);

        private final PaymentMethod paymentMethod;

        BankInitMode(PaymentMethod paymentMethod) {
            this.paymentMethod = paymentMethod;
        }

        private PaymentMethod paymentMethod() {
            return paymentMethod;
        }
    }

    private record BankInitReservation(
            Long linkId,
            Long orderId,
            String token,
            String nonce,
            String tbankOrderId,
            String paymentId,
            String paymentUrl,
            long amountKopecks,
            String description,
            String email,
            String bankId,
            String terminalKey,
            TbankPaymentProfile runtimeProfile,
            BankInitMode mode,
            PublicPaymentInitResponse cachedResponse
    ) {
    }

    private record BankInitApplyResult(
            PublicPaymentInitResponse response,
            String paymentId,
            String paymentUrl,
            HttpStatus errorStatus,
            String errorReason
    ) {
        private static BankInitApplyResult success(
                PublicPaymentInitResponse response,
                String paymentId,
                String paymentUrl
        ) {
            return new BankInitApplyResult(response, paymentId, paymentUrl, null, null);
        }

        private static BankInitApplyResult error(HttpStatus status, String reason) {
            return new BankInitApplyResult(null, null, null, status, reason);
        }
    }

    private record PublicLinkRefresh(
            PublicPaymentLinkResponse response,
            Long linkId,
            Long orderId,
            boolean replacementRequired
    ) {
    }
}
