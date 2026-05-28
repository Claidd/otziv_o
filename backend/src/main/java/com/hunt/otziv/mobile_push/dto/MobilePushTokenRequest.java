package com.hunt.otziv.mobile_push.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MobilePushTokenRequest(
        @NotBlank
        @Size(max = 512)
        String token,

        @Size(max = 32)
        String platform,

        @Size(max = 128)
        String deviceId,

        @Size(max = 64)
        String appVersion
) {
}
