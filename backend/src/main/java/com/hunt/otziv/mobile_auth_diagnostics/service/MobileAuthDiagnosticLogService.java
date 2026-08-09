package com.hunt.otziv.mobile_auth_diagnostics.service;

import com.hunt.otziv.mobile_auth_diagnostics.dto.MobileAuthDiagnosticBatchRequest;
import com.hunt.otziv.mobile_auth_diagnostics.dto.MobileAuthDiagnosticEventRequest;
import com.hunt.otziv.webhook.security.WebhookClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MobileAuthDiagnosticLogService {

    private static final Logger diagnosticLog = LoggerFactory.getLogger("MOBILE_AUTH_DIAGNOSTICS");

    private final WebhookClientIpResolver clientIpResolver;

    public void logBatch(
            Principal principal,
            HttpServletRequest servletRequest,
            MobileAuthDiagnosticBatchRequest batch
    ) {
        String submittedBy = safe(principal == null ? null : principal.getName(), 128);
        String observedIp = maskAddress(clientIpResolver.resolve(servletRequest));
        String headerInstallation = safe(servletRequest.getHeader("X-Otziv-Installation-Id"), 128);
        String installationId = headerInstallation.equals("unknown")
                ? safe(batch.installationId(), 128)
                : headerInstallation;
        String devicePlatform = safe(servletRequest.getHeader("X-Otziv-Device-Platform"), 24);
        String deviceModel = safe(servletRequest.getHeader("X-Otziv-Device-Model"), 80);

        for (MobileAuthDiagnosticEventRequest event : batch.events()) {
            diagnosticLog.info(
                    "mobile_auth_event user={} installation={} batch={} eventId={} occurredAt={} lagSeconds={} type={} run={} app={}({}) device={}/{} network={}/{} ipPrefix={} details={}",
                    submittedBy,
                    installationId,
                    safe(batch.batchId(), 64),
                    safe(event.eventId(), 64),
                    event.occurredAt(),
                    lagSeconds(event.occurredAt()),
                    safe(event.type(), 64),
                    safe(event.runId(), 64),
                    safe(event.appVersion(), 32),
                    safe(event.appBuild(), 32),
                    devicePlatform,
                    deviceModel,
                    event.connected() ? "connected" : "offline",
                    safe(event.networkType(), 24),
                    observedIp,
                    safeDetails(event.details())
            );
        }
    }

    private long lagSeconds(Instant occurredAt) {
        return Math.max(0L, Duration.between(occurredAt, Instant.now()).getSeconds());
    }

    private String safeDetails(Map<String, String> details) {
        return new TreeMap<>(details).entrySet().stream()
                .map(entry -> safe(entry.getKey(), 48) + '=' + safeDetailValue(entry.getKey(), entry.getValue()))
                .collect(Collectors.joining(",", "{", "}"));
    }

    private String safeDetailValue(String key, String value) {
        String normalizedKey = key == null ? "" : key.toLowerCase();
        if (normalizedKey.equals("password")
                || normalizedKey.equals("access_token")
                || normalizedKey.equals("refresh_token")
                || normalizedKey.equals("id_token")
                || normalizedKey.equals("authorization")
                || normalizedKey.equals("authorization_code")
                || normalizedKey.equals("code_verifier")) {
            return "[redacted]";
        }
        String normalizedValue = safe(value, 200);
        if (normalizedValue.regionMatches(true, 0, "Bearer ", 0, 7)
                || normalizedValue.matches("eyJ[A-Za-z0-9_-]{20,}\\.[A-Za-z0-9_-]{20,}.*")) {
            return "[redacted]";
        }
        return normalizedValue;
    }

    private String safe(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String normalized = value.replaceAll("[\\p{Cntrl}]", " ").trim();
        return normalized.substring(0, Math.min(normalized.length(), maxLength));
    }

    private String maskAddress(String address) {
        if (address == null || address.isBlank()) {
            return "unknown";
        }
        String normalized = address.trim();
        String[] ipv4 = normalized.split("\\.");
        if (ipv4.length == 4) {
            return ipv4[0] + '.' + ipv4[1] + '.' + ipv4[2] + ".0/24";
        }
        int separator = normalized.indexOf(':');
        return separator > 0 ? normalized.substring(0, separator) + "::/64" : "unknown";
    }
}
