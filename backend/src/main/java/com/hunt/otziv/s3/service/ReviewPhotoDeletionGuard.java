package com.hunt.otziv.s3.service;

import com.hunt.otziv.p_products.api.ReviewPhotoRecords;
import com.hunt.otziv.s3.cleanup.service.ObjectDeletionGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ReviewPhotoDeletionGuard implements ObjectDeletionGuard {
    private final ReviewPhotoRecords records;
    @Value("${s3.bucket}") private String bucket;
    @Value("${s3.public-base-url:}") private String publicBaseUrl;
    @Value("${s3.projectId}") private String projectId;

    @Override
    public boolean mayDelete(String candidateBucket, String key) {
        if (!bucket.equals(candidateBucket) || !key.startsWith("reviews/")) return true;
        String legacy = "https://" + projectId + ".selstorage.ru/" + key;
        boolean legacyUnused = records.retireForDeletion(legacy);
        String base = publicBaseUrl == null ? "" : publicBaseUrl.trim().replaceAll("/+$", "");
        boolean publicUnused = base.isBlank() || (base + "/" + key).equals(legacy)
                || records.retireForDeletion(base + "/" + key);
        return legacyUnused && publicUnused;
    }
}
