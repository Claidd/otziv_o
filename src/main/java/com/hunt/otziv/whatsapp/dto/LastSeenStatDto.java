package com.hunt.otziv.whatsapp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Set;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LastSeenStatDto {
    private String clientId;
    private int processed;              // всего обработано
    private int hasWhatsApp;            // зарегистрированы в WhatsApp
    private int hasLastSeen;            // у кого есть lastSeen
    private int hiddenOrNotWhatsApp;    // скрыт / нет WhatsApp
    private LocalDateTime firstCheckTime;
    private LocalDateTime lastCheckTime;
    private Set<Long> leadIds = new HashSet<>();

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    public void incrementProcessed(Long leadId) {
        this.processed++;
        leadIds.add(leadId);
        updateTime();
    }

    public void incrementHasWhatsApp(Long leadId) {
        this.hasWhatsApp++;
        leadIds.add(leadId);
        updateTime();
    }

    public void incrementHasLastSeen(Long leadId) {
        this.hasLastSeen++;
        leadIds.add(leadId);
        updateTime();
    }

    public void incrementHidden(Long leadId) {
        this.hiddenOrNotWhatsApp++;
        leadIds.add(leadId);
        updateTime();
    }

    private void updateTime() {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Irkutsk"));
        if (firstCheckTime == null) firstCheckTime = now;
        lastCheckTime = now;
    }

    public String toReportLine() {
        return String.format(
                "• %s — Всего %d / WhatsApp %d / lastSeen %d / Скрыт/Нет WA %d (с %s до %s, лидов: %d)",
                clientId,
                processed,
                hasWhatsApp,
                hasLastSeen,
                hiddenOrNotWhatsApp,
                firstCheckTime != null ? firstCheckTime.format(TIME_FORMAT) : "--:--",
                lastCheckTime != null ? lastCheckTime.format(TIME_FORMAT) : "--:--",
                leadIds.size()
        );
    }
}


