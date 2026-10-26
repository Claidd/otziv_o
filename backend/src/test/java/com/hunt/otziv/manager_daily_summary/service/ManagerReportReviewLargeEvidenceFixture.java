package com.hunt.otziv.manager_daily_summary.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Synthetic scale regression: one case, 142 complete facts and six answer criteria. */
final class ManagerReportReviewLargeEvidenceFixture {
    private ManagerReportReviewLargeEvidenceFixture() { }

    static final String ANSWER = "Открою счёт и проверю готовность каждой позиции. Сопоставлю актуальный текст с историей "
            + "и отделю ошибку прошлой попытки от текущей причины ожидания. До публикации получу согласование клиента "
            + "по неготовым позициям. Оплату подтвержу только после сверки фактического поступления, а результат "
            + "проверки и согласования зафиксирую в карточке.";
    static final String FALSE_ANSWER = "Я проверила счёт: статус PAID, готово 7 из 7 позиций, вся сумма уже поступила. "
            + "Клиент согласовал все тексты, публикация завершена; зафиксировала это в карточке.";
    static final String VAGUE_ANSWER = "С этой ситуацией разберусь и постараюсь помочь, всё будет хорошо.";

    static ManagerReportReviewQualityService.ReviewQuestion question() {
        return new ManagerReportReviewQualityService.ReviewQuestion(
                "У компании Альфа общий счёт ожидает готовности позиций. В истории есть ошибка старой попытки. "
                        + "Что вы проверите, как отделите старую ошибку от текущей ситуации, какие подтверждения "
                        + "нужны перед публикацией и отметкой оплаты и где зафиксируете результат?",
                List.of("проверить текущее состояние счёта и готовность позиций",
                        "сопоставить актуальный текст и историю проверки",
                        "отделить прошлую ошибку от текущей причины ожидания",
                        "получить согласование клиента перед публикацией",
                        "подтверждать оплату только после фактического поступления",
                        "зафиксировать результат сверки в карточке"), List.of(701L));
    }

    static String evidence() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String now = OffsetDateTime.now().toString();
        List<String> lines = new ArrayList<>();
        lines.add("Исторический контекст: ошибка предыдущей попытки не является текущим подтверждением.\n"
                + "История компании Альфа сохранена для сопоставления прежних и текущих обстоятельств. ".repeat(120));
        lines.add("СЕРВЕРНЫЕ ФАКТЫ ДЛЯ ПРОВЕРКИ:");
        var initial = Map.of("status", "COLLECTING", "orderCount", "7", "readyCount", "4",
                "paid_amount_kopecks", "0", "client_approval", "PENDING", "published", "false");
        for (var entry : new java.util.TreeMap<>(initial).entrySet()) {
            lines.add(mapper.writeValueAsString(Map.of("id", "invoice:9701:" + entry.getKey(),
                    "source", "invoice", "field", entry.getKey(), "value", entry.getValue(),
                    "sourceTaskId", 701, "observedAt", now)));
        }
        for (int index = 0; index < 136; index++) {
            lines.add(mapper.writeValueAsString(Map.of("id", "chat:701:" + index,
                    "source", "recorded_chat_message", "field", "recorded_text", "sourceTaskId", 701,
                    "observedAt", now, "value", "Запись " + index + ": клиент уточнял порядок работы с текстами. "
                            + "Менеджер сообщил, что после проверки позиций результат будет записан в карточке. "
                            + "Это запись переписки, она не подтверждает оплату или публикацию.")));
        }
        return String.join("\n", lines);
    }
}
