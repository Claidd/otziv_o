package com.hunt.otziv.client_chat_control.dto;

import com.hunt.otziv.client_chat_control.model.ClientChatPlatform;
import java.time.LocalDateTime;

public record ClientChatUnansweredExample(
        Long id,
        ClientChatPlatform platform,
        Long companyId,
        String companyTitle,
        String chatId,
        String chatTitle,
        String senderName,
        String lastMessageText,
        LocalDateTime lastClientMessageAt,
        long waitingMinutes,
        String targetUrl,
        String chatUrl,
        String specialistName
) {
}
