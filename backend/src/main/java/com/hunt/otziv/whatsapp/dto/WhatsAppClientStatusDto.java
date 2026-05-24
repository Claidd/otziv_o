package com.hunt.otziv.whatsapp.dto;

public record WhatsAppClientStatusDto(
        String clientId,
        boolean configured,
        boolean ready,
        boolean authenticated,
        String state,
        String lastQrAt,
        String lastReadyAt,
        String lastError,
        boolean hasQr,
        String qrDataUrl,
        String message
) {
    public static WhatsAppClientStatusDto unconfigured(String clientId, String message) {
        return new WhatsAppClientStatusDto(
                clientId,
                false,
                false,
                false,
                "not_configured",
                null,
                null,
                null,
                false,
                null,
                message
        );
    }

    public static WhatsAppClientStatusDto unavailable(String clientId, String message) {
        return new WhatsAppClientStatusDto(
                clientId,
                true,
                false,
                false,
                "unavailable",
                null,
                null,
                message,
                false,
                null,
                message
        );
    }
}
