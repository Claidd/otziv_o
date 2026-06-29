package com.hunt.otziv.client_chat_control.service;

import com.hunt.otziv.config.settings.AppSettingService;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ClientChatAutoIgnoreService {

    public static final String DEFAULT_PHRASES = "ок,окей,хорошо,спасибо,спасибо большое,благодарю,да,нет,"
            + "понял,поняла,поняли,принято,договорились,отлично,супер,ясно,ладно,хорошо спасибо,спс";
    private static final Set<String> QUESTION_WORDS = Set.of(
            "когда",
            "сколько",
            "почему",
            "зачем",
            "как",
            "где",
            "куда",
            "можно",
            "что",
            "какой",
            "какая",
            "какие",
            "будет",
            "будут",
            "получится",
            "откроется"
    );
    private static final Set<String> PROBLEM_WORDS = Set.of(
            "не открывается",
            "не работает",
            "ошибка",
            "проблема",
            "не пришло",
            "не получил",
            "не получила",
            "нет ссылки",
            "не вижу",
            "не могу"
    );

    private final AppSettingService appSettingService;

    public boolean shouldIgnore(String messageText) {
        if (!appSettingService.getBoolean(AppSettingService.MANAGER_CONTROL_UNANSWERED_AUTO_IGNORE_ENABLED, true)) {
            return false;
        }
        String normalized = normalize(messageText);
        if (normalized.isBlank()) {
            return true;
        }
        int maxLength = Math.max(1, appSettingService.getInt(AppSettingService.MANAGER_CONTROL_UNANSWERED_AUTO_IGNORE_MAX_LENGTH, 60));
        if (normalized.length() > maxLength) {
            return false;
        }
        if (looksLikeQuestionOrProblem(normalized)) {
            return false;
        }
        Set<String> phrases = ignorePhrases();
        if (phrases.contains(normalized)) {
            return true;
        }
        return normalized.length() <= 12 && phrases.stream().anyMatch(phrase ->
                phrase.length() >= 2 && (normalized.equals(phrase) || normalized.startsWith(phrase + " "))
        );
    }

    private boolean looksLikeQuestionOrProblem(String normalized) {
        if (normalized.contains("?")) {
            return true;
        }
        String padded = " " + normalized + " ";
        boolean hasQuestionWord = QUESTION_WORDS.stream().anyMatch(word -> padded.contains(" " + word + " "));
        boolean hasProblemWord = PROBLEM_WORDS.stream().anyMatch(normalized::contains);
        return hasQuestionWord || hasProblemWord;
    }

    private Set<String> ignorePhrases() {
        String raw = appSettingService.getStringAllowEmpty(
                AppSettingService.MANAGER_CONTROL_UNANSWERED_AUTO_IGNORE_PHRASES,
                DEFAULT_PHRASES
        );
        return Arrays.stream(raw.split(","))
                .map(ClientChatAutoIgnoreService::normalize)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toSet());
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replace('ё', 'е')
                .replaceAll("[!.,:;()\\[\\]\"'`]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
