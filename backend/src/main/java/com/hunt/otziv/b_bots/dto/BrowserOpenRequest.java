package com.hunt.otziv.b_bots.dto;

public record BrowserOpenRequest(Boolean heartbeatSupported) {
    public boolean supportsHeartbeat() {
        return Boolean.TRUE.equals(heartbeatSupported);
    }
}
