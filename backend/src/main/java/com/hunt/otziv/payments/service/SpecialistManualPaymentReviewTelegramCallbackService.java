package com.hunt.otziv.payments.service;

import java.util.Optional;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;

@Service
@RequiredArgsConstructor
@Slf4j
public class SpecialistManualPaymentReviewTelegramCallbackService {

    private static final String PREFIX = "specialist-payment-dispute:";

    private final SpecialistManualPaymentReviewService reviews;

    public Optional<String> handle(CallbackQuery callback) {
        String data = callback == null ? null : callback.getData();
        if (data == null || !data.startsWith(PREFIX)) {
            return Optional.empty();
        }
        if (callback.getFrom() == null || callback.getFrom().getId() == null
                || callback.getMessage() == null
                || !Objects.equals(callback.getMessage().getChatId(), callback.getFrom().getId())) {
            return Optional.of("Откройте личный чат с ботом под своей учётной записью");
        }
        try {
            Long reviewId = Long.valueOf(data.substring(PREFIX.length()));
            return Optional.of(reviews.dispute(reviewId, callback.getFrom().getId()));
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
