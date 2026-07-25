package com.hunt.otziv.client_chat_control.service;

import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ClientChatReplySuggestionService {

    private final ClientChatResolutionPolicy resolutionPolicy;

    public Suggestion suggest(String messageText) {
        String message = normalize(messageText);
        ClientChatResolutionPolicy.Assessment assessment = resolutionPolicy.assess(messageText);
        if (message.contains("не откры") || message.contains("не работа") || message.contains("не получ")) {
            return new Suggestion(
                    "Здравствуйте! Спасибо, что сообщили. Сейчас проверим ссылку и вернёмся к вам с рабочим вариантом.",
                    "LINK_OR_DELIVERY_PROBLEM"
            );
        }
        if (message.contains("оплатил") || message.contains("оплатила") || message.contains("оплатили")) {
            return new Suggestion(
                    "Здравствуйте! Спасибо, проверим поступление оплаты и сообщим результат.",
                    "PAYMENT_CHECK"
            );
        }
        if (message.contains("оплачу") || message.contains("оплатим")) {
            return new Suggestion(
                    "Хорошо, спасибо. Зафиксировали и вернёмся к вопросу оплаты в согласованный день.",
                    "PAYMENT_FOLLOW_UP"
            );
        }
        if ("PROBLEM_OR_COMPLAINT".equals(assessment.reasonCode())) {
            return new Suggestion(
                    "Здравствуйте! Понимаем ваше недовольство. Проверим ситуацию по заказу и вернёмся к вам с конкретным решением.",
                    "COMPLAINT"
            );
        }
        if ("ACTION_REQUEST".equals(assessment.reasonCode())) {
            return new Suggestion(
                    "Здравствуйте! Приняли ваш запрос в работу. Зафиксируем изменения и сообщим, когда всё будет готово.",
                    "ACTION_REQUEST"
            );
        }
        if ("QUESTION".equals(assessment.reasonCode())) {
            return new Suggestion(
                    "Здравствуйте! Уточним информацию по вашему заказу и ответим вам по существу.",
                    "QUESTION_REQUIRES_FACTS"
            );
        }
        return new Suggestion("Здравствуйте! Спасибо за сообщение.", "GENERAL");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replace('ё', 'е');
    }

    public record Suggestion(String message, String reasonCode) {
    }
}

