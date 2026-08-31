package com.hunt.otziv.workload_shadow.service;

import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferOfferRepository.DeliveryProjection;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkloadTransferOfferDeliveryService {

    private final WorkloadTransferOfferService offerService;
    private final WorkloadTransferOfferScopeService offerScopeService;
    private final WorkloadLiveSettingsService liveSettingsService;
    private final TelegramService telegramService;

    public int deliverDueOffers() {
        WorkloadTransferOfferService.ClaimedOffers claimed =
                offerService.claimDueOffers();
        if (claimed.offers().isEmpty()) {
            return 0;
        }
        /*
         * claimDueOffers filters by the settings that were current when the
         * lease was acquired. Re-read them once immediately before the
         * external Telegram side effect: CANARY membership may have changed
         * in between. The partition is in memory and cancellation is one
         * set-based update, not one query per offer.
         */
        var liveSettings = liveSettingsService.current();
        List<DeliveryProjection> deliverable = new ArrayList<>();
        List<Long> outsideScopeIds = new ArrayList<>();
        for (DeliveryProjection offer : claimed.offers()) {
            if (liveSettingsService.managerAllowed(
                    liveSettings,
                    offer.getManagerId()
            )) {
                deliverable.add(offer);
            } else {
                outsideScopeIds.add(offer.getOfferId());
            }
        }
        if (!outsideScopeIds.isEmpty()) {
            int changedRows = offerScopeService.cancelClaimedOutsideScope(
                    claimed.processingToken(),
                    outsideScopeIds
            );
            log.info(
                    "Cancelled claimed workload offers outside current scope "
                            + "offerCount={} changedRows={}",
                    outsideScopeIds.size(),
                    changedRows
            );
        }

        int delivered = 0;
        for (DeliveryProjection offer : deliverable) {
            Integer deliveredMessageId = null;
            boolean storedAsOffered = false;
            try {
                Optional<Integer> messageId =
                        telegramService.sendMessageWithInlineKeyboardMessageId(
                                offer.getTargetGroupChatId(),
                                message(
                                        offer,
                                        claimed.responseTimeoutMinutes()
                                ),
                                "HTML",
                                List.of()
                        );
                if (messageId.isEmpty()) {
                    offerService.markDeliveryFailure(
                            offer.getOfferId(),
                            claimed.processingToken(),
                            "TELEGRAM_NO_MESSAGE_ID",
                            "Telegram не вернул ID сообщения"
                    );
                    continue;
                }
                deliveredMessageId = messageId.get();
                offerService.markDelivered(
                        offer.getOfferId(),
                        claimed.processingToken(),
                        deliveredMessageId,
                        claimed.responseTimeoutMinutes()
                );
                storedAsOffered = true;
                boolean keyboardActivated = telegramService.editMessageText(
                        offer.getTargetGroupChatId(),
                        messageId.get(),
                        message(offer, claimed.responseTimeoutMinutes()),
                        "HTML",
                        keyboard(offer.getOfferToken())
                );
                if (!keyboardActivated) {
                    offerService.markKeyboardActivationFailure(
                            offer.getOfferId(),
                            messageId.get()
                    );
                    telegramService.editMessageText(
                            offer.getTargetGroupChatId(),
                            messageId.get(),
                            cancelledMessage(offer),
                            "HTML",
                            List.of()
                    );
                    continue;
                }
                offerService.markKeyboardActivated(
                        offer.getOfferId(),
                        messageId.get()
                );
                delivered++;
            } catch (RuntimeException exception) {
                log.warn(
                        "Workload transfer offer delivery failed offerId={}",
                        offer.getOfferId(),
                        exception
                );
                if (storedAsOffered && deliveredMessageId != null) {
                    closeInactiveKeyboardOffer(offer, deliveredMessageId);
                } else {
                    offerService.markDeliveryFailure(
                            offer.getOfferId(),
                            claimed.processingToken(),
                            exception.getClass().getSimpleName(),
                            exception.getMessage()
                    );
                    if (deliveredMessageId != null) {
                        removeKeyboard(offer, deliveredMessageId);
                    }
                }
            }
        }
        return delivered;
    }

    private void closeInactiveKeyboardOffer(
            DeliveryProjection offer,
            int messageId
    ) {
        try {
            offerService.markKeyboardActivationFailure(
                    offer.getOfferId(),
                    messageId
            );
        } catch (RuntimeException closeException) {
            log.error(
                    "Failed to close inactive workload Telegram offer offerId={}",
                    offer.getOfferId(),
                    closeException
            );
        }
        removeKeyboard(offer, messageId);
    }

    private void removeKeyboard(DeliveryProjection offer, int messageId) {
        try {
            telegramService.editMessageText(
                    offer.getTargetGroupChatId(),
                    messageId,
                    cancelledMessage(offer),
                    "HTML",
                    List.of()
            );
        } catch (RuntimeException cleanupException) {
            log.warn(
                    "Failed to remove workload Telegram keyboard offerId={}",
                    offer.getOfferId(),
                    cleanupException
            );
        }
    }

    private String message(
            DeliveryProjection offer,
            int responseTimeoutMinutes
    ) {
        return """
                <b>Предложение принять компанию</b>

                Специалист: <b>%s</b>
                Компания: <b>%s</b>
                Передаёт: %s

                Нагрузка: %d ед., примерно %d мин.
                Заказов: %d
                Новые: %d · Коррекция: %d · Выгул: %d
                Публикация: %d · Восстановление: %d · Плохие: %d

                На ответ даётся <b>%s с момента доставки</b>. Компания будет передана только после вашего согласия и повторной проверки графа.
                """.formatted(
                html(offer.getCandidateWorkerName()),
                html(offer.getCompanyTitle()),
                html(offer.getSourceWorkerName()),
                number(offer.getProblemUnits()),
                number(offer.getEstimatedMinutes()),
                number(offer.getActiveOrderCount()),
                number(offer.getNewUnitCount()),
                number(offer.getCorrectionCount()),
                number(offer.getNagulCount()),
                number(offer.getPublishCount()),
                number(offer.getRecoveryCount()),
                number(offer.getBadCount()),
                responseWindow(responseTimeoutMinutes)
        ).trim();
    }

    private String responseWindow(int responseTimeoutMinutes) {
        int safeMinutes = Math.max(1, responseTimeoutMinutes);
        if (safeMinutes % 60 != 0) {
            return safeMinutes + " мин.";
        }
        int hours = safeMinutes / 60;
        int lastTwoDigits = hours % 100;
        String unit;
        if (lastTwoDigits >= 11 && lastTwoDigits <= 14) {
            unit = "часов";
        } else {
            unit = switch (hours % 10) {
                case 1 -> "час";
                case 2, 3, 4 -> "часа";
                default -> "часов";
            };
        }
        return hours + " " + unit;
    }


    private String cancelledMessage(DeliveryProjection offer) {
        return "<b>Предложение отменено</b>\n\nКомпания: <b>"
                + html(offer.getCompanyTitle())
                + "</b>\nКнопки не были активированы из-за ошибки доставки. "
                + "Назначения не изменялись.";
    }
    private List<List<InlineKeyboardButton>> keyboard(String token) {
        InlineKeyboardButton accept = new InlineKeyboardButton();
        accept.setText("✅ Принять");
        accept.setCallbackData("wlt:a:" + token);
        InlineKeyboardButton decline = new InlineKeyboardButton();
        decline.setText("❌ Отказаться");
        decline.setCallbackData("wlt:d:" + token);
        return List.of(List.of(accept, decline));
    }

    private long number(Number value) {
        return value == null ? 0 : value.longValue();
    }

    private String html(String value) {
        if (value == null) {
            return "—";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
