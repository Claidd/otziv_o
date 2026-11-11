package com.hunt.otziv.payments.service;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import com.hunt.otziv.payments.api.SpecialistPaymentDisputeCallback;

@Service
@RequiredArgsConstructor
@Slf4j
public class SpecialistManualPaymentReviewTelegramCallbackService implements SpecialistPaymentDisputeCallback {

    private static final String PREFIX = "specialist-payment-dispute:";

    private final SpecialistManualPaymentReviewService reviews;
    private final CommonInvoicePaymentDisputeService commonInvoiceDisputes;

    @Override
    public Optional<String> handle(String data, Long fromUserId, Long chatId) {
        if (data != null && data.startsWith(CommonInvoicePaymentDisputeService.CALLBACK_PREFIX)) {
            return Optional.of(disputeCommonInvoiceReceipt(data, fromUserId, chatId));
        }
        if (data == null || !data.startsWith(PREFIX)) {
            return Optional.empty();
        }
        if (fromUserId == null || chatId == null || chatId == 0) {
            return Optional.of("Не удалось определить участника или чат");
        }
        try {
            Long reviewId = Long.valueOf(data.substring(PREFIX.length()));
            return Optional.of(reviews.dispute(reviewId, fromUserId, chatId));
        } catch (NumberFormatException malformed) {
            return Optional.of("Повреждённый запрос оплаты");
        } catch (ResponseStatusException rejected) {
            return Optional.of(rejected.getReason() == null
                    ? "Не удалось оспорить оплату" : rejected.getReason());
        } catch (RuntimeException failure) {
            log.error("Specialist manual payment dispute callback failed", failure);
            return Optional.of("Не удалось сохранить спор. Повторите позже");
        }
    }

    /** The same button for a common-invoice receipt; its dispute is recorded on the delivered notice. */
    private String disputeCommonInvoiceReceipt(String data, Long fromUserId, Long chatId) {
        if (fromUserId == null || chatId == null || chatId == 0) {
            return "Не удалось определить участника или чат";
        }
        try {
            Long deliveryId = Long.valueOf(data.substring(CommonInvoicePaymentDisputeService.CALLBACK_PREFIX.length()));
            return commonInvoiceDisputes.dispute(deliveryId, fromUserId, chatId);
        } catch (NumberFormatException malformed) {
            return "Повреждённый запрос оплаты";
        } catch (ResponseStatusException rejected) {
            return rejected.getReason() == null ? "Не удалось оспорить оплату" : rejected.getReason();
        } catch (RuntimeException failure) {
            log.error("Common invoice payment dispute callback failed", failure);
            return "Не удалось сохранить спор. Повторите позже";
        }
    }
}
