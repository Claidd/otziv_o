package com.hunt.otziv.archive;

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
        String scheduleCron,
        String scheduleZone
) {
}
