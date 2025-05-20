package com.hunt.otziv.whatsapp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;


import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;

import java.time.format.DateTimeFormatter;


@Data
@NoArgsConstructor
@AllArgsConstructor
public class StatDto {

    private String clientId;
    private int success;
    private int fail;

    private LocalDateTime firstSentTime;
    private LocalDateTime lastSentTime;

    private Set<Long> leadIds = new HashSet<>();

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    public void incrementSuccess(Long leadId) {
        success++;
        updateTime();
        leadIds.add(leadId);
    }

    public void incrementFail(Long leadId) {
        fail++;
        updateTime();
        leadIds.add(leadId);
    }

    private void updateTime() {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Irkutsk"));
        if (firstSentTime == null) {
            firstSentTime = now;
        }
        lastSentTime = now;
    }

    public String toReportLine() {
        return String.format(
                "• %s — ✅ %d / ❌ %d (с %s до %s, лидов: %d)",
                clientId,
                success,
                fail,
                firstSentTime != null ? firstSentTime.format(TIME_FORMAT) : "--:--",
                lastSentTime != null ? lastSentTime.format(TIME_FORMAT) : "--:--",
                leadIds.size()
        );
    }
}


