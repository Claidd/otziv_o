package com.hunt.otziv.payments.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Canonical entry point for an operator-requested payment instruction after a
 * bad-review task. Authorization, order locking and the active-common-invoice
 * guard are delegated to the canonical authorized {@link PaymentLinkService}
 * entry point.
 */
@Service
@RequiredArgsConstructor
public class BadReviewPaymentInstructionOrchestrator {

    private final ObjectProvider<PaymentLinkService> paymentLinkServiceProvider;

    public String prepareCopyTextAuthorized(Long orderId, Authentication authentication) {
        return prepareAuthorized(orderId, authentication).copyText();
    }

    public PreparedPaymentInstruction prepareAuthorized(Long orderId, Authentication authentication) {
        if (orderId == null || orderId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Заказ для счета не выбран");
        }
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Требуется авторизация");
        }
        PaymentLinkService paymentLinkService = paymentLinkServiceProvider.getIfAvailable();
        if (paymentLinkService == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Сервис платежных реквизитов недоступен");
        }
        PaymentLinkService.PaymentInstructionPreparation prepared =
                paymentLinkService.prepareForOrderAuthorized(orderId, authentication);
        return new PreparedPaymentInstruction(
                prepared.response().copyText(),
                prepared.response().token(),
                prepared.response().orderId(),
                prepared.createdFresh(),
                prepared.response().telegramCopyTransferNumber()
        );
    }

    public boolean releaseKnownUnsent(
            PreparedPaymentInstruction prepared,
            Authentication authentication
    ) {
        if (prepared == null || !prepared.createdFresh()) {
            return false;
        }
        PaymentLinkService paymentLinkService = paymentLinkServiceProvider.getIfAvailable();
        if (paymentLinkService == null) {
            return false;
        }
        return paymentLinkService.cancelFreshUnsentPreparationAuthorized(
                prepared.paymentToken(),
                prepared.orderId(),
                authentication
        );
    }

    public record PreparedPaymentInstruction(
            String copyText,
            String paymentToken,
            Long orderId,
            boolean createdFresh,
            String telegramCopyTransferNumber
    ) {
        public PreparedPaymentInstruction(
                String copyText,
                String paymentToken,
                Long orderId,
                boolean createdFresh
        ) {
            this(copyText, paymentToken, orderId, createdFresh, null);
        }
    }
}
