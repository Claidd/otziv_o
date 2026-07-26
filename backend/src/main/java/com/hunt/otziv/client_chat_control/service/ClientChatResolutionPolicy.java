package com.hunt.otziv.client_chat_control.service;

import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class ClientChatResolutionPolicy {

    private static final Set<String> QUESTION_WORDS = Set.of(
            "когда", "сколько", "почему", "зачем", "как", "где", "куда",
            "можно", "что", "какой", "какая", "какие", "верно", "получится", "откроется"
    );
    private static final Set<String> ACTION_WORDS = Set.of(
            "запуст", "добав", "исправ", "поменя", "измен", "удал", "останов", "публику",
            "перенес", "проверь", "пришл", "отправ", "сдела", "нужно", "надо"
    );
    private static final Set<String> PROBLEM_WORDS = Set.of(
            "не работает", "не открывается", "не получается", "не пришло", "не получил",
            "ошибка", "проблем", "плох", "неправд", "не прош", "заблок", "издевает",
            "деньги требу", "не читаете"
    );
    private static final Set<String> SAFE_PHRASES = Set.of(
            "ок", "окей", "хорошо", "спасибо", "спасибо большое", "вам спасибо",
            "и вам спасибо", "взаимно", "благодарю",
            "понял", "поняла", "поняли", "принято", "договорились", "отлично",
            "супер", "ясно", "ладно", "хорошо спасибо", "не надо", "пока не нужно"
    );
    private static final Set<String> SAFE_ACKNOWLEDGEMENT_WORDS = Set.of(
            "ок", "окей", "хорошо", "спасибо", "большое", "вам", "и",
            "взаимно", "благодарю", "понял", "поняла", "поняли", "принято",
            "договорились", "отлично", "супер", "ясно", "ладно"
    );

    public Assessment assess(String messageText) {
        String normalized = normalize(messageText);
        if (normalized.isBlank()) {
            return new Assessment(false, false, true, "EMPTY");
        }
        if (normalized.startsWith("вложение ") || normalized.endsWith(" pdf")) {
            return new Assessment(true, true, false, "ATTACHMENT_REQUIRES_REVIEW");
        }
        if (normalized.contains("?") || containsWord(normalized, QUESTION_WORDS)) {
            return new Assessment(true, false, false, "QUESTION");
        }
        if (containsFragment(normalized, PROBLEM_WORDS)) {
            return new Assessment(true, true, false, "PROBLEM_OR_COMPLAINT");
        }
        if (containsFragment(normalized, ACTION_WORDS)) {
            return new Assessment(true, true, false, "ACTION_REQUEST");
        }
        if (isSafeAcknowledgement(normalized)) {
            return new Assessment(false, false, true, "ACKNOWLEDGEMENT");
        }
        return new Assessment(true, false, false, "NEEDS_HUMAN_REVIEW");
    }

    private boolean isSafeAcknowledgement(String normalized) {
        if (SAFE_PHRASES.contains(normalized)) {
            return true;
        }
        String withoutEmoji = normalized.replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]+", "").trim();
        if (withoutEmoji.isBlank()) {
            return true;
        }
        String[] words = normalized.split(" ");
        return words.length <= 5
                && java.util.Arrays.stream(words).allMatch(SAFE_ACKNOWLEDGEMENT_WORDS::contains);
    }

    private boolean containsWord(String normalized, Set<String> words) {
        String padded = " " + normalized + " ";
        return words.stream().anyMatch(word -> padded.contains(" " + word + " "));
    }

    private boolean containsFragment(String normalized, Set<String> fragments) {
        return fragments.stream().anyMatch(normalized::contains);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replace('ё', 'е')
                .replaceAll("[!.,:;()\\[\\]\"'`]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    public record Assessment(
            boolean responseRequired,
            boolean actionRequired,
            boolean safeNoResponse,
            String reasonCode
    ) {
    }
}
