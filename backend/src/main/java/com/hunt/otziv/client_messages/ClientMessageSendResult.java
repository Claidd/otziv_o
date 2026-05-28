package com.hunt.otziv.client_messages;

public record ClientMessageSendResult(
        boolean sent,
        String channel,
        String errorCode,
        String errorMessage
) {
    public static ClientMessageSendResult sent(String channel) {
        return new ClientMessageSendResult(true, channel, null, null);
    }

    public static ClientMessageSendResult failed(String errorCode, String errorMessage) {
        return new ClientMessageSendResult(false, null, errorCode, errorMessage);
    }
}
