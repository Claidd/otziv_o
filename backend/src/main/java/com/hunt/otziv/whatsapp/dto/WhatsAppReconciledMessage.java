package com.hunt.otziv.whatsapp.dto;

public record WhatsAppReconciledMessage(
        String clientId,
        String groupId,
        String groupName,
        String from,
        String fromName,
        String messageId,
        Long timestamp,
        Boolean fromMe,
        Boolean systemGenerated,
        String message
) {
}
