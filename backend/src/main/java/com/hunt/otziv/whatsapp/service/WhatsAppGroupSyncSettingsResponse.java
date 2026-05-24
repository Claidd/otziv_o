package com.hunt.otziv.whatsapp.service;

public record WhatsAppGroupSyncSettingsResponse(
        boolean enabled,
        int intervalMinutes,
        String lastRunAt,
        int lastLinkedCount
) {
}
