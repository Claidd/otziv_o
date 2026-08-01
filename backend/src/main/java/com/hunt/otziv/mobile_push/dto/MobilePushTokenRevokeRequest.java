package com.hunt.otziv.mobile_push.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MobilePushTokenRevokeRequest(
        @NotBlank
        @Size(max = 512)
        String token
) {
}
