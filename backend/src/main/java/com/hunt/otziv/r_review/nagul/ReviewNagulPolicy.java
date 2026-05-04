package com.hunt.otziv.r_review.nagul;

import com.hunt.otziv.exceptions.BotTemplateNameException;
import com.hunt.otziv.exceptions.NagulTooFastException;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.u_users.model.Worker;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

@Component
public class ReviewNagulPolicy {

    private static final List<String> FORBIDDEN_PATTERNS = Arrays.asList(
            "имя", "фамилия", "фамилию", "впиши", "отчество", "fio", "name", "surname",
            "введите", "заполните", "укажите", "вставьте", "шаблон", "template", "пример"
    );

    public void validateWorkerCooldown(Worker worker, int cooldownMinutes) {
        if (worker.getLastNagulTime() == null) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        Duration duration = Duration.between(worker.getLastNagulTime(), now);

        if (duration.toMinutes() < cooldownMinutes) {
            long secondsLeft = cooldownMinutes * 60L - duration.getSeconds();
            long minutesLeft = secondsLeft / 60;
            long remainingSeconds = secondsLeft % 60;

            throw new NagulTooFastException(minutesLeft, remainingSeconds);
        }
    }

    public void validateBotName(Review review) {
        if (review.getBot() == null || review.getBot().getFio() == null) {
            return;
        }

        String botFio = review.getBot().getFio().trim();
        String botFioLower = botFio.toLowerCase();

        if (botFio.isEmpty()) {
            throw new BotTemplateNameException("Имя бота не может быть пустым");
        }

        if (botFio.matches(".*\\d.*")) {
            throw new BotTemplateNameException("Аккаунт не выгулян: имя содержит цифры");
        }

        List<String> forbiddenFullNames = Arrays.asList(
                "имя фамилию",
                "имя фамилия",
                "впиши имя фамилию",
                "впиши имя фамилия",
                "фамилия имя",
                "фамилию имя"
        );

        if (forbiddenFullNames.contains(botFioLower)) {
            throw new BotTemplateNameException("Аккаунт не выгулян: используется шаблонное имя");
        }

        String[] parts = botFio.split("\\s+");

        if (parts.length < 2 || parts.length > 3) {
            throw new BotTemplateNameException("Аккаунт не выгулян: имя должно быть в формате 'Имя Фамилия' или 'Имя Фамилия И.О.'");
        }

        for (int i = 0; i < 2; i++) {
            String word = parts[i];
            String wordLower = word.toLowerCase();

            for (String pattern : FORBIDDEN_PATTERNS) {
                if (wordLower.equals(pattern.toLowerCase())) {
                    throw new BotTemplateNameException("Аккаунт не выгулян: используется шаблонное имя");
                }
            }

            if (!word.matches("^[А-ЯA-Z][а-яa-z]+$")) {
                throw new BotTemplateNameException("Аккаунт не выгулян: неверный формат имени или фамилии");
            }

            if (word.length() < 2) {
                throw new BotTemplateNameException("Аккаунт не выгулян: имя или фамилия слишком короткие");
            }
        }

        if (parts.length == 3) {
            String initials = parts[2];
            if (!initials.matches("^[А-ЯA-Z](\\.?[А-ЯA-Z])?\\.?$")) {
                throw new BotTemplateNameException("Аккаунт не выгулян: неверный формат инициалов. Допустимые форматы: С.И., СИ, С., С, С.И, СИ.");
            }

            if (initials.contains("..")) {
                throw new BotTemplateNameException("Аккаунт не выгулян: некорректные инициалы (две точки подряд)");
            }

            String lettersOnly = initials.replace(".", "");
            for (char c : lettersOnly.toCharArray()) {
                if (!Character.isUpperCase(c)) {
                    throw new BotTemplateNameException("Аккаунт не выгулян: инициалы должны быть заглавными буквами");
                }
            }
        }

        if (parts[0].equalsIgnoreCase(parts[1])) {
            throw new BotTemplateNameException("Аккаунт не выгулян: имя и фамилия не могут быть одинаковыми");
        }
    }
}
