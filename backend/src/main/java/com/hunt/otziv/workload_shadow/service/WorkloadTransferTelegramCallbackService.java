package com.hunt.otziv.workload_shadow.service;

import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferOfferRepository;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferOfferRepository.CallbackProjection;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;

@Service
@RequiredArgsConstructor
public class WorkloadTransferTelegramCallbackService {

    private static final String ACCEPT_PREFIX = "wlt:a:";
    private static final String DECLINE_PREFIX = "wlt:d:";

    private final WorkloadTransferOfferRepository repository;
    private final WorkloadLiveSettingsService liveSettingsService;
    private final WorkloadShadowSettingsService shadowSettingsService;
    private final TelegramService telegramService;
    private final WorkloadForcedSingleRecipientService forcedSingleRecipientService;
    private final com.hunt.otziv.workload_shadow.repository.WorkloadLiveControlRepository liveControlRepository;

    @Transactional
    public Optional<String> handle(CallbackQuery callbackQuery) {
        ParsedCallback parsed = parse(callbackQuery == null ? null : callbackQuery.getData());
        if (parsed == null) {
            return Optional.empty();
        }
        if (callbackQuery.getFrom() == null
                || callbackQuery.getFrom().getId() == null
                || callbackQuery.getMessage() == null
                || callbackQuery.getMessage().getMessageId() == null) {
            return Optional.of("Не удалось определить пользователя или сообщение");
        }
        var liveSettings = liveSettingsService.current();
        if (!liveSettingsService.applicationAllowed(liveSettings)) {
            return Optional.of("Боевой контур остановлен. Назначения не изменены");
        }
        if (!liveControlMatches(liveSettings)) {
            return Optional.of("Боевой контур изменился. Назначения не изменены");
        }

        CallbackProjection offer = repository.findCallbackOffer(parsed.offerToken())
                .orElse(null);
        if (offer == null) {
            return Optional.of("Предложение не найдено");
        }
        if (!liveSettingsService.managerAllowed(
                liveSettings,
                offer.getManagerId()
        )) {
            return Optional.of(
                    "Менеджер больше не входит в боевой контур. "
                            + "Ответ не принят, назначения не изменены"
            );
        }
        LocalDateTime now = now();
        if (!"OFFERED".equals(offer.getOfferStatus())
                || !"OFFERED".equals(offer.getWorkflowStatus())) {
            return Optional.of(statusMessage(offer.getOfferStatus()));
        }
        if (offer.getExpiresAt() == null || !offer.getExpiresAt().isAfter(now)) {
            return Optional.of("Срок ответа истёк. Система уже выбирает следующего кандидата");
        }

        long chatId = callbackQuery.getMessage().getChatId();
        int messageId = callbackQuery.getMessage().getMessageId();
        long actorTelegramId = callbackQuery.getFrom().getId();
        int updated;
        boolean forcedFallback = false;
        if (parsed.accept()) {
            updated = repository.accept(
                    parsed.offerToken(),
                    chatId,
                    messageId,
                    actorTelegramId,
                    offer.getManagerId(),
                    liveSettings.revision(),
                    now
            );
        } else {
            updated = repository.decline(
                    parsed.offerToken(),
                    chatId,
                    messageId,
                    actorTelegramId,
                    offer.getManagerId(),
                    liveSettings.revision(),
                    now
            );
            if (updated > 0 && offer.getWorkflowId() != null) {
                forcedFallback = forcedSingleRecipientService
                        .acceptExhaustedWorkflow(offer.getWorkflowId(), now) > 0;
            }
        }
        /*
         * accept/decline atomically update offer, workflow and candidate in one
         * guarded MySQL multi-table UPDATE. JDBC reports the number of changed
         * physical rows (normally three), not one logical transition.
         */
        if (updated <= 0) {
            return Optional.of(
                    "Ответ не принят: предложение изменилось или предназначено другому сотруднику"
            );
        }
        boolean callbackForcedFallback = forcedFallback;
        afterCommit(() -> telegramService.editMessageText(
                chatId,
                messageId,
                resultText(offer, parsed.accept(), callbackForcedFallback),
                "HTML",
                null
        ));
        return Optional.of(
                parsed.accept()
                        ? "Согласие принято. Передача будет выполнена только после повторной проверки"
                        : forcedFallback
                                ? "Отказ принят. Других получателей нет: передача будет выполнена принудительно, владелец уведомлён"
                                : "Отказ принят. Предложение перейдёт следующему кандидату"
        );
    }

    private boolean liveControlMatches(
            com.hunt.otziv.workload_shadow.dto.WorkloadLiveSettingsResponse settings
    ) {
        var control = liveControlRepository.lockState().orElse(null);
        if (control == null || control.getSettingsRevision() == null) {
            return false;
        }
        String mode = control.getMode() == null ? "" : control.getMode();
        boolean activeMode = WorkloadLiveSettingsService.MODE_CANARY.equals(mode)
                || WorkloadLiveSettingsService.MODE_LIVE.equals(mode);
        return activeMode
                && control.getSettingsRevision() == settings.revision()
                && "true".equalsIgnoreCase(control.getApplyEnabled());
    }


    private ParsedCallback parse(String data) {
        if (data == null) {
            return null;
        }
        boolean accept;
        String token;
        if (data.startsWith(ACCEPT_PREFIX)) {
            accept = true;
            token = data.substring(ACCEPT_PREFIX.length());
        } else if (data.startsWith(DECLINE_PREFIX)) {
            accept = false;
            token = data.substring(DECLINE_PREFIX.length());
        } else {
            return null;
        }
        try {
            UUID.fromString(token);
            return new ParsedCallback(accept, token);
        } catch (IllegalArgumentException exception) {
            return new ParsedCallback(accept, "");
        }
    }

    private String statusMessage(String status) {
        return switch (status == null ? "" : status) {
            case "ACCEPTED" -> "Предложение уже принято";
            case "DECLINED" -> "Отказ уже сохранён";
            case "EXPIRED" -> "Срок предложения истёк";
            case "CANCELLED" -> "Предложение отменено";
            default -> "Предложение уже обработано";
        };
    }

    private String resultText(
            CallbackProjection offer,
            boolean accepted,
            boolean forcedFallback
    ) {
        String company = html(offer.getCompanyTitle());
        if (accepted) {
            return "<b>✅ Предложение принято</b>\n\nКомпания: <b>"
                    + company
                    + "</b>\nНазначения пока не изменены. Выполняется повторная проверка графа.";
        }
        if (forcedFallback) {
            return "<b>❌ Предложение отклонено</b>\n\nКомпания: <b>"
                    + company
                    + "</b>\nДругих получателей нет. Передача будет выполнена принудительно; владелец уведомлён, что нужен новый сотрудник.";
        }
        return "<b>❌ Предложение отклонено</b>\n\nКомпания: <b>"
                + company
                + "</b>\nСистема предложит её следующему подходящему сотруднику.";
    }

    private LocalDateTime now() {
        var shadow = shadowSettingsService.current();
        return LocalDateTime.now(shadowSettingsService.zone(shadow));
    }

    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        action.run();
                    }
                }
        );
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

    private record ParsedCallback(boolean accept, String offerToken) {
    }
}
