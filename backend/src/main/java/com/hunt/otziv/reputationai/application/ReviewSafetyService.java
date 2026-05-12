package com.hunt.otziv.reputationai.application;

import com.hunt.otziv.reputationai.domain.ReviewSafetyReport;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class ReviewSafetyService {

    private static final List<String> HYPE_MARKERS = List.of(
            "лучший", "в восторге", "превзошло ожидания", "идеально", "безупречно",
            "всем рекомендую", "на высшем уровне", "невероятно", "супер", "топ"
    );

    public ReviewSafetyReport check(String text, List<String> allowedFacts) {
        List<String> warnings = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();
        int risk = 0;

        if (text == null || text.isBlank()) {
            return new ReviewSafetyReport(
                    false,
                    100,
                    List.of("Текст пустой."),
                    List.of("Попросите клиента описать реальный опыт покупки или обращения.")
            );
        }

        String normalized = text.toLowerCase(Locale.ROOT);
        if (text.length() < 40) {
            risk += 15;
            warnings.add("Текст слишком короткий, в нем мало личного опыта.");
            suggestions.add("Добавьте 1-2 конкретики: что покупали, что понравилось, какой был результат.");
        }

        long hypeCount = HYPE_MARKERS.stream().filter(normalized::contains).count();
        if (hypeCount >= 2) {
            risk += 25;
            warnings.add("Много рекламных или слишком восторженных формулировок.");
            suggestions.add("Сделайте тон спокойнее и уберите общие похвалы без фактов.");
        }

        if (!normalized.contains("я ") && !normalized.contains("мне ") && !normalized.contains("покуп") && !normalized.contains("обращ")) {
            risk += 20;
            warnings.add("Текст слабо похож на личный опыт клиента.");
            suggestions.add("Добавьте формулировку от первого лица: что клиент делал и что получил.");
        }

        if (normalized.contains("гарантированно") || normalized.contains("100%") || normalized.contains("самый")) {
            risk += 20;
            warnings.add("Есть абсолютные обещания или неподтвержденные превосходные степени.");
            suggestions.add("Замените абсолютные утверждения на конкретный наблюдаемый опыт.");
        }

        if (allowedFacts != null && !allowedFacts.isEmpty()) {
            boolean anyFactMentioned = allowedFacts.stream()
                    .filter(fact -> fact != null && !fact.isBlank())
                    .map(fact -> fact.toLowerCase(Locale.ROOT))
                    .anyMatch(normalized::contains);
            if (!anyFactMentioned) {
                risk += 15;
                warnings.add("Текст не опирается на переданные факты опыта.");
                suggestions.add("Используйте только те пункты, которые клиент действительно выбрал.");
            }
        }

        if (warnings.isEmpty()) {
            suggestions.add("Перед публикацией клиенту все равно стоит перечитать и адаптировать текст под свой опыт.");
        }

        return new ReviewSafetyReport(risk < 50, risk, warnings, suggestions);
    }
}
