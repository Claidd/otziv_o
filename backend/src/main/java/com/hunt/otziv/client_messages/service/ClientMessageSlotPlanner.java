package com.hunt.otziv.client_messages.service;

import java.time.format.DateTimeParseException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ClientMessageSlotPlanner {

    public static final ZoneId IRKUTSK_ZONE = ZoneId.of("Asia/Irkutsk");
    public static final String DEFAULT_WINDOWS_SPEC = "10:00-12:00,14:00-17:00,19:00-21:00";

    private static final List<Window> WINDOWS = List.of(
            new Window(LocalTime.of(10, 0), LocalTime.of(12, 0)),
            new Window(LocalTime.of(14, 0), LocalTime.of(17, 0)),
            new Window(LocalTime.of(19, 0), LocalTime.of(21, 0))
    );

    public LocalDateTime nextAllowedAt(LocalDateTime desiredLocalTime) {
        return nextAllowedAt(desiredLocalTime, DEFAULT_WINDOWS_SPEC);
    }

    public LocalDateTime nextAllowedAt(LocalDateTime desiredLocalTime, String windowsSpec) {
        LocalDate date = desiredLocalTime.toLocalDate();
        LocalTime time = desiredLocalTime.toLocalTime();
        List<Window> windows = parseWindowsOrDefault(windowsSpec);

        for (Window window : windows) {
            if (time.isBefore(window.start())) {
                return date.atTime(window.start());
            }
            if (!time.isBefore(window.start()) && time.isBefore(window.end())) {
                return desiredLocalTime.withNano(0);
            }
        }

        return date.plusDays(1).atTime(windows.getFirst().start());
    }

    public boolean isAllowedNow(LocalDateTime localTime) {
        return isAllowedNow(localTime, DEFAULT_WINDOWS_SPEC);
    }

    public boolean isAllowedNow(LocalDateTime localTime, String windowsSpec) {
        LocalTime time = localTime.toLocalTime();
        return parseWindowsOrDefault(windowsSpec).stream()
                .anyMatch(window -> !time.isBefore(window.start()) && time.isBefore(window.end()));
    }

    public LocalDateTime afterGap(LocalDateTime candidateLocalTime, LocalDateTime lastSentLocalTime, int gapSeconds) {
        return afterGap(candidateLocalTime, lastSentLocalTime, gapSeconds, DEFAULT_WINDOWS_SPEC);
    }

    public LocalDateTime afterGap(
            LocalDateTime candidateLocalTime,
            LocalDateTime lastSentLocalTime,
            int gapSeconds,
            String windowsSpec
    ) {
        if (lastSentLocalTime == null || gapSeconds <= 0) {
            return nextAllowedAt(candidateLocalTime, windowsSpec);
        }
        LocalDateTime afterGap = lastSentLocalTime.plusSeconds(gapSeconds);
        LocalDateTime desired = candidateLocalTime.isAfter(afterGap) ? candidateLocalTime : afterGap;
        return nextAllowedAt(desired, windowsSpec);
    }

    public static boolean isValidWindowsSpec(String windowsSpec) {
        try {
            parseWindows(windowsSpec);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private List<Window> parseWindowsOrDefault(String windowsSpec) {
        try {
            return parseWindows(windowsSpec);
        } catch (IllegalArgumentException exception) {
            return WINDOWS;
        }
    }

    private static List<Window> parseWindows(String windowsSpec) {
        String value = windowsSpec == null || windowsSpec.isBlank() ? DEFAULT_WINDOWS_SPEC : windowsSpec.trim();
        List<Window> windows = Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .map(ClientMessageSlotPlanner::parseWindow)
                .sorted((left, right) -> left.start().compareTo(right.start()))
                .toList();
        if (windows.isEmpty()) {
            throw new IllegalArgumentException("Укажите хотя бы одно окно отправки");
        }
        for (int index = 1; index < windows.size(); index++) {
            if (!windows.get(index).start().isAfter(windows.get(index - 1).end())) {
                throw new IllegalArgumentException("Окна отправки не должны пересекаться");
            }
        }
        return windows;
    }

    private static Window parseWindow(String value) {
        String[] parts = value.split("-");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Окно отправки должно быть в формате HH:mm-HH:mm");
        }
        try {
            LocalTime start = LocalTime.parse(parts[0].trim());
            LocalTime end = LocalTime.parse(parts[1].trim());
            if (!end.isAfter(start)) {
                throw new IllegalArgumentException("Конец окна должен быть позже начала");
            }
            return new Window(start, end);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("Окно отправки должно быть в формате HH:mm-HH:mm");
        }
    }

    private record Window(LocalTime start, LocalTime end) {
    }
}
