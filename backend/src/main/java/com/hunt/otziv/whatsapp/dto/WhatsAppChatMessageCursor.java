package com.hunt.otziv.whatsapp.dto;

public record WhatsAppChatMessageCursor(
        String groupId,
        long afterTimestamp
) {
}
