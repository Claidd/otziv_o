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

    /**
     * Парсит текст статуса и возвращает дату/время в Иркутском времени.
     */
    public static Optional<LocalDateTime> parse(String statusText) {
        if (statusText == null || statusText.isBlank()) {
            return Optional.empty();
        }

        // Очистим всё лишнее (номер, "был(-а)", пробелы)
        statusText = statusText
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\+7\\s?\\d{3}[\\s-]?\\d{3}[\\s-]?\\d{2}[\\s-]?\\d{2}", "")
                .replace("был(-а)", "")
                .replace("был", "")
                .trim();

        LocalDate today = LocalDate.now(IRKUTSK_ZONE);
        LocalTime time = LocalTime.MIDNIGHT;

        // "в сети" → текущий момент (Иркутское)
        if (statusText.contains("в сети") || statusText.contains("online") || statusText.contains("last seen")) {
            return Optional.of(LocalDateTime.now(IRKUTSK_ZONE));
        }

        // "сегодня в HH:mm"
        if (statusText.startsWith("сегодня")) {
            time = extractTime(statusText);
            return Optional.of(LocalDateTime.of(today, time));
        }

        // "вчера в HH:mm"
        if (statusText.startsWith("вчера")) {
            time = extractTime(statusText);
            return Optional.of(LocalDateTime.of(today.minusDays(1), time));
        }

        // "21 июля в HH:mm"
        for (Map.Entry<String, Month> entry : RUSSIAN_MONTHS.entrySet()) {
            String monthName = entry.getKey();
            if (statusText.contains(monthName)) {
                Matcher m = Pattern.compile("(\\d{1,2})\\s+" + monthName + "\\s+в\\s+(\\d{1,2}):(\\d{2})")
                        .matcher(statusText);
                if (m.find()) {
                    int day = Integer.parseInt(m.group(1));
                    int hour = Integer.parseInt(m.group(2));
                    int minute = Integer.parseInt(m.group(3));

                    LocalDate date = LocalDate.of(today.getYear(), entry.getValue(), day);
                    return Optional.of(LocalDateTime.of(date, LocalTime.of(hour, minute)));
                }
            }
        }

        return Optional.empty(); // если ничего не распознали
    }

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


