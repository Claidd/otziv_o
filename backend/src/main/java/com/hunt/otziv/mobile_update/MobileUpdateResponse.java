package com.hunt.otziv.mobile_update;

import java.time.Instant;

public record MobileUpdateResponse(
        boolean enabled,
        Integer versionCode,
        String versionName,
        Integer minSupportedVersionCode,
        Boolean required,
        String notes,
        String downloadUrl,
        Long fileSize,
        String sha256,
        Instant publishedAt
) {
    public static MobileUpdateResponse disabled() {
        return new MobileUpdateResponse(false, null, null, null, null, null, null, null, null, null);
    }

    public static MobileUpdateResponse from(MobileUpdateRelease release) {
        return new MobileUpdateResponse(
                true,
                release.versionCode(),
                release.versionName(),
                release.minSupportedVersionCode(),
                release.required(),
                release.notes(),
                "/api/mobile-update/download",
                release.fileSize(),
                release.sha256(),
                release.publishedAt()
        );
    }
}
