package com.hunt.otziv.performers.service;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;

@Service
@RequiredArgsConstructor
public class PerformerTelegramCallbackService {

    private static final String ACCEPT_PREFIX = "perf:accept:";
    private static final String DECLINE_PREFIX = "perf:decline:";

    private final PerformerAssignmentService assignmentService;

    public Optional<String> handle(CallbackQuery callbackQuery) {
        if (callbackQuery == null || callbackQuery.getData() == null) {
            return Optional.empty();
        }
        String data = callbackQuery.getData().trim();
        try {
            if (data.startsWith(ACCEPT_PREFIX)) {
                assignmentService.acceptOfferFromTelegram(parseId(data, ACCEPT_PREFIX));
                return Optional.of("Задание принято");
            }
            if (data.startsWith(DECLINE_PREFIX)) {
                assignmentService.declineOfferFromTelegram(parseId(data, DECLINE_PREFIX));
                return Optional.of("Отказ зафиксирован");
            }
        } catch (RuntimeException e) {
            return Optional.of(e.getMessage() == null ? "Не удалось обработать команду" : e.getMessage());
        }
        return Optional.empty();
    }

    private Long parseId(String data, String prefix) {
        return Long.parseLong(data.substring(prefix.length()));
    }
}
