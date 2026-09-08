package com.hunt.otziv.payments.service;

import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.model.PaymentMethod;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import java.time.LocalDateTime;
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
import static com.hunt.otziv.payments.service.PaymentLinkCancellationWorkflow.REFUNDED_STATUSES;

/**
 * Resolves public tokens and eligible replacement links inside the caller's
 * order transaction. A failed replacement keeps the original public response
 * available without marking the surrounding transaction rollback-only.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PublicPaymentLinkResolutionService {

    private final PaymentLinkLifecycleService lifecycleService;

    private final PaymentLinkPreparationWorkflow preparationWorkflow;

    private final PaymentLinkPresenter paymentPresenter;

    static final String STATUS_PAYMENT = "Оплачено";

    static final String MANUAL_PAID_RETIRED_REASON = "Заказ отмечен оплаченным вручную; старая ссылка закрыта";

    private final PaymentLinkRepository paymentLinkRepository;

    private final TbankRuntimeSettingsService runtimeSettingsService;

    private boolean hasBankInitReservation(PaymentLink link) {
        return lifecycleService.hasBankInitReservation(link);
    }

    PaymentLink findPublicLink(String token) {
        String cleanToken = normalize(token);
        if (cleanToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежная ссылка не найдена");
        }
        return paymentLinkRepository.findByTokenWithOrder(cleanToken).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежная ссылка не найдена"));
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = Exception.class)
    PaymentLink findPublicLinkForUpdate(String token) {
        String cleanToken = normalize(token);
        if (cleanToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежная ссылка не найдена");
        }
        return paymentLinkRepository.findByTokenForUpdate(cleanToken).or(() -> paymentLinkRepository.findByTokenWithOrder(cleanToken)).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежная ссылка не найдена"));
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = Exception.class)
    PaymentLink findPublicLinkForUpdateStrict(String token) {
        String cleanToken = normalize(token);
        if (cleanToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежная ссылка не найдена");
        }
        return paymentLinkRepository.findByTokenForUpdate(cleanToken).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежная ссылка не найдена"));
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = Exception.class)
    PaymentLink lockResolvedPublicLink(PaymentLink link) {
        if (link == null || link.getId() == null) {
            return link;
        }
        return paymentLinkRepository.findByIdForUpdate(link.getId()).orElse(link);
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = Exception.class)
    PaymentLink resolveReplacementPublicLink(PaymentLink link, LocalDateTime now, boolean createIfMissing) {
        if (!shouldResolveReplacementPublicLink(link, now)) {
            return link;
        }
        Long orderId = link.getOrder() == null ? null : link.getOrder().getId();
        Optional<PaymentLink> replacement = paymentLinkRepository.findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(orderId, REUSABLE_STATUSES, now).filter(candidate -> !sameLinkId(candidate, link));
        if (replacement.isPresent() || !createIfMissing) {
            return replacement.orElse(link);
        }
        return createReplacementPublicLink(orderId, now).orElse(link);
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = Exception.class)
    Optional<PaymentLink> createReplacementPublicLink(Long orderId, LocalDateTime now) {
        if (orderId == null || orderId <= 0 || !runtimeSettingsService.isPaymentLinksEnabled()) {
            return Optional.empty();
        }
        try {
            preparationWorkflow.createReplacementInCurrentTransaction(orderId);
        } catch (ResponseStatusException e) {
            log.warn("Public payment link replacement skipped: orderId={}, status={}, reason={}", orderId, e.getStatusCode(), normalize(e.getReason()));
            return Optional.empty();
        } catch (RuntimeException e) {
            log.warn("Public payment link replacement failed: orderId={}", orderId, e);
            return Optional.empty();
        }
        return paymentLinkRepository.findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(orderId, REUSABLE_STATUSES, now).filter(candidate -> candidate.getExpiresAt() != null && candidate.getExpiresAt().isAfter(now));
    }

    boolean shouldResolveReplacementPublicLink(PaymentLink link, LocalDateTime now) {
        if (link == null || link.getOrder() == null || link.getOrder().getId() == null) {
            return false;
        }
        if (hasBankInitReservation(link)) {
            return false;
        }
        if (isManualPaidRetiredLink(link)) {
            return true;
        }
        if (link.getStatus() == PaymentLinkStatus.CONFIRMED || link.getStatus() == PaymentLinkStatus.TEST_CONFIRMED || link.getStatus() == PaymentLinkStatus.AMOUNT_MISMATCH || link.getStatus() == PaymentLinkStatus.AUTHORIZED || link.getStatus() == PaymentLinkStatus.NEEDS_RECONCILIATION || (REFUNDED_STATUSES.contains(link.getStatus()) && !isProviderCanceledBankAttempt(link))) {
            return false;
        }
        return link.getStatus() == PaymentLinkStatus.EXPIRED || link.getStatus() == PaymentLinkStatus.FAILED || link.getStatus() == PaymentLinkStatus.REJECTED || isProviderCanceledBankAttempt(link) || (link.getExpiresAt() != null && !link.getExpiresAt().isAfter(now));
    }

    /**
     * A public link may outlive the provider-side payment/QR session created
     * from it. Once T-Bank has explicitly confirmed CANCELED, that immutable
     * bank attempt can no longer accept money and it is safe to resolve the
     * public URL to a fresh payment link for the still-unpaid order.
     *
     * Internal cancellations deliberately do not qualify: they may represent
     * an unsent message, a manual-payment transition or another operational
     * decision that must not be silently reopened by a public page visit.
     */
    private boolean isProviderCanceledBankAttempt(PaymentLink link) {
        if (link == null || link.getStatus() != PaymentLinkStatus.CANCELED || link.getPaidAt() != null || (link.getPaymentMethod() != PaymentMethod.BANK_FORM && link.getPaymentMethod() != PaymentMethod.SBP_QR) || normalize(link.getTbankPaymentId()).isBlank()) {
            return false;
        }
        return "CANCELED".equals(normalize(link.getProviderTerminalStatus()).toUpperCase(Locale.ROOT));
    }

    private boolean isManualPaidRetiredLink(PaymentLink link) {
        if (link.getStatus() != PaymentLinkStatus.CANCELED || !MANUAL_PAID_RETIRED_REASON.equals(normalize(link.getLastError())) || !normalize(link.getTbankPaymentId()).isBlank()) {
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

    private String normalize(String value) {
        return paymentPresenter.normalize(value);
    }
}
