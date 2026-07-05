package com.hunt.otziv.external_review_checks.service;

import com.hunt.otziv.external_review_checks.config.ExternalReviewCheckProperties;
import java.util.Base64;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExternalReviewScreenshotStorage {

    private final S3Client s3Client;
    private final ExternalReviewCheckProperties properties;

    @Value("${s3.bucket}")
    private String bucket;

    @Value("${s3.projectId}")
    private String projectId;

    @Value("${s3.public-base-url:}")
    private String publicBaseUrl;

    public StoredScreenshot store(Long checkId, Long reviewId, String screenshotBase64, @Nullable String contentType) {
        if (screenshotBase64 == null || screenshotBase64.isBlank()) {
            return null;
        }

        String normalizedContentType = normalizeContentType(contentType);
        String extension = extension(normalizedContentType);
        String key = normalizeFolder(properties.getS3Folder())
                + "/reviews/" + reviewId
                + "/" + checkId + "-" + UUID.randomUUID() + "." + extension;

        byte[] bytes = Base64.getDecoder().decode(stripDataPrefix(screenshotBase64));
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .acl("public-read")
                .contentType(normalizedContentType)
                .build();

        s3Client.putObject(request, RequestBody.fromBytes(bytes));
        String url = publicObjectBaseUrl() + "/" + key;
        log.info("Скриншот проверки отзыва загружен в S3: checkId={}, reviewId={}, url={}", checkId, reviewId, url);
        return new StoredScreenshot(key, url);
    }

    private String stripDataPrefix(String value) {
        int comma = value.indexOf(',');
        if (value.startsWith("data:") && comma >= 0) {
            return value.substring(comma + 1);
        }
        return value;
    }

    private String normalizeContentType(@Nullable String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "image/png";
        }
        String normalized = contentType.trim().toLowerCase();
        if ("image/jpeg".equals(normalized) || "image/jpg".equals(normalized)) {
            return "image/jpeg";
        }
        return "image/png";
    }

    private String extension(String contentType) {
        return "image/jpeg".equals(contentType) ? "jpg" : "png";
    }

    private String normalizeFolder(String folder) {
        if (folder == null || folder.isBlank()) {
            return "external-review-checks";
        }
        return folder.replace("\\", "/")
                .replaceAll("^/+", "")
                .replaceAll("/+$", "")
                .replaceAll("[^a-zA-Z0-9/_-]", "-");
    }

    private String publicObjectBaseUrl() {
        if (publicBaseUrl != null && !publicBaseUrl.isBlank()) {
            return trimTrailingSlash(publicBaseUrl.trim());
        }
        return "https://" + projectId + ".selstorage.ru";
    }

    private String trimTrailingSlash(String value) {
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    public record StoredScreenshot(String key, String url) {
    }
}
