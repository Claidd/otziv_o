package com.hunt.otziv.archive;

public record ArchiveOrdersSettingsRequest(
        Integer archiveRetentionDays,
        Integer batchSize,
        Boolean scheduleEnabled,
        String runMode,
        String reason
) {
}
