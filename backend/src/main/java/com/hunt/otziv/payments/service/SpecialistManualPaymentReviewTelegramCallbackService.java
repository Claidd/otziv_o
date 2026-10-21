package com.hunt.otziv.payments.service;

import java.util.Optional;
import java.util.Objects;
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

    @Override
    public Optional<String> handle(String data, Long fromUserId, Long chatId) {
        if (data == null || !data.startsWith(PREFIX)) {
            return Optional.empty();
        }
        if (fromUserId == null || !Objects.equals(chatId, fromUserId)) {
            return Optional.of("Откройте личный чат с ботом под своей учётной записью");
        }
        try {
            Long reviewId = Long.valueOf(data.substring(PREFIX.length()));
            return Optional.of(reviews.dispute(reviewId, fromUserId));
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
}
