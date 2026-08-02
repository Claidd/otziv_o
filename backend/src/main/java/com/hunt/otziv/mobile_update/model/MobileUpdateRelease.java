package com.hunt.otziv.mobile_update.model;

import java.time.Instant;

public record MobileUpdateRelease(
        int versionCode,
        String versionName,
        int minSupportedVersionCode,
        boolean required,
        String notes,
        String fileName,
        long fileSize,
        String sha256,
        Instant publishedAt
) {
}
