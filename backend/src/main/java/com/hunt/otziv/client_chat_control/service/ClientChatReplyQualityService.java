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
        if (ClientChatContentClassifier.attachmentOnly(clientMessage)) {
            return acknowledgesReceipt(reply)
                    ? new Result(ClientChatReplyQuality.GOOD, "Ответ подтверждает получение вложения и следующий шаг")
                    : new Result(
                            ClientChatReplyQuality.NOT_APPLICABLE,
                            "Для вложения достаточно подтверждения получения"
                    );
        }
        if (positivePaymentConfirmation(client)) {
            return acknowledgesReceipt(reply)
                    ? new Result(ClientChatReplyQuality.GOOD, "Ответ подтверждает получение информации об оплате")
                    : new Result(
                            ClientChatReplyQuality.NOT_APPLICABLE,
                            "Для подтверждения оплаты достаточно короткого ответа"
                    );
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

    private boolean positivePaymentConfirmation(String normalized) {
        if (containsAny(
                normalized,
                "не прошла",
                "не прошел",
                "не проходит",
                "не получается",
                "ошибка",
                "проблем",
                "не дош"
        )) {
            return false;
        }
        return containsAny(
                normalized,
                "оплата прошла",
                "оплату произвел",
                "оплату произвела",
                "оплату перевел",
                "оплату перевела",
                "оплату отправил",
                "оплату отправила",
                "я оплатил",
                "я оплатила",
                "я перевел",
                "я перевела",
                "деньги перевел",
                "деньги перевела",
                "чек об оплате",
                "квитанция об оплате"
        );
    }

    private boolean acknowledgesReceipt(String normalizedReply) {
        return normalizedReply.length() >= 12
                && containsAny(
                        normalizedReply,
                        "чек принят",
                        "чек получили",
                        "чек получен",
                        "документ принят",
                        "документ получили",
                        "документ получен",
                        "оплату получили",
                        "информацию об оплате получили",
                        "проверим поступление",
                        "проверю поступление",
                        "приняли в работу",
                        "зафиксировали оплату",
                        "отметили оплату"
                );
    }

    public record Result(ClientChatReplyQuality quality, String reason) {
    }
}
