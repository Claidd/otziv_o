package com.hunt.otziv.manager_daily_summary.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Anonymized 2026-10-23 audit question/answer; facts retain the observed UNKNOWN semantics. */
final class ManagerReportReviewIncidentFixture {
    private ManagerReportReviewIncidentFixture() { }

    static final String ANSWER = "Открою карточки этих фирм, сверю в чате по каждому заказу историю переписку, дату и статус заказов, "
            + "зафиксирую итог в карточке и оставлю заказ на соответствующем этапе до подтверждения. Повторную отправку без проверки не создаю";

    static ManagerReportReviewQualityService.ReviewQuestion question() {
        return new ManagerReportReviewQualityService.ReviewQuestion(
                "В «Просроченных заказах» Компания Альфа (6 шт., 1200 руб.), Компания Бета (10 шт., 2000 руб.) "
                        + "и Компания Гамма (19 шт., 3800 руб.) стоят «На проверке»; в карточках указано, что клиент не проверял шаблоны "
                        + "16–25 дней, а старая отправка осталась без подтверждения. Что вы сверите в чате по каждому заказу и как "
                        + "зафиксируете итог, не создавая повторную отправку?",
                List.of("Компания Альфа, Компания Бета, Компания Гамма",
                        "все три заказа в статусе «На проверке», клиент не проверял шаблоны 16–25 дней",
                        "старая отправка осталась без подтверждения",
                        "проверить историю чата и результат существующей отправки/operation ID",
                        "не отправлять повторно до подтверждения; при возможности использовать «Починить», "
                                + "при неясном исходе передать существующую операцию администратору и зафиксировать результат"),
                List.of(101L, 102L, 103L));
    }

    static String evidence() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<String> lines = new ArrayList<>();
        lines.add("СЕРВЕРНЫЕ ФАКТЫ ДЛЯ ПРОВЕРКИ:");
        String observedAt = OffsetDateTime.now().toString();
        for (long task = 101; task <= 103; task++) {
            String company = List.of("Компания Альфа", "Компания Бета", "Компания Гамма").get((int) task - 101);
            for (var field : Map.of("company", company, "status", "На проверке").entrySet()) {
                lines.add(mapper.writeValueAsString(Map.of("id", "order:" + task + ":" + field.getKey(),
                        "source", "order", "field", field.getKey(), "value", field.getValue(),
                        "sourceTaskId", task, "observedAt", observedAt)));
            }
            lines.add(mapper.writeValueAsString(Map.of("id", "automation:" + task + ":delivery_status",
                    "source", "automation", "field", "delivery_status", "value", "UNKNOWN",
                    "sourceTaskId", task, "observedAt", observedAt)));
            lines.add(mapper.writeValueAsString(Map.of("id", "automation:" + task + ":current_cycle",
                    "source", "automation", "field", "current_cycle", "value", "true",
                    "sourceTaskId", task, "observedAt", observedAt)));
        }
        return String.join("\n", lines);
    }
}
