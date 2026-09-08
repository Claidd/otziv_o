package com.hunt.otziv.client_messages.dto;

public record ClientMessageSendResult(
        boolean sent,
        String channel,
        String errorCode,
        String errorMessage,
        String messageId
) {
    public ClientMessageSendResult(boolean sent, String channel, String errorCode, String errorMessage) {
        this(sent, channel, errorCode, errorMessage, null);
    }

    public static ClientMessageSendResult sent(String channel) {
        return new ClientMessageSendResult(true, channel, null, null);
    }

    public static ClientMessageSendResult sent(String channel, String messageId) {
        return new ClientMessageSendResult(true, channel, null, null, messageId);
    }

    public static ClientMessageSendResult failed(String errorCode, String errorMessage) {
        return new ClientMessageSendResult(false, null, errorCode, errorMessage);
    }
}
