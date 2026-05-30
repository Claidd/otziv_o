package com.hunt.otziv.archive.dto;

public record ArchiveOrdersSettingsResponse(
        int boardLiveSliceRetentionDays,
        int archiveRetentionDays,
        int batchSize,
        int maxBatchSize,
        boolean applyEnabled,
        boolean scheduleWorkerEnabled,
        boolean scheduleEnabled,
        String runMode,
        String reason,
        String scheduleTime,
        String scheduleCron,
        String scheduleZone
) {
}
