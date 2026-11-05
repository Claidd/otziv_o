package com.hunt.otziv.client_chat_control.dto;

import com.hunt.otziv.client_chat_control.model.ClientChatPlatform;
import java.time.LocalDateTime;

/** The provider reported that an already sent chat message was edited; editedText is the new full text. */
public record ClientChatMessageEditCommand(
        ClientChatPlatform platform,
        String chatId,
        String externalMessageId,
        String editedText,
        LocalDateTime editedAt
) {
}
