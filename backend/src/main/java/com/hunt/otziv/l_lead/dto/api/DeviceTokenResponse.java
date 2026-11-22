package com.hunt.otziv.l_lead.dto.api;

import java.time.LocalDateTime;

/**
 * A device token of a phone. {@code active} means «the token works now»: switched on and not expired. {@code status}
 * says why it does not: ACTIVE, EXPIRED, DISABLED, or LEGACY_NO_EXPIRY for an old token without an expiry date.
 */
public record DeviceTokenResponse(
        String token,
        LocalDateTime createdAt,
        boolean active,
        LocalDateTime expiresAt,
        String status
) {

    public static DeviceTokenResponse of(String token, LocalDateTime createdAt, LocalDateTime expiresAt,
            boolean enabled, LocalDateTime now) {
        String status;
        if (!enabled) {
            status = "DISABLED";
        } else if (expiresAt == null) {
            status = "LEGACY_NO_EXPIRY";
        } else if (!expiresAt.isAfter(now)) {
            status = "EXPIRED";
        } else {
            status = "ACTIVE";
        }
        boolean works = "ACTIVE".equals(status) || "LEGACY_NO_EXPIRY".equals(status);
        return new DeviceTokenResponse(token, createdAt, works, expiresAt, status);
    }
}
