package com.hunt.otziv.b_bots.dto;

import java.time.Instant;

public record BrowserOpenResponse(
        String sessionId,
        String vncUrl,
        String vncPassword,
        int heartbeatIntervalSeconds,
        Instant expiresAt,
        Long botId,
        String userAgent,
        String platform,
        String screenResolution
) {
    public BrowserOpenResponse(
            String sessionId,
            String vncUrl,
            int heartbeatIntervalSeconds,
            Instant expiresAt
    ) {
        this(sessionId, vncUrl, null, heartbeatIntervalSeconds, expiresAt, null, null, null, null);
    }
}
