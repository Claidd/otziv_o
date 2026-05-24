package com.hunt.otziv.whatsapp.service;

public record WhatsAppGroupSyncSettingsRequest(
        Boolean enabled,
        Integer intervalMinutes
) {
}
