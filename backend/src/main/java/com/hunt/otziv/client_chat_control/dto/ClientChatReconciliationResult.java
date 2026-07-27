package com.hunt.otziv.client_chat_control.dto;

public record ClientChatReconciliationResult(
        int requestedChats,
        int receivedMessages,
        int openBefore,
        int openAfter,
        int closedItems
) {
}
