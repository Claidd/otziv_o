package com.hunt.otziv.whatsapp.dto;

import java.util.List;

public record WhatsAppReconciledMessagesResponse(
        String status,
        String clientId,
        List<WhatsAppReconciledMessage> messages
) {
}
