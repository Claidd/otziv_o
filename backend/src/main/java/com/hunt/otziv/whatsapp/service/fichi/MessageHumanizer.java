package com.hunt.otziv.whatsapp.service.fichi;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

@Slf4j
public class MessageHumanizer {


    private final PersistentSentHashes sentHashes = new PersistentSentHashes();


    private final List<String> emojis = List.of("😊", "👍", "🎉", "🔥", "🙌", "😉", "💬", "📩", "👋", "💡");
    private final List<String> markers = List.of(
            "Спасибо за внимание.",
            "Хорошего дня!",
            "Всего доброго.",
            "Свяжитесь с нами, если нужно.",
            "Если будут вопросы — мы на связи.",
            "Работаем без предоплаты.",
            "Все тексты сначала согласуем.",
            "Отзывы пишем вручную, аккуратно.",
            "Публикуют только живые авторы.",
            "Оценки появляются в карточке быстро.",
            "Пишите — расскажем подробнее.",
            "Готовы к долгому сотрудничеству.",
            "Профили настоящие, из вашего региона.",
            "Гарантируем анонимность и результат.",
            "Пишите +, и мы всё вышлем.",
            "Поможем поднять рейтинг спокойно и точно.",
            "Пишите — покажем примеры работы.",
            "Работаем только по факту результата.",
            "Карточки растут после наших отзывов.",
            "Детали можем выслать прямо в этот чат."
    );


    private final Map<String, List<String>> synonyms = Map.ofEntries(
            Map.entry("здравствуйте", List.of("привет", "добрый день", "доброго времени суток", "рада вас приветствовать", "связались с вами по поводу отзывов")),
            Map.entry("напишите", List.of("+", "да", "оставьте сообщение", "свяжитесь с нами", "просто напишите в ответ", "дайте знать, если интересно")),
            Map.entry("отзыв", List.of("отметка", "рецензия", "положительный комментарий", "впечатление")),
            Map.entry("отзывы", List.of("отметки", "оценки", "мнения клиентов", "публикации", "позитивные отклики")),
            Map.entry("пишем", List.of("размещаем", "публикуем", "оформляем", "выкладываем", "составляем")),
            Map.entry("готов", List.of("написан", "подготовлен", "оформлен", "согласован", "завершён")),
            Map.entry("оплата", List.of("перевод", "вознаграждение", "расчёт", "деньги")),
            Map.entry("по факту", List.of("после результата", "после публикации", "только после размещения", "оплата после", "никаких предоплат")),
            Map.entry("подробнее", List.of("всё объясню", "расскажу детали", "напишу условия", "покажу примеры", "вышлю информацию")),
            Map.entry("повышаем рейтинг", List.of("поднимаем карточку", "увеличиваем лояльность", "добавляем звёзды", "улучшаем имидж", "продвигаем репутацию")),
            Map.entry("работаем", List.of("сотрудничаем", "занимаемся", "помогаем", "публикуем")),
            Map.entry("отзовиками", List.of("справочниками", "рецензиями", "мнениями", "отметками")),
            Map.entry("карточка", List.of("профиль", "компания на карте", "организация", "страница")),
            Map.entry("живые авторы", List.of("настоящие люди", "местные пользователи", "без накрутки", "ручная работа"))
    );



    private final Map<Character, Character> typoMap = Map.of(
            'а', 'a', 'е', 'e', 'о', '0', 'б', '6', 'и', 'u', 'с', 'c', 'к', 'k'
    );

    public String generate(String template) {
        for (int attempt = 0; attempt < 5; attempt++) {
            String msg = applyHumanization(template);
            String hash = DigestUtils.sha1Hex(msg);
            if (sentHashes.isNew(hash)) {
                return msg;
            }
        }
        log.warn("⚠️ Невозможно сгенерировать уникальное сообщение после 5 попыток");
        return template;
    }

    private String applyHumanization(String template) {
        String result = applySynonyms(template);

        List<String> sentences = new ArrayList<>(List.of(result.split("(?<=[.!?])\\s*")));
        if (sentences.size() > 1 && ThreadLocalRandom.current().nextBoolean()) {
            Collections.shuffle(sentences);
        }

        int emojiIdx = ThreadLocalRandom.current().nextInt(sentences.size());
        String emoji = emojis.get(ThreadLocalRandom.current().nextInt(emojis.size()));
        sentences.set(emojiIdx, insertEmojiSmart(sentences.get(emojiIdx), emoji));

        if (ThreadLocalRandom.current().nextInt(100) < 40) {
            sentences.add(markers.get(ThreadLocalRandom.current().nextInt(markers.size())));
        }

        String finalMessage = String.join(" ", sentences);
        return injectFakeTypos(finalMessage);
    }

    private String applySynonyms(String msg) {
        String result = msg;
        for (Map.Entry<String, List<String>> entry : synonyms.entrySet()) {
            if (result.toLowerCase().contains(entry.getKey())) {
                String replacement = entry.getValue().get(ThreadLocalRandom.current().nextInt(entry.getValue().size()));
                result = result.replaceAll("(?i)" + Pattern.quote(entry.getKey()), replacement);
            }
        }
        return result;
    }

    private String insertEmojiSmart(String sentence, String emoji) {
        int insertPos = Math.max(Math.max(sentence.lastIndexOf("."), sentence.lastIndexOf("!")), sentence.lastIndexOf("?"));
        if (insertPos == -1) {
            return sentence + " " + emoji;
        }
        boolean after = ThreadLocalRandom.current().nextBoolean();
        if (after) {
            return sentence.substring(0, insertPos + 1) + " " + emoji + sentence.substring(insertPos + 1);
        } else {
            return sentence.substring(0, insertPos) + " " + emoji + sentence.substring(insertPos);
        }
    }

    private String injectFakeTypos(String msg) {
        StringBuilder result = new StringBuilder();
        for (char c : msg.toCharArray()) {
            if (typoMap.containsKey(c) && ThreadLocalRandom.current().nextInt(100) < 10) {
                result.append(typoMap.get(c));
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }
}