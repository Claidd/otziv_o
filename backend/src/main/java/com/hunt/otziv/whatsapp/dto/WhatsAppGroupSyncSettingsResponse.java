package com.hunt.otziv.whatsapp.dto;

public record WhatsAppGroupSyncSettingsResponse(
        boolean enabled,
        int intervalMinutes,
        String lastRunAt,
        int lastLinkedCount
) {
}
