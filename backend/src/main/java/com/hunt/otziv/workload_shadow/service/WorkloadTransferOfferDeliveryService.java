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
            try {
                Optional<Integer> messageId =
                        telegramService.sendMessageWithInlineKeyboardMessageId(
                                offer.getTargetGroupChatId(),
                                message(
                                        offer,
                                        claimed.responseTimeoutMinutes()
                                ),
                                "HTML",
                                keyboard(offer.getOfferToken())
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
                offerService.markDelivered(
                        offer.getOfferId(),
                        claimed.processingToken(),
                        messageId.get(),
                        claimed.responseTimeoutMinutes()
                );
                delivered++;
            } catch (RuntimeException exception) {
                log.warn(
                        "Workload transfer offer delivery failed offerId={}",
                        offer.getOfferId(),
                        exception
                );
                offerService.markDeliveryFailure(
                        offer.getOfferId(),
                        claimed.processingToken(),
                        exception.getClass().getSimpleName(),
                        exception.getMessage()
                );
            }
        }
        return delivered;
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

                На ответ даётся <b>%d мин. с момента доставки</b>. Компания будет передана только после вашего согласия и повторной проверки графа.
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
                Math.max(1, responseTimeoutMinutes)
        ).trim();
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
