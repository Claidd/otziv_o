package com.hunt.otziv.client_campaigns;

import java.time.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class CampaignSchedule {
    static final ZoneId ZONE = ZoneId.of("Asia/Irkutsk");
    private CampaignSchedule() {}
    static LocalDate day(LocalDateTime utc) { return local(utc).toLocalDate(); }
    static LocalDateTime local(LocalDateTime utc) { return utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(ZONE).toLocalDateTime(); }
    static LocalDateTime allowed(LocalDateTime desiredUtc, CampaignModels.Settings settings) {
        var local = local(desiredUtc);
        var start = LocalTime.parse(settings.windowStart());
        var end = LocalTime.parse(settings.windowEnd());
        if (local.toLocalTime().isBefore(start)) local = local.toLocalDate().atTime(start);
        else if (!local.toLocalTime().isBefore(end)) local = local.toLocalDate().plusDays(1).atTime(start);
        return local.atZone(ZONE).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
    static LocalDateTime nextDay(LocalDateTime utc, CampaignModels.Settings settings) {
        return day(utc).plusDays(1).atTime(LocalTime.parse(settings.windowStart()))
                .atZone(ZONE).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
    static void validate(CampaignModels.Settings s) {
        if (s == null || s.title() == null || s.title().isBlank() || s.title().length() > 120
                || s.message() == null || s.message().isBlank() || s.message().length() > 1000
                || s.dailyLimit() < 1 || s.dailyLimit() > 10000 || s.intervalMinutes() < 1 || s.intervalMinutes() > 1440
                || !(s.includeActive() || s.includeStopped() || s.includeBanned())
                || !("ATTACHMENT".equals(s.fileMode()) || "LINK".equals(s.fileMode())))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Проверьте текст, лимит, интервал и выберите хотя бы один список");
        try {
            if (!LocalTime.parse(s.windowEnd()).isAfter(LocalTime.parse(s.windowStart()))) throw new IllegalArgumentException();
        } catch (RuntimeException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Время окончания должно быть позже начала (HH:mm)");
        }
    }
}
