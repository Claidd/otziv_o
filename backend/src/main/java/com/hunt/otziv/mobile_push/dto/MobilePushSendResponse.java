package com.hunt.otziv.mobile_push.dto;

public record MobilePushSendResponse(
        boolean configured,
        int tokens,
        int sent,
        int failed
) {
}
