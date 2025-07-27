package com.hunt.otziv.whatsapp.service.fichi;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Month;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.time.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.time.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LastSeenParser {

    private static final ZoneId IRKUTSK_ZONE = ZoneId.of("Asia/Irkutsk");

    private static final Map<String, Month> RUSSIAN_MONTHS = Map.ofEntries(
            Map.entry("января", Month.JANUARY),
            Map.entry("февраля", Month.FEBRUARY),
            Map.entry("марта", Month.MARCH),
            Map.entry("апреля", Month.APRIL),
            Map.entry("мая", Month.MAY),
            Map.entry("июня", Month.JUNE),
            Map.entry("июля", Month.JULY),
            Map.entry("августа", Month.AUGUST),
            Map.entry("сентября", Month.SEPTEMBER),
            Map.entry("октября", Month.OCTOBER),
            Map.entry("ноября", Month.NOVEMBER),
            Map.entry("декабря", Month.DECEMBER)
    );

    // Список дней недели для парсинга "суббота в 06:56"
    private static final List<String> RUSSIAN_WEEKDAYS = List.of(
            "понедельник", "вторник", "среда",
            "четверг", "пятница", "суббота", "воскресенье"
    );

    /**
     * Парсит текст статуса WhatsApp и возвращает дату/время в Иркутском времени.
     */
    public static Optional<LocalDateTime> parse(String statusText) {
        if (statusText == null || statusText.isBlank()) {
            return Optional.empty();
        }

        // Очистим лишнее (номер, "был(-а)", пробелы), сохраняя время с двоеточиями
        statusText = statusText
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\+7\\s?\\d{3}[\\s-]?\\d{3}[\\s-]?\\d{2}[\\s-]?\\d{2}", " ")
                .replace("был(-а):", "был(-а) ")  // убираем двоеточие только в этом месте
                .replace("был(-а)", "")
                .replace("был", "")
                .trim();

        LocalDate today = LocalDate.now(IRKUTSK_ZONE);

        // "в сети" → текущий момент (Иркутское время)
        if (statusText.contains("в сети") || statusText.contains("online") || statusText.contains("last seen")) {
            LocalDateTime now = LocalDateTime.now(IRKUTSK_ZONE);
            logParsed(statusText, now);
            return Optional.of(now);
        }

        // "сегодня в HH:mm"
        if (statusText.startsWith("сегодня")) {
            LocalDateTime result = LocalDateTime.of(today, extractTime(statusText));
            logParsed(statusText, result);
            return Optional.of(result);
        }

        // "вчера в HH:mm"
        if (statusText.startsWith("вчера")) {
            LocalDateTime result = LocalDateTime.of(today.minusDays(1), extractTime(statusText));
            logParsed(statusText, result);
            return Optional.of(result);
        }

        // "суббота в HH:mm" (или другой день недели)
        for (int i = 0; i < RUSSIAN_WEEKDAYS.size(); i++) {
            String weekday = RUSSIAN_WEEKDAYS.get(i);
            if (statusText.startsWith(weekday)) {
                LocalTime parsedTime = extractTime(statusText);
                LocalDate targetDate = findPreviousWeekday(i);
                LocalDateTime result = LocalDateTime.of(targetDate, parsedTime);
                logParsed(statusText, result);
                return Optional.of(result);
            }
        }

        // "21 июля в HH:mm" (русский месяц)
        for (Map.Entry<String, Month> entry : RUSSIAN_MONTHS.entrySet()) {
            String monthName = entry.getKey();
            if (statusText.contains(monthName)) {
                Matcher m = Pattern.compile("(\\d{1,2})\\s+" + monthName + "\\s+в\\s*(\\d{1,2}):(\\d{2})")
                        .matcher(statusText);
                if (m.find()) {
                    int day = Integer.parseInt(m.group(1));
                    int hour = Integer.parseInt(m.group(2));
                    int minute = Integer.parseInt(m.group(3));

                    LocalDate date = LocalDate.of(today.getYear(), entry.getValue(), day);
                    LocalDateTime result = LocalDateTime.of(date, LocalTime.of(hour, minute));
                    logParsed(statusText, result);
                    return Optional.of(result);
                }
            }
        }

        // "25.06.2025 в HH:mm" (числовая дата)
        Matcher numericDate = Pattern.compile("(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})\\s+в\\s*(\\d{1,2}):(\\d{2})")
                .matcher(statusText);
        if (numericDate.find()) {
            int day = Integer.parseInt(numericDate.group(1));
            int month = Integer.parseInt(numericDate.group(2));
            int year = Integer.parseInt(numericDate.group(3));
            int hour = Integer.parseInt(numericDate.group(4));
            int minute = Integer.parseInt(numericDate.group(5));

            LocalDate date = LocalDate.of(year, month, day);
            LocalDateTime result = LocalDateTime.of(date, LocalTime.of(hour, minute));
            logParsed(statusText, result);
            return Optional.of(result);
        }

        System.out.println("[LastSeenParser] Не удалось распарсить: " + statusText);
        return Optional.empty(); // если ничего не распознали
    }

    /**
     * Логирует результат парсинга для отладки.
     */
    private static void logParsed(String original, LocalDateTime result) {
        System.out.println("[LastSeenParser] Распознано: '" + original + "' → " + result);
    }

    /**
     * Ищет дату предыдущего дня недели.
     * @param targetDayIndex 0 = понедельник, ... 6 = воскресенье
     */
    private static LocalDate findPreviousWeekday(int targetDayIndex) {
        LocalDate today = LocalDate.now(IRKUTSK_ZONE);
        int todayIndex = (today.getDayOfWeek().getValue() % 7); // 1=понедельник..7=воскресенье
        int diff = todayIndex - (targetDayIndex + 1);
        if (diff <= 0) diff += 7;
        return today.minusDays(diff);
    }

    /**
     * Извлекает время (HH:mm) из текста.
     */
    private static LocalTime extractTime(String text) {
        Matcher matcher = Pattern.compile("(\\d{1,2}):(\\d{2})").matcher(text);
        if (matcher.find()) {
            int hour = Integer.parseInt(matcher.group(1));
            int minute = Integer.parseInt(matcher.group(2));
            return LocalTime.of(hour, minute);
        }
        return LocalTime.MIDNIGHT;
    }
}




