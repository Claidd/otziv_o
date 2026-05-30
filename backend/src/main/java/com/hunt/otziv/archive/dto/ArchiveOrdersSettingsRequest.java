package com.hunt.otziv.archive.dto;

public record ArchiveOrdersSettingsRequest(
        Integer archiveRetentionDays,
        Integer batchSize,
        Boolean applyEnabled,
        Boolean scheduleWorkerEnabled,
        Boolean scheduleEnabled,
        String runMode,
        String reason,
        String scheduleTime,
        String scheduleCron,
        String scheduleZone
) {
}
