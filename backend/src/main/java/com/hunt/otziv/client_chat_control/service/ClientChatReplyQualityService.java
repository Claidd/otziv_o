package com.hunt.otziv.client_chat_control.service;

import com.hunt.otziv.client_chat_control.model.ClientChatReplyQuality;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class ClientChatReplyQualityService {

    private static final Set<String> GENERIC_REPLIES = Set.of(
            "да", "нет", "хорошо", "поняла", "понял", "проверим", "спасибо", "ок", "окей"
    );

    public Result assess(String clientMessage, String staffReply) {
        String client = normalize(clientMessage);
        String reply = normalize(staffReply);
        if (reply.isBlank()) {
            return new Result(ClientChatReplyQuality.SUSPICIOUS, "Ответ сотрудника пуст");
        }
        ClientChatResolutionPolicy.Assessment request = new ClientChatResolutionPolicy().assess(clientMessage);
        if (!request.responseRequired()) {
            return new Result(ClientChatReplyQuality.NOT_APPLICABLE, "Сообщение не требует содержательного ответа");
        }
        if (GENERIC_REPLIES.contains(reply)) {
            return new Result(
                    ClientChatReplyQuality.PARTIAL,
                    "На вопрос или проблему дан слишком общий ответ"
            );
        }
        if ("PROBLEM_OR_COMPLAINT".equals(request.reasonCode())
                && !containsAny(reply, "провер", "исправ", "уточн", "реш", "разбер", "восстанов", "вернем")) {
            return new Result(
                    ClientChatReplyQuality.PARTIAL,
                    "В ответе на проблему не указан следующий шаг"
            );
        }
        return new Result(ClientChatReplyQuality.GOOD, "Ответ содержит содержательную реакцию");
    }

    private boolean containsAny(String value, String... fragments) {
        for (String fragment : fragments) {
            if (value.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replace('ё', 'е')
                .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    public record Result(ClientChatReplyQuality quality, String reason) {
    }
}

