package com.hunt.otziv.client_chat_control.dto;

import com.hunt.otziv.client_chat_control.model.ClientChatDirection;
import com.hunt.otziv.client_chat_control.model.ClientChatPlatform;
import java.time.LocalDateTime;

public record ClientChatMessageCommand(
        ClientChatPlatform platform,
        ClientChatDirection direction,
        String chatId,
        String chatTitle,
        String externalMessageId,
        String senderExternalId,
        String senderName,
        String messageText,
        LocalDateTime messageAt
) {
}
