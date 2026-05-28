package com.hunt.otziv.client_messages;

public record ClientMessagePreview(
        String expectedChannel,
        String channelDetails,
        String paymentInstructionSource,
        String messagePreview
) {
}
