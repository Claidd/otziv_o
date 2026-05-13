package com.hunt.otziv.reputationai.api.dto;

import java.time.LocalDateTime;

public record OpenAiProviderDiagnostics(
        String baseUrl,
        boolean configured,
        boolean proxyEnabled,
        boolean proxyConfigured,
        boolean proxyAuthConfigured,
        String proxyHost,
        int proxyPort,
        boolean requestGoesThroughProxy,
        String route,
        String lastCheckStatus,
        Integer lastHttpStatus,
        String lastMessage,
        LocalDateTime lastCheckedAt
) {
    public OpenAiProviderDiagnostics {
        baseUrl = normalize(baseUrl);
        proxyHost = normalize(proxyHost);
        route = normalize(route);
        lastCheckStatus = normalize(lastCheckStatus);
        lastMessage = normalize(lastMessage);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
