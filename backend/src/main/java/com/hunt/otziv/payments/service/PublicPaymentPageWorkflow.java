package com.hunt.otziv.payments.service;

import com.hunt.otziv.contractor_payments.service.ContractorPaymentLiveRoutingService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.payments.dto.PublicPaymentLinkResponse;
import com.hunt.otziv.payments.dto.PublicSbpBankResponse;
import com.hunt.otziv.payments.dto.TbankGetQrBankListCommand;
import com.hunt.otziv.payments.dto.TbankGetQrBankListResponse;
import com.hunt.otziv.payments.dto.TbankPaymentProfile;
import com.hunt.otziv.payments.model.ManualPaymentType;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.model.PaymentMethod;
import com.hunt.otziv.payments.model.PaymentProfile;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static com.hunt.otziv.payments.service.PaymentLinkPreparationWorkflow.REUSABLE_STATUSES;
import static com.hunt.otziv.payments.service.BankInitializationStateService.BankInitReservationRecovery;
import static com.hunt.otziv.payments.service.CommonInvoiceRouteSelector.ManualRouteReadView;
import static com.hunt.otziv.payments.service.PaymentBankObservationService.ProviderStateObservation;
import static com.hunt.otziv.payments.service.PaymentBankObservationService.PublicBankStateProbe;

@Service
@Slf4j
@RequiredArgsConstructor
/**
 * Resolves public payment pages and client transfer reports. A page read may apply
 * a bank observation or resolve a retired attempt, so this is a workflow rather
 * than a read-only query; provider I/O stays outside its locked database phases.
 */
public class PublicPaymentPageWorkflow {

    private final PaymentLinkLifecycleService lifecycleService;

    private final ManualPaymentConfirmationWorkflow manualConfirmationWorkflow;

    private final BankInitializationStateService bankInitializationState;

    private final PublicPaymentLinkResolutionService publicLinkResolution;

    private final BankObservationApplicationService bankObservationApplication;

    private final PaymentLinkCancellationWorkflow cancellationWorkflow;

    private final PaymentLinkPresenter paymentPresenter;

    private final CommonInvoiceRouteSelector commonInvoiceRouteSelector;

    private final PaymentBankObservationService bankObservations;

    static final List<String> FEATURED_SBP_BANK_PATTERNS = List.of("сбер", "т-банк", "t-bank", "тинькофф", "альфа", "втб", "газпром", "райфф", "совком", "мтс", "ozon", "озон", "яндекс", "псб", "промсвяз");

    private final PaymentLinkRepository paymentLinkRepository;

    private final OrderRepository orderRepository;

    private final PaymentProfileService paymentProfileService;

    private final TbankClient tbankClient;

    private final ContractorPaymentLiveRoutingService contractorPaymentLiveRoutingService;

    private final PaymentLinkTransactionExecutor transactionExecutor;

    private boolean isPaperInvoice(PaymentLink link) {
        return paymentPresenter.isPaperInvoice(link);
    }

    private boolean isFrozenContractorRoute(PaymentLink link) {
        return commonInvoiceRouteSelector.isFrozenContractorRoute(link);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PublicPaymentLinkResponse publicLink(String token) {
        PaymentLink snapshot = findPublicLink(token);
        PublicBankStateProbe probe = observePublicBankState(snapshot);
        Long snapshotOrderId = snapshot.getOrder() == null ? null : snapshot.getOrder().getId();
        PublicLinkRefresh refreshed = transactionExecutor.required(() -> refreshPublicLink(token, probe, snapshotOrderId));
        if (!refreshed.replacementRequired() || refreshed.orderId() == null) {
            return refreshed.response();
        }
        Long replacementId = transactionExecutor.required(() -> resolveReplacementOrderFirst(refreshed.orderId(), refreshed.linkId()));
        if (replacementId == null || replacementId.equals(refreshed.linkId())) {
            return refreshed.response();
        }
        PaymentLink replacementSnapshot = paymentLinkRepository.findByIdWithOrder(replacementId).orElse(null);
        if (replacementSnapshot == null) {
            return refreshed.response();
        }
        PublicBankStateProbe replacementProbe = observePublicBankState(replacementSnapshot);
        PublicPaymentLinkResponse currentReplacement = transactionExecutor.required(() -> refreshPublicReplacement(replacementId, replacementProbe));
        return currentReplacement == null ? refreshed.response() : currentReplacement;
    }

    private PublicLinkRefresh refreshPublicLink(String token, PublicBankStateProbe probe, Long snapshotOrderId) {
        ProviderStateObservation observation = probe.observation();
        Long lockedOrderId = snapshotOrderId == null ? lockObservedOrderFirst(observation) : orderRepository.findByIdForCounterUpdate(snapshotOrderId).map(Order::getId).orElse(null);
        PaymentLink link = findPublicLinkForUpdateStrict(token);
        markPublicBankStateAttempt(link, probe);
        if (hasOrderBinding(link, lockedOrderId)) {
            recoverExpiredBankInitReservationLocked(link, LocalDateTime.now(), "public_get");
        }
        applyObservedBankStateIfCurrent(link, observation, lockedOrderId);
        expireIfPastDue(link);
        expireIfAmountChanged(link);
        LocalDateTime now = LocalDateTime.now();
        return new PublicLinkRefresh(toPublicResponse(link), link.getId(), link.getOrder() == null ? null : link.getOrder().getId(), shouldResolveReplacementPublicLink(link, now));
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
        Optional<PaymentLink> existing = paymentLinkRepository.findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(orderId, REUSABLE_STATUSES, now).filter(candidate -> candidate.getId() == null || !candidate.getId().equals(sourceLinkId));
        if (existing.isPresent()) {
            return existing.get().getId();
        }
        return createReplacementPublicLink(orderId, now).map(PaymentLink::getId).orElse(null);
    }

    private PublicPaymentLinkResponse refreshPublicReplacement(Long linkId, PublicBankStateProbe probe) {
        ProviderStateObservation observation = probe.observation();
        Long lockedOrderId = lockObservedOrderFirst(observation);
        PaymentLink link = paymentLinkRepository.findByIdForUpdate(linkId).orElse(null);
        if (link == null) {
            return null;
        }
        markPublicBankStateAttempt(link, probe);
        applyObservedBankStateIfCurrent(link, observation, lockedOrderId);
        expireIfPastDue(link);
        expireIfAmountChanged(link);
        return toPublicResponse(link);
    }

    /**
     * A public payment page may be reloaded several times at once (focus,
     * pageshow and a manual refresh). Keep those reads public, but do not turn
     * every reload into another provider request. The durable timestamp also
     * coordinates the public path with scheduled reconciliation; the local
     * atomic claim closes the gap before that timestamp is committed.
     */
    private PublicBankStateProbe observePublicBankState(PaymentLink link) {
        return bankObservations.observePublicBankState(link);
    }

    private void markPublicBankStateAttempt(PaymentLink link, PublicBankStateProbe probe) {
        if (link != null && probe.attempted() && probe.attemptedAt() != null) {
            link.setBankReconciliationAttemptedAt(probe.attemptedAt());
        }
    }

    private void applyObservedBankStateIfCurrent(PaymentLink link, ProviderStateObservation observation, Long lockedOrderId) {
        bankObservationApplication.applyObservedBankStateIfCurrent(link, observation, lockedOrderId);
    }

    private Long lockObservedOrderFirst(ProviderStateObservation observation) {
        if (observation == null || observation.orderId() == null) {
            return null;
        }
        return orderRepository.findByIdForCounterUpdate(observation.orderId()).map(Order::getId).orElse(null);
    }

    private boolean hasOrderBinding(PaymentLink link, Long orderId) {
        return lifecycleService.hasOrderBinding(link, orderId);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<PublicSbpBankResponse> publicSbpBanks(String token, String deviceType, String os) {
        SbpBankListRequest request = transactionExecutor.readOnly(() -> {
            PaymentLink link = resolveReplacementPublicLink(findPublicLink(token), LocalDateTime.now(), false);
            validatePayable(link);
            validateTbankPayment(link);
            PaymentProfile profile = resolvePaymentProfile(link);
            if (paymentProfileService.isTochkaProvider(profile)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Выбор банка СБП не используется для платежной страницы Точки");
            }
            return new SbpBankListRequest(runtimeProfileForLink(profile, link), new TbankGetQrBankListCommand("qr", cleanDeviceType(deviceType), limit(os, 255)));
        });
        TbankGetQrBankListResponse response = tbankClient.getQrBankList(request.runtimeProfile(), request.command());
        return response.safeBanks().stream().map(this::toPublicSbpBankResponse).filter(bank -> !bank.bankId().isBlank() && !bank.name().isBlank()).sorted(Comparator.comparingInt((PublicSbpBankResponse bank) -> featuredBankRank(bank.name())).thenComparing(bank -> bank.order() == null ? Integer.MAX_VALUE : bank.order()).thenComparing(PublicSbpBankResponse::name, String.CASE_INSENSITIVE_ORDER)).toList();
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

    @Transactional(noRollbackFor = ResponseStatusException.class)
    public PublicPaymentLinkResponse reportManualPayment(String token) {
        PaymentLink link = findPublicLinkForUpdateStrict(token);
        if (isFrozenContractorRoute(link)) {
            contractorPaymentLiveRoutingService.validatePaymentLinkClientReportedRoute(link);
        }
        validatePayable(link, true);
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
            reconcileContractorPaymentRouteAfterCommit(link.getId());
        }
        return toPublicResponse(link);
    }

    private BankInitReservationRecovery recoverExpiredBankInitReservationLocked(PaymentLink link, LocalDateTime now, String source) {
        return bankInitializationState.recoverExpiredBankInitReservationLocked(link, now, source);
    }

    private PaymentLink findPublicLink(String token) {
        return publicLinkResolution.findPublicLink(token);
    }

    private PaymentLink findPublicLinkForUpdateStrict(String token) {
        return publicLinkResolution.findPublicLinkForUpdateStrict(token);
    }

    private PaymentLink resolveReplacementPublicLink(PaymentLink link, LocalDateTime now, boolean createIfMissing) {
        return publicLinkResolution.resolveReplacementPublicLink(link, now, createIfMissing);
    }

    private Optional<PaymentLink> createReplacementPublicLink(Long orderId, LocalDateTime now) {
        return publicLinkResolution.createReplacementPublicLink(orderId, now);
    }

    private boolean shouldResolveReplacementPublicLink(PaymentLink link, LocalDateTime now) {
        return publicLinkResolution.shouldResolveReplacementPublicLink(link, now);
    }

    private void validatePayable(PaymentLink link) {
        lifecycleService.validatePayable(link);
    }

    private void validatePayable(PaymentLink link, boolean releaseTaskReservationOnExpiry) {
        lifecycleService.validatePayable(link, releaseTaskReservationOnExpiry);
    }

    private void expireIfPastDue(PaymentLink link) {
        lifecycleService.expireIfPastDue(link);
    }

    private boolean expireIfAmountChanged(PaymentLink link) {
        return lifecycleService.expireIfAmountChanged(link);
    }

    private void validateTbankPayment(PaymentLink link) {
        lifecycleService.validateTbankPayment(link);
    }

    private void ensureManualPayment(PaymentLink link) {
        manualConfirmationWorkflow.ensureManualPayment(link);
    }

    private void validateManualPaymentTargetAvailable(PaymentLink link) {
        if (isPaperInvoice(link)) {
            if (link.getPaperInvoiceIssuedAt() == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Сначала отметьте, что бумажный счёт отправлен клиенту");
            }
            return;
        }
        ManualRouteReadView requisites = manualRouteReadView(link);
        boolean external = link.getPaymentMethod() == PaymentMethod.MANUAL_EXTERNAL_LINK || link.getManualPaymentType() == ManualPaymentType.EXTERNAL_LINK;
        boolean mobileBank = link.getPaymentMethod() == PaymentMethod.MANUAL_MOBILE_BANK || link.getManualPaymentType() == ManualPaymentType.MOBILE_BANK;
        if (external && requisites.paymentUrl().isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ссылка ручной оплаты отсутствует или имеет недопустимый формат");
        }
        if (mobileBank && requisites.phone().isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Телефон для ручной оплаты через мобильный банк не указан");
        }
    }

    private PublicPaymentLinkResponse toPublicResponse(PaymentLink link) {
        return paymentPresenter.toPublicResponse(link);
    }

    private PublicSbpBankResponse toPublicSbpBankResponse(TbankGetQrBankListResponse.TbankSbpBank bank) {
        String name = normalize(bank.bankName());
        return new PublicSbpBankResponse(normalize(bank.bankId()), normalize(bank.nspkBankId()), name, normalize(bank.bankLogo()), bank.bankOrder(), featuredBankRank(name) < FEATURED_SBP_BANK_PATTERNS.size());
    }

    private PaymentProfile resolvePaymentProfile(PaymentLink link) {
        return bankObservations.resolvePaymentProfile(link);
    }

    private TbankPaymentProfile runtimeProfileForLink(PaymentProfile profile, PaymentLink link) {
        return bankObservations.runtimeProfileForLink(profile, link);
    }

    private ManualRouteReadView manualRouteReadView(PaymentLink link) {
        return paymentPresenter.manualRouteReadView(link);
    }

    private String normalize(String value) {
        return paymentPresenter.normalize(value);
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
        return commonInvoiceRouteSelector.limit(value, maxLength);
    }

    record SbpBankListRequest(TbankPaymentProfile runtimeProfile, TbankGetQrBankListCommand command) {
    }

    record PublicLinkRefresh(PublicPaymentLinkResponse response, Long linkId, Long orderId, boolean replacementRequired) {
    }
}
