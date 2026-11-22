package com.hunt.otziv.client_chat_control.service;

import com.hunt.otziv.config.settings.service.AppSettingService;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ClientChatAutoIgnoreService {

    public static final String DEFAULT_PHRASES = "ок,окей,хорошо,спасибо,спасибо большое,вам спасибо,"
            + "и вам спасибо,взаимно,благодарю,да,нет,"
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
    private static final Set<String> SAFE_ACKNOWLEDGEMENT_WORDS = Set.of(
            "ок", "окей", "хорошо", "спасибо", "большое", "вам", "и",
            "взаимно", "благодарю", "понял", "поняла", "поняли", "принято",
            "договорились", "отлично", "супер", "ясно", "ладно", "спс"
    );
    // «да»/«нет» are answers to something we asked; next to other words («добрый день, нет») they carry
    // content, so they are skipped only when sent alone or right before an acknowledgement.
    private static final Set<String> STANDALONE_ONLY_PHRASES = Set.of("да", "нет");
    private static final int MAX_COMBINATION_WORDS = 10;
    // Gateways (WhatsApp, MAX) store media as «[Вложение: <type>]» or «[Вложение]», with the caption after it.
    // A file or a photo always needs a look from the manager, whatever the caption says.
    private static final Pattern ATTACHMENT_MARKER = Pattern.compile(
            "^\\s*\\[вложение\\s*[:\\]]",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );
    // Emoji question marks (❓ ❔ ⁉ ⁇ ⁈ ‽ and the full-width ？) would otherwise be stripped with the other
    // symbols and hide a question.
    private static final Pattern QUESTION_MARK_VARIANTS = Pattern.compile("[❓❔⁉⁇⁈‽？]");
    private static final Pattern NON_WORD_RUN = Pattern.compile("[^\\p{L}\\p{N}?]+");
    private static final Pattern LEGACY_PUNCTUATION = Pattern.compile("[!.,:;()\\[\\]\"'`]+");

    private final AppSettingService appSettingService;

    public boolean shouldIgnore(String messageText) {
        if (!appSettingService.getBoolean(AppSettingService.MANAGER_CONTROL_UNANSWERED_AUTO_IGNORE_ENABLED, true)) {
            return false;
        }
        if (isAttachment(messageText)) {
            return false;
        }
        // Empty and punctuation-only messages were always skipped; stripping emoji must not widen this set.
        if (legacyNormalize(messageText).isBlank()) {
            return true;
        }
        String normalized = normalize(messageText);
        int maxLength = Math.max(1, appSettingService.getInt(AppSettingService.MANAGER_CONTROL_UNANSWERED_AUTO_IGNORE_MAX_LENGTH, 60));
        if (normalized.length() > maxLength) {
            return false;
        }
        if (normalized.isEmpty()) {
            return matchesListedSymbolsExactly(messageText);
        }
        if (looksLikeQuestionOrProblem(normalized)) {
            return false;
        }
        Set<String> phrases = ignorePhrases();
        if (phrases.contains(normalized)) {
            return true;
        }
        String[] words = normalized.split(" ");
        boolean shortAcknowledgement = words.length <= 5
                && (Arrays.stream(words).allMatch(SAFE_ACKNOWLEDGEMENT_WORDS::contains)
                || normalized.length() <= 12 && phrases.stream().anyMatch(phrase ->
                        phrase.length() >= 2 && normalized.startsWith(phrase + " ")
                                // «нет счета», «да не пришел» start with a phrase but carry content: only an
                                // acknowledgement may follow it («да спасибо», «нет спасибо»).
                                && onlyAcknowledges(normalized.substring(phrase.length()).trim(), phrases)
                ));
        return shortAcknowledgement || coveredByListPhrases(words, phrases);
    }

    private static boolean onlyAcknowledges(String rest, Set<String> phrases) {
        return !rest.isBlank() && Arrays.stream(rest.split(" "))
                .allMatch(word -> SAFE_ACKNOWLEDGEMENT_WORDS.contains(word) || phrases.contains(word));
    }

    /**
     * Clients chain courtesy phrases («Добрый день, спасибо», «Спасибо, хорошего дня»). The text is skipped
     * when it splits completely into whole list phrases and acknowledgement words, with at least one list
     * phrase, so every word of it comes from the list the owner maintains. All splits are tried, so a long
     * phrase that would swallow the start of the next one does not block a valid split.
     */
    private static boolean coveredByListPhrases(String[] words, Set<String> phrases) {
        if (words.length > MAX_COMBINATION_WORDS) {
            return false;
        }
        List<String[]> parts = phrases.stream()
                .filter(phrase -> !STANDALONE_ONLY_PHRASES.contains(phrase))
                .map(phrase -> phrase.split(" "))
                .toList();
        // covered[i][0]: the first i words are acknowledgement words only;
        // covered[i][1]: the first i words split into acknowledgement words and at least one list phrase.
        boolean[][] covered = new boolean[words.length + 1][2];
        covered[0][0] = true;
        for (int index = 0; index < words.length; index++) {
            for (int withPhrase = 0; withPhrase < 2; withPhrase++) {
                if (!covered[index][withPhrase]) {
                    continue;
                }
                if (SAFE_ACKNOWLEDGEMENT_WORDS.contains(words[index])) {
                    covered[index + 1][withPhrase] = true;
                }
                for (String[] part : parts) {
                    if (matchesAt(words, index, part)) {
                        covered[index + part.length][1] = true;
                    }
                }
            }
        }
        return covered[words.length][1];
    }

    private static boolean matchesAt(String[] words, int from, String[] phrase) {
        if (from + phrase.length > words.length) {
            return false;
        }
        for (int offset = 0; offset < phrase.length; offset++) {
            if (!words[from + offset].equals(phrase[offset])) {
                return false;
            }
        }
        return true;
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

    /**
     * Emoji- or symbol-only messages («👍», «+») stay a remark as before. The old exact match let the owner
     * skip such a message by putting exactly that text into the list, so that one case is kept.
     */
    private boolean matchesListedSymbolsExactly(String messageText) {
        String legacy = legacyNormalize(messageText);
        return rawPhrases()
                .map(ClientChatAutoIgnoreService::legacyNormalize)
                .anyMatch(legacy::equals);
    }

    private Set<String> ignorePhrases() {
        return rawPhrases()
                .map(ClientChatAutoIgnoreService::normalize)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toSet());
    }

    private Stream<String> rawPhrases() {
        String raw = appSettingService.getStringAllowEmpty(
                AppSettingService.MANAGER_CONTROL_UNANSWERED_AUTO_IGNORE_PHRASES,
                DEFAULT_PHRASES
        );
        return Arrays.stream(raw.split(","));
    }

    private static boolean isAttachment(String messageText) {
        return messageText != null && ATTACHMENT_MARKER.matcher(messageText).find();
    }

    /**
     * Leaves only letters, digits and question marks, so emoji, punctuation and file-name dots around
     * a courtesy phrase («Спасибо!!! ❤️», «Спасибо🙏🏻») do not stop it from matching the list.
     */
    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        // NFC joins letters a phone may send decomposed («й» as «и» plus a mark), so the mark is not cut out.
        String text = Normalizer.normalize(value, Normalizer.Form.NFC)
                .toLowerCase(Locale.ROOT)
                .replace('ё', 'е');
        text = QUESTION_MARK_VARIANTS.matcher(text).replaceAll("?");
        return NON_WORD_RUN.matcher(text).replaceAll(" ").trim();
    }

    /** The matching before emoji were stripped: only plain punctuation is removed. */
    private static String legacyNormalize(String value) {
        if (value == null) {
            return "";
        }
        return LEGACY_PUNCTUATION.matcher(value.toLowerCase(Locale.ROOT).replace('ё', 'е'))
                .replaceAll(" ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
