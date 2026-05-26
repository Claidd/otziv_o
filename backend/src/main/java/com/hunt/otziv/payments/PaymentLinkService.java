package com.hunt.otziv.payments;

import com.hunt.otziv.bad_reviews.dto.BadReviewTaskSummary;
import com.hunt.otziv.bad_reviews.services.BadReviewTaskService;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.services.service.OrderTransactionService;
import com.hunt.otziv.payments.dto.AdminPaymentLinkResponse;
import com.hunt.otziv.payments.dto.ManagerPaymentLinkResponse;
import com.hunt.otziv.payments.dto.PublicPaymentInitResponse;
import com.hunt.otziv.payments.dto.PublicPaymentLinkResponse;
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
import java.util.List;
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
            PaymentLinkStatus.AUTHORIZED
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
    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional
    public ManagerPaymentLinkResponse createForOrder(Long orderId) {
        if (!runtimeSettingsService.isPaymentLinksEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Платежные ссылки выключены в настройках");
        }

        LocalDateTime now = LocalDateTime.now();
        Optional<PaymentLink> existing = paymentLinkRepository
                .findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(orderId, REUSABLE_STATUSES, now);
        if (existing.isPresent()) {
            PaymentLink link = existing.get();
            ensurePaymentProfile(link);
            return toManagerResponse(link);
        }

        Order order = orderRepository.findByIdForMutation(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ не найден"));

        long amountKopecks = amountKopecks(payableSum(order));
        if (amountKopecks <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "У заказа нет суммы к оплате");
        }

        PaymentLink link = new PaymentLink();
        link.setToken(newToken());
        link.setOrder(order);
        link.setAmountKopecks(amountKopecks);
        link.setDescription(description(order));
        String defaultEmail = defaultPayerEmail(order);
        if (!defaultEmail.isBlank()) {
            link.setPayerEmail(defaultEmail);
        }
        link.setExpiresAt(now.plus(properties.getLinkTtl()));
        link.setStatus(PaymentLinkStatus.CREATED);
        applyPaymentProfile(link, selectProfile(order));
        return toManagerResponse(paymentLinkRepository.save(link));
    }

    @Transactional(readOnly = true)
    public PublicPaymentLinkResponse publicLink(String token) {
        PaymentLink link = findPublicLink(token);
        return toPublicResponse(link);
    }

    @Transactional
    public List<AdminPaymentLinkResponse> adminLinks() {
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
            String clientIp,
            String userAgent
    ) {
        PaymentLink link = findPublicLink(token);
        validatePayable(link);
        validateConsents(offerConsent, privacyConsent, receiptConsent);

        String cleanEmail = normalizeEmail(email);
        if (cleanEmail.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Укажите e-mail для электронного чека");
        }

        PaymentProfile profile = ensurePaymentProfile(link);
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
                        "T-Bank Init before SBP QR failed: linkId={}, orderId={}, profile={}, terminal={}, status={}, reason={}",
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
                && !normalize(link.getSbpQrImage()).isBlank()
                && link.getStatus() == PaymentLinkStatus.INITIATED) {
            paymentLinkRepository.save(link);
            return new PublicPaymentInitResponse(
                    link.getPaymentUrl(),
                    link.getTbankPaymentId(),
                    link.getStatus().name(),
                    PaymentMethod.SBP_QR.name(),
                    link.getSbpQrPayload(),
                    link.getSbpQrImage()
            );
        }

        TbankGetQrResponse qrResponse;
        try {
            qrResponse = tbankClient.getQr(runtimeProfile, new TbankGetQrCommand(
                    link.getTbankPaymentId(),
                    "IMAGE",
                    null
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

        String qrImage = normalize(qrResponse.data());
        if (qrImage.isBlank()) {
            link.setLastError("T-Bank GetQr returned empty QR data");
            paymentLinkRepository.save(link);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Т-Банк не вернул QR-код СБП");
        }

        link.setStatus(PaymentLinkStatus.INITIATED);
        link.setPaymentMethod(PaymentMethod.SBP_QR);
        link.setTbankTerminalKey(runtimeProfile.terminalKey());
        link.setSbpQrImage(qrImage);
        link.setSbpQrPayload(null);
        link.setSbpQrDataType("IMAGE");
        link.setSbpQrCreatedAt(LocalDateTime.now());
        link.setLastError(null);
        paymentLinkRepository.save(link);

        return new PublicPaymentInitResponse(
                link.getPaymentUrl(),
                link.getTbankPaymentId(),
                link.getStatus().name(),
                PaymentMethod.SBP_QR.name(),
                null,
                qrImage
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
        if (link.getStatus() == PaymentLinkStatus.CONFIRMED || link.getStatus() == PaymentLinkStatus.TEST_CONFIRMED) {
            rememberCompanyPayerEmail(link);
            return;
        }
        if (!runtimeSettingsService.isApplyConfirmedPayments()
                || paymentProfileService.isTestTerminal(link.getTbankTerminalKey())) {
            link.setStatus(PaymentLinkStatus.TEST_CONFIRMED);
            link.setPaidAt(LocalDateTime.now());
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
            link.setLastError(null);
            rememberCompanyPayerEmail(link);
        } catch (Exception e) {
            link.setStatus(PaymentLinkStatus.FAILED);
            link.setLastError("Order payment transition failed");
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Не удалось перевести заказ в оплату", e);
        }
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
                link.getExpiresAt(),
                isPayable(link),
                paymentPageModeName(),
                runtimeSettingsService.isTpayEnabled(),
                runtimeSettingsService.isSberpayEnabled(),
                runtimeSettingsService.isMirpayEnabled()
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
                link.getExpiresAt(),
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
                link.getStatus().name(),
                link.getPaymentMethod() == null ? PaymentMethod.BANK_FORM.name() : link.getPaymentMethod().name(),
                paymentProfileCode(link),
                paymentProfileName(link),
                normalize(link.getTbankTerminalKey()),
                link.getTbankPaymentId(),
                link.getTbankOrderId(),
                link.getPayerEmail(),
                link.getPaymentUrl(),
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
                && link.getStatus() != PaymentLinkStatus.EXPIRED;
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
        Manager manager = order == null ? null : order.getManager();
        if (manager == null && order != null) {
            Company company = order.getCompany();
            manager = company == null ? null : company.getManager();
        }
        return paymentProfileService.selectForManager(manager);
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
        return String.join("\n\n",
                heading(link.getOrder()),
                "Здравствуйте, ваш заказ выполнен. К оплате: "
                        + amountRubles(link.getAmountKopecks()).stripTrailingZeros().toPlainString()
                        + " руб.",
                "Ссылка на оплату: " + url
        );
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
