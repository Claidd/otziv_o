package com.hunt.otziv.whatsapp.dto;

public record WhatsAppGroupSyncSettingsRequest(
        Boolean enabled,
        Integer intervalMinutes
) {
}
