package com.hunt.otziv.payments;

import com.hunt.otziv.bad_reviews.dto.BadReviewTaskSummary;
import com.hunt.otziv.bad_reviews.services.BadReviewTaskService;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.client_messages.ClientMessageSendResult;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.services.service.OrderTransactionService;
import com.hunt.otziv.payments.dto.AdminPaymentLinkResponse;
import com.hunt.otziv.payments.dto.ManagerPaymentLinkResponse;
import com.hunt.otziv.payments.dto.PublicPaymentInitResponse;
import com.hunt.otziv.payments.dto.PublicPaymentLinkResponse;
import com.hunt.otziv.payments.dto.PublicSbpBankResponse;
import com.hunt.otziv.u_users.model.Manager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentLinkService {

    private static final String PAYMENT_SERVICE_NAME = "Репутационное сопровождение компании в сети Интернет";
    private static final String OFFER_PATH = "/offer";
    private static final String PRIVACY_PATH = "/privacy";
    private static final String RECEIPT_CONSENT_PATH = "/receipt-consent";
    private static final Set<PaymentLinkStatus> REUSABLE_STATUSES = Set.of(
            PaymentLinkStatus.CREATED,
            PaymentLinkStatus.INITIATED,
            PaymentLinkStatus.AUTHORIZED,
            PaymentLinkStatus.WAITING_MANUAL_PAYMENT,
            PaymentLinkStatus.MANUAL_REPORTED
    );
    private static final Set<PaymentLinkStatus> MUTABLE_REUSABLE_STATUSES = Set.of(
            PaymentLinkStatus.CREATED,
            PaymentLinkStatus.WAITING_MANUAL_PAYMENT
    );
    private static final Set<PaymentLinkStatus> REFUNDABLE_STATUSES = Set.of(
            PaymentLinkStatus.AUTHORIZED,
            PaymentLinkStatus.TEST_CONFIRMED,
            PaymentLinkStatus.CONFIRMED
    );
    private static final Set<PaymentLinkStatus> SYNCABLE_BANK_STATUSES = Set.of(
            PaymentLinkStatus.INITIATED,
            PaymentLinkStatus.AUTHORIZED
    );
    private static final Set<PaymentLinkStatus> MANUAL_USAGE_STATUSES = Set.of(
            PaymentLinkStatus.WAITING_MANUAL_PAYMENT,
            PaymentLinkStatus.MANUAL_REPORTED,
            PaymentLinkStatus.CONFIRMED
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

    private final PaymentLinkRepository paymentLinkRepository;
    private final OrderRepository orderRepository;
    private final BadReviewTaskService badReviewTaskService;
    private final OrderTransactionService orderTransactionService;
    private final TbankPaymentProperties properties;
    private final TbankRuntimeSettingsService runtimeSettingsService;
    private final PaymentProfileService paymentProfileService;
    private final TbankClient tbankClient;
    private final TbankTokenSigner tokenSigner;
    private final PaymentSuccessClientNotifier paymentSuccessClientNotifier;
    private final ManualPaymentTaskService manualPaymentTaskService;
    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional
    public ManagerPaymentLinkResponse createForOrder(Long orderId) {
        if (!runtimeSettingsService.isPaymentLinksEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Платежные ссылки выключены в настройках");
        }

        LocalDateTime now = LocalDateTime.now();
        expireStaleManualLinks(now);

        Order order = orderRepository.findByIdForMutation(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ не найден"));

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
            ensurePaymentProfile(link);
            if (!MUTABLE_REUSABLE_STATUSES.contains(link.getStatus())) {
                return toManagerResponse(link);
            }

            PaymentLink candidate = newPaymentLink(order, amountKopecks, now);
            applyPaymentProfile(candidate, profile);
            routePayment(candidate, manager, profile, amountKopecks, now, link.getId());
            if (canReuseLink(link, candidate)) {
                return toManagerResponse(link);
            }

            retireStaleReusableLink(link);
        }

        PaymentLink link = newPaymentLink(order, amountKopecks, now);
        applyPaymentProfile(link, profile);
        routePayment(link, manager, profile, amountKopecks, now, null);
        return toManagerResponse(paymentLinkRepository.save(link));
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

    @Transactional
    public PublicPaymentLinkResponse publicLink(String token) {
        PaymentLink link = findPublicLink(token);
        syncTbankStateIfNeeded(link);
        expireIfPastDue(link);
        return toPublicResponse(link);
    }

    @Transactional(readOnly = true)
    public List<PublicSbpBankResponse> publicSbpBanks(String token, String deviceType, String os) {
        PaymentLink link = findPublicLink(token);
        validatePayable(link);
        validateTbankPayment(link);

        PaymentProfile profile = resolvePaymentProfile(link);
        TbankPaymentProfile runtimeProfile = runtimeProfileForLink(profile, link);
        TbankGetQrBankListResponse response = tbankClient.getQrBankList(runtimeProfile, new TbankGetQrBankListCommand(
                "qr",
                cleanDeviceType(deviceType),
                limit(os, 255)
        ));

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
    public List<AdminPaymentLinkResponse> adminLinks() {
        expireStaleManualLinks(LocalDateTime.now());
        List<PaymentLink> links = paymentLinkRepository.findTop100ByOrderByCreatedAtDesc();
        links.forEach(this::syncTbankStateIfNeeded);
        return links.stream()
                .map(this::toAdminResponse)
                .toList();
    }

    @Transactional
    public AdminPaymentLinkResponse cancel(Long linkId) {
        PaymentLink link = paymentLinkRepository.findByIdWithOrder(linkId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежная ссылка не найдена"));
        if (!isRefundable(link)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Платеж не готов к возврату через T-Bank");
        }

        PaymentProfile profile = resolvePaymentProfile(link);
        TbankPaymentProfile runtimeProfile = runtimeProfileForLink(profile, link);
        TbankCancelResponse response = tbankClient.cancel(runtimeProfile, new TbankCancelCommand(
                link.getTbankPaymentId(),
                link.getAmountKopecks()
        ));

        link.setStatus(statusAfterCancel(response.status()));
        link.setLastError(null);
        paymentLinkRepository.save(link);
        return toAdminResponse(link);
    }

    @Transactional
    public AdminPaymentLinkResponse confirmManual(Long linkId, String confirmedBy) {
        PaymentLink link = paymentLinkRepository.findByIdWithOrder(linkId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежная ссылка не найдена"));
        ensureManualPayment(link);
        validateManualConfirmable(link);

        try {
            orderTransactionService.handlePaymentStatus(link.getOrder());
            LocalDateTime now = LocalDateTime.now();
            link.setStatus(PaymentLinkStatus.CONFIRMED);
            link.setPaidAt(now);
            link.setManualConfirmedAt(now);
            link.setManualConfirmedBy(limit(confirmedBy, 160));
            link.setConfirmedAmountKopecks(link.getAmountKopecks());
            link.setReceiptStatus(PaymentReceiptStatus.PENDING);
            link.setLastError(null);
            paymentLinkRepository.save(link);
            manualPaymentTaskService.completeIfConfirmedTargetReached(link.getManualPaymentTask());
            return toAdminResponse(link);
        } catch (Exception e) {
            link.setStatus(PaymentLinkStatus.FAILED);
            link.setLastError("Manual payment transition failed");
            paymentLinkRepository.save(link);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Не удалось подтвердить ручную оплату", e);
        }
    }

    @Transactional
    public AdminPaymentLinkResponse markManualReceipt(Long linkId, String confirmedBy) {
        PaymentLink link = paymentLinkRepository.findByIdWithOrder(linkId)
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

    @Transactional(noRollbackFor = ResponseStatusException.class)
    public PublicPaymentLinkResponse reportManualPayment(String token) {
        PaymentLink link = findPublicLink(token);
        validatePayable(link);
        ensureManualPayment(link);
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

    @Transactional(noRollbackFor = ResponseStatusException.class)
    public PublicPaymentInitResponse init(
            String token,
            String email,
            boolean offerConsent,
            boolean privacyConsent,
            boolean receiptConsent,
            String clientIp,
            String userAgent
    ) {
        PaymentLink link = findPublicLink(token);
        validatePayable(link);
        validateTbankPayment(link);
        validateConsents(offerConsent, privacyConsent, receiptConsent);

        String cleanEmail = normalizeEmail(email);
        if (cleanEmail.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Укажите e-mail для электронного чека");
        }

        PaymentProfile profile = ensurePaymentProfile(link);
        TbankPaymentProfile runtimeProfile = paymentProfileService.toRuntime(profile);
        if (link.getPaymentUrl() != null && !link.getPaymentUrl().isBlank()
                && link.getStatus() == PaymentLinkStatus.INITIATED) {
            applyConsentTrace(link, clientIp, userAgent);
            link.setPaymentMethod(PaymentMethod.BANK_FORM);
            paymentLinkRepository.save(link);
            return new PublicPaymentInitResponse(link.getPaymentUrl(), link.getTbankPaymentId(), link.getStatus().name());
        }

        link.setPayerEmail(cleanEmail);
        applyConsentTrace(link, clientIp, userAgent);
        link.setTbankOrderId(tbankOrderId(link));
        link.setTbankTerminalKey(runtimeProfile.terminalKey());

        TbankInitResponse response;
        try {
            response = tbankClient.init(runtimeProfile, new TbankInitCommand(
                    link.getTbankOrderId(),
                    link.getAmountKopecks(),
                    link.getDescription(),
                    cleanEmail,
                    properties.notificationUrl(),
                    properties.successUrl(),
                    properties.failUrl(),
                    OffsetDateTime.now(MOSCOW_ZONE).plus(properties.getRedirectDue())
            ));
        } catch (ResponseStatusException e) {
            String reason = normalize(e.getReason());
            link.setLastError(reason.isBlank() ? "T-Bank Init failed" : reason);
            paymentLinkRepository.save(link);
            log.warn(
                    "T-Bank Init failed: linkId={}, orderId={}, profile={}, terminal={}, status={}, reason={}",
                    link.getId(),
                    link.getOrder() == null ? null : link.getOrder().getId(),
                    profile.getCode(),
                    runtimeProfile.terminalKey(),
                    e.getStatusCode(),
                    link.getLastError()
            );
            throw e;
        }

        link.setStatus(PaymentLinkStatus.INITIATED);
        link.setPaymentMethod(PaymentMethod.BANK_FORM);
        link.setTbankPaymentId(response.paymentId());
        link.setPaymentUrl(response.paymentUrl());
        link.setInitiatedAt(LocalDateTime.now());
        link.setLastError(null);
        paymentLinkRepository.save(link);

        return new PublicPaymentInitResponse(response.paymentUrl(), response.paymentId(), link.getStatus().name());
    }

    @Transactional(noRollbackFor = ResponseStatusException.class)
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
        PaymentLink link = findPublicLink(token);
        validatePayable(link);
        validateTbankPayment(link);
        validateConsents(offerConsent, privacyConsent, receiptConsent);

        String cleanEmail = normalizeEmail(email);
        if (cleanEmail.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Укажите e-mail для электронного чека");
        }

        PaymentProfile profile = ensurePaymentProfile(link);
        String cleanBankId = normalize(sbpBankId);
        link.setPayerEmail(cleanEmail);
        applyConsentTrace(link, clientIp, userAgent);

        TbankPaymentProfile runtimeProfile;
        if (normalize(link.getTbankPaymentId()).isBlank()) {
            runtimeProfile = paymentProfileService.toRuntime(profile);
            link.setTbankOrderId(tbankOrderId(link));
            link.setTbankTerminalKey(runtimeProfile.terminalKey());
            try {
                TbankInitResponse response = tbankClient.init(runtimeProfile, new TbankInitCommand(
                        link.getTbankOrderId(),
                        link.getAmountKopecks(),
                        link.getDescription(),
                        cleanEmail,
                        properties.notificationUrl(),
                        properties.successUrl(),
                        properties.failUrl(),
                        OffsetDateTime.now(MOSCOW_ZONE).plus(properties.getRedirectDue())
                ));
                link.setStatus(PaymentLinkStatus.INITIATED);
                link.setTbankPaymentId(response.paymentId());
                link.setPaymentUrl(response.paymentUrl());
                link.setInitiatedAt(LocalDateTime.now());
                link.setLastError(null);
            } catch (ResponseStatusException e) {
                String reason = normalize(e.getReason());
                link.setLastError(reason.isBlank() ? "T-Bank Init failed" : reason);
                paymentLinkRepository.save(link);
                log.warn(
                        "T-Bank Init before SBP payload failed: linkId={}, orderId={}, profile={}, terminal={}, status={}, reason={}",
                        link.getId(),
                        link.getOrder() == null ? null : link.getOrder().getId(),
                        profile.getCode(),
                        runtimeProfile.terminalKey(),
                        e.getStatusCode(),
                        link.getLastError()
                );
                throw e;
            }
        } else {
            runtimeProfile = runtimeProfileForLink(profile, link);
        }

        if (link.getPaymentMethod() == PaymentMethod.SBP_QR
                && !normalize(link.getSbpQrPayload()).isBlank()
                && cleanBankId.isBlank()
                && link.getStatus() == PaymentLinkStatus.INITIATED) {
            paymentLinkRepository.save(link);
            return new PublicPaymentInitResponse(
                    link.getPaymentUrl(),
                    link.getTbankPaymentId(),
                    link.getStatus().name(),
                    PaymentMethod.SBP_QR.name(),
                    link.getSbpQrPayload(),
                    null
            );
        }

        TbankGetQrResponse qrResponse;
        try {
            qrResponse = tbankClient.getQr(runtimeProfile, new TbankGetQrCommand(
                    link.getTbankPaymentId(),
                    "PAYLOAD",
                    cleanBankId.isBlank() ? null : cleanBankId
            ));
        } catch (ResponseStatusException e) {
            String reason = normalize(e.getReason());
            link.setLastError(reason.isBlank() ? "T-Bank GetQr failed" : reason);
            paymentLinkRepository.save(link);
            log.warn(
                    "T-Bank GetQr failed: linkId={}, orderId={}, paymentId={}, profile={}, terminal={}, status={}, reason={}",
                    link.getId(),
                    link.getOrder() == null ? null : link.getOrder().getId(),
                    link.getTbankPaymentId(),
                    profile.getCode(),
                    runtimeProfile.terminalKey(),
                    e.getStatusCode(),
                    link.getLastError()
            );
            throw e;
        }

        String qrPayload = normalize(qrResponse.data());
        if (qrPayload.isBlank()) {
            link.setLastError("T-Bank GetQr returned empty SBP payload");
            paymentLinkRepository.save(link);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Т-Банк не вернул ссылку СБП");
        }

        link.setStatus(PaymentLinkStatus.INITIATED);
        link.setPaymentMethod(PaymentMethod.SBP_QR);
        link.setTbankTerminalKey(runtimeProfile.terminalKey());
        link.setSbpQrImage(null);
        link.setSbpQrPayload(qrPayload);
        link.setSbpQrDataType("PAYLOAD");
        link.setSbpQrCreatedAt(LocalDateTime.now());
        link.setLastError(null);
        paymentLinkRepository.save(link);

        return new PublicPaymentInitResponse(
                link.getPaymentUrl(),
                link.getTbankPaymentId(),
                link.getStatus().name(),
                PaymentMethod.SBP_QR.name(),
                qrPayload,
                null
        );
    }

    @Transactional
    public void handleTbankWebhook(Map<String, String> payload) {
        VerifiedWebhookProfile verified = verifyWebhook(payload);
        PaymentProfile profile = verified.profile();
        TbankPaymentProfile runtimeProfile = verified.runtimeProfile();

        String orderId = normalize(payload.get("OrderId"));
        String paymentId = normalize(payload.get("PaymentId"));
        Optional<PaymentLink> linkCandidate = !orderId.isBlank()
                ? paymentLinkRepository.findByTbankOrderIdWithOrder(orderId)
                : Optional.empty();
        if (linkCandidate.isEmpty() && !paymentId.isBlank()) {
            linkCandidate = paymentLinkRepository.findByTbankPaymentIdWithOrder(paymentId);
        }

        if (linkCandidate.isEmpty()) {
            log.warn("T-Bank webhook ignored: payment link not found for OrderId={}, PaymentId={}", orderId, paymentId);
            return;
        }

        PaymentLink link = linkCandidate.get();
        validateWebhookTerminal(link, runtimeProfile);
        validateWebhookAmount(link, payload);
        link.setTbankPaymentId(paymentId.isBlank() ? link.getTbankPaymentId() : paymentId);
        link.setTbankTerminalKey(runtimeProfile.terminalKey());
        applyPaymentProfile(link, profile);

        String status = normalize(payload.get("Status")).toUpperCase();
        boolean success = "true".equalsIgnoreCase(normalize(payload.get("Success")));
        String errorCode = normalize(payload.get("ErrorCode"));

        applyBankStatus(link, status, success, errorCode);

        paymentLinkRepository.save(link);
    }

    private void syncTbankStateIfNeeded(PaymentLink link) {
        if (!runtimeSettingsService.isTbankEnabled()
                || !SYNCABLE_BANK_STATUSES.contains(link.getStatus())
                || normalize(link.getTbankPaymentId()).isBlank()) {
            return;
        }

        try {
            PaymentProfile profile = resolvePaymentProfile(link);
            TbankPaymentProfile runtimeProfile = runtimeProfileForLink(profile, link);
            TbankGetStateResponse state = tbankClient.getState(runtimeProfile, link.getTbankPaymentId());
            if (!isStateConsistent(link, state, runtimeProfile)) {
                paymentLinkRepository.save(link);
                return;
            }

            link.setTbankTerminalKey(runtimeProfile.terminalKey());
            if (!normalize(state.paymentId()).isBlank()) {
                link.setTbankPaymentId(state.paymentId());
            }
            if (normalize(link.getTbankOrderId()).isBlank() && !normalize(state.orderId()).isBlank()) {
                link.setTbankOrderId(state.orderId());
            }
            applyPaymentProfile(link, profile);
            applyBankStatus(link, normalize(state.status()).toUpperCase(), state.success(), normalize(state.errorCode()));
            paymentLinkRepository.save(link);
        } catch (ResponseStatusException e) {
            log.warn(
                    "T-Bank GetState sync skipped: linkId={}, paymentId={}, status={}, reason={}",
                    link.getId(),
                    link.getTbankPaymentId(),
                    e.getStatusCode(),
                    normalize(e.getReason())
            );
        } catch (RuntimeException e) {
            log.warn(
                    "T-Bank GetState sync failed: linkId={}, paymentId={}",
                    link.getId(),
                    link.getTbankPaymentId(),
                    e
            );
        }
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

        return true;
    }

    private void applyBankStatus(PaymentLink link, String status, boolean success, String errorCode) {
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
                link.setLastError(errorCode);
            }
            case "CANCELED" -> markFinalBankStatus(link, PaymentLinkStatus.CANCELED);
            case "REVERSED" -> markFinalBankStatus(link, PaymentLinkStatus.REVERSED);
            case "PARTIAL_REVERSED" -> markFinalBankStatus(link, PaymentLinkStatus.PARTIAL_REVERSED);
            case "REFUNDED" -> markFinalBankStatus(link, PaymentLinkStatus.REFUNDED);
            case "PARTIAL_REFUNDED" -> markFinalBankStatus(link, PaymentLinkStatus.PARTIAL_REFUNDED);
            case "DEADLINE_EXPIRED" -> link.setStatus(PaymentLinkStatus.EXPIRED);
            default -> {
                if (!success && !errorCode.isBlank() && !"0".equals(errorCode)) {
                    link.setStatus(PaymentLinkStatus.FAILED);
                    link.setLastError(errorCode);
                }
                log.info("T-Bank status stored without final transition: linkId={}, status={}", link.getId(), status);
            }
        }
    }

    private void confirmPayment(PaymentLink link) {
        if (link.getStatus() == PaymentLinkStatus.CONFIRMED) {
            rememberCompanyPayerEmail(link);
            notifyPaymentSuccessIfNeeded(link);
            return;
        }
        if (link.getStatus() == PaymentLinkStatus.TEST_CONFIRMED) {
            rememberCompanyPayerEmail(link);
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
        try {
            orderTransactionService.handlePaymentStatus(link.getOrder());
            link.setStatus(PaymentLinkStatus.CONFIRMED);
            link.setPaidAt(LocalDateTime.now());
            link.setConfirmedAmountKopecks(link.getAmountKopecks());
            link.setLastError(null);
            rememberCompanyPayerEmail(link);
            notifyPaymentSuccessIfNeeded(link);
        } catch (Exception e) {
            link.setStatus(PaymentLinkStatus.FAILED);
            link.setLastError("Order payment transition failed");
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Не удалось перевести заказ в оплату", e);
        }
    }

    private void notifyPaymentSuccessIfNeeded(PaymentLink link) {
        if (link == null
                || link.getStatus() != PaymentLinkStatus.CONFIRMED
                || link.getPaymentSuccessNotifiedAt() != null) {
            return;
        }

        try {
            ClientMessageSendResult result = paymentSuccessClientNotifier.notifySuccess(link);
            if (result != null && result.sent()) {
                link.setPaymentSuccessNotifiedAt(LocalDateTime.now());
                link.setPaymentSuccessNotificationError(null);
                log.info(
                        "Payment success notification sent: linkId={}, orderId={}, channel={}",
                        link.getId(),
                        link.getOrder() == null ? null : link.getOrder().getId(),
                        result.channel()
                );
                return;
            }

            String error = paymentNotificationError(result);
            link.setPaymentSuccessNotificationError(limit(error, 512));
            log.warn(
                    "Payment success notification was not sent: linkId={}, orderId={}, error={}",
                    link.getId(),
                    link.getOrder() == null ? null : link.getOrder().getId(),
                    error
            );
        } catch (Exception e) {
            String error = e.getMessage() == null || e.getMessage().isBlank()
                    ? e.getClass().getSimpleName()
                    : e.getMessage();
            link.setPaymentSuccessNotificationError(limit(error, 512));
            log.warn(
                    "Payment success notification failed: linkId={}, orderId={}",
                    link.getId(),
                    link.getOrder() == null ? null : link.getOrder().getId(),
                    e
            );
        }
    }

    private String paymentNotificationError(ClientMessageSendResult result) {
        if (result == null) {
            return "notification_result_empty";
        }
        String code = normalize(result.errorCode());
        String message = normalize(result.errorMessage());
        if (code.isBlank()) {
            return message.isBlank() ? "notification_not_sent" : message;
        }
        return message.isBlank() ? code : code + ": " + message;
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

    private PaymentLink findPublicLink(String token) {
        String cleanToken = normalize(token);
        if (cleanToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежная ссылка не найдена");
        }
        return paymentLinkRepository.findByTokenWithOrder(cleanToken)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежная ссылка не найдена"));
    }

    private void validatePayable(PaymentLink link) {
        if (link.getExpiresAt().isBefore(LocalDateTime.now())) {
            link.setStatus(PaymentLinkStatus.EXPIRED);
            throw new ResponseStatusException(HttpStatus.GONE, "Срок действия платежной ссылки истек");
        }
        if (link.getStatus() == PaymentLinkStatus.CONFIRMED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Заказ уже оплачен");
        }
        if (link.getStatus() == PaymentLinkStatus.TEST_CONFIRMED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Тестовый платеж по ссылке уже подтвержден");
        }
        if (link.getStatus() == PaymentLinkStatus.CANCELED
                || link.getStatus() == PaymentLinkStatus.REVERSED
                || link.getStatus() == PaymentLinkStatus.PARTIAL_REVERSED
                || link.getStatus() == PaymentLinkStatus.REFUNDED
                || link.getStatus() == PaymentLinkStatus.PARTIAL_REFUNDED
                || link.getStatus() == PaymentLinkStatus.REJECTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Платежная ссылка недоступна");
        }
    }

    private void expireIfPastDue(PaymentLink link) {
        if (link.getExpiresAt() != null
                && link.getExpiresAt().isBefore(LocalDateTime.now())
                && (link.getStatus() == PaymentLinkStatus.WAITING_MANUAL_PAYMENT
                || link.getStatus() == PaymentLinkStatus.MANUAL_REPORTED
                || link.getStatus() == PaymentLinkStatus.CREATED)) {
            link.setStatus(PaymentLinkStatus.EXPIRED);
            link.setLastError("Срок действия платежной ссылки истек");
            paymentLinkRepository.save(link);
        }
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

    private void validateManualConfirmable(PaymentLink link) {
        if (link.getStatus() == PaymentLinkStatus.CONFIRMED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ручная оплата уже подтверждена");
        }
        if (link.getStatus() != PaymentLinkStatus.WAITING_MANUAL_PAYMENT
                && link.getStatus() != PaymentLinkStatus.MANUAL_REPORTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ручная оплата недоступна для подтверждения");
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
        String payerEmail = normalizeEmail(link.getPayerEmail());
        if (payerEmail.isBlank()) {
            payerEmail = defaultPayerEmail(order);
        }
        return new PublicPaymentLinkResponse(
                link.getToken(),
                order == null ? null : order.getId(),
                companyTitle(order),
                filialTitle(order),
                link.getDescription(),
                amountRubles(link.getAmountKopecks()),
                link.getAmountKopecks(),
                link.getDescription(),
                payerEmail,
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
                normalize(link.getManualPaymentUrl()),
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
                link.getPaymentUrl(),
                manualPaymentTypeName(link),
                normalize(link.getManualPhone()),
                manualRecipientName(link),
                normalize(link.getManualPaymentUrl()),
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
                && link.getStatus() != PaymentLinkStatus.CONFIRMED
                && link.getStatus() != PaymentLinkStatus.TEST_CONFIRMED
                && link.getStatus() != PaymentLinkStatus.CANCELED
                && link.getStatus() != PaymentLinkStatus.REVERSED
                && link.getStatus() != PaymentLinkStatus.PARTIAL_REVERSED
                && link.getStatus() != PaymentLinkStatus.REFUNDED
                && link.getStatus() != PaymentLinkStatus.PARTIAL_REFUNDED
                && link.getStatus() != PaymentLinkStatus.REJECTED
                && link.getStatus() != PaymentLinkStatus.EXPIRED
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
        return !normalize(profile.getManualPaymentUrl()).isBlank();
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
        String clean = limit(value, 512);
        return clean.isBlank() ? ManualPaymentType.DEFAULT_EXTERNAL_PAYMENT_URL : clean;
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
        link.setManualComment(manualComment(link));
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
        link.setManualComment(manualComment(link));
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

    private void markFinalBankStatus(PaymentLink link, PaymentLinkStatus status) {
        link.setStatus(status);
        link.setLastError(null);
    }

    private boolean isFinalStatus(PaymentLinkStatus status) {
        return status == PaymentLinkStatus.TEST_CONFIRMED
                || status == PaymentLinkStatus.CONFIRMED
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
            default -> PaymentLinkStatus.CANCELED;
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
        String greeting = "Здравствуйте, ваш заказ выполнен. К оплате: "
                + amountRubles(link.getAmountKopecks()).stripTrailingZeros().toPlainString()
                + " руб.";
        String afterword = paymentAfterword(link);
        if (afterword.isBlank()) {
            return String.join("\n\n", heading(link.getOrder()), greeting, paymentInstructionText(link, url));
        }
        return String.join("\n\n", heading(link.getOrder()), greeting, paymentInstructionText(link, url), afterword);
    }

    private String paymentInstructionText(PaymentLink link, String url) {
        if (!isManualPayment(link)) {
            return "Ссылка на оплату: " + url;
        }
        if (manualPaymentType(link) == ManualPaymentType.EXTERNAL_LINK) {
            return String.join("\n",
                    "Ссылка на оплату: " + manualPaymentUrl(link.getManualPaymentUrl()),
                    "Получатель: " + manualRecipientName(link),
                    "Комментарий: " + manualComment(link)
            );
        }
        return String.join("\n",
                "Ссылка на оплату: " + normalize(link.getManualPhone()),
                "Получатель: " + manualRecipientName(link),
                "Комментарий: " + manualComment(link)
        );
    }

    private String paymentAfterword(PaymentLink link) {
        if (!isManualPayment(link)) {
            return "";
        }
        return "После оплаты отправьте чек менеджеру.";
    }

    private String manualComment(PaymentLink link) {
        Long orderId = link.getOrder() == null ? null : link.getOrder().getId();
        return orderId == null ? "Оплата заказа" : "Оплата заказа №" + orderId;
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
}
