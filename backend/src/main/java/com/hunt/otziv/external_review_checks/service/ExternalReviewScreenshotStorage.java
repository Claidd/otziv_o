package com.hunt.otziv.external_review_checks.service;

import com.hunt.otziv.external_review_checks.config.ExternalReviewCheckProperties;
import com.hunt.otziv.external_review_checks.config.ExternalReviewTimeoutPolicy;
import com.hunt.otziv.s3.cleanup.service.S3ObjectCleanupQueue;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExternalReviewScreenshotStorage {

    private final S3Client s3Client;
    private final ExternalReviewCheckProperties properties;
    private final S3ObjectCleanupQueue cleanupQueue;

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

        String encoded = stripDataPrefix(screenshotBase64);
        long maximumBytes = Math.max(1L, properties.getScreenshotMaxBytes());
        long maximumEncodedCharacters = 4L * ((maximumBytes + 2L) / 3L);
        if (encoded.length() > maximumEncodedCharacters) {
            throw new IllegalArgumentException("Screenshot payload exceeds configured limit");
        }

        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException invalidBase64) {
            throw new IllegalArgumentException("Screenshot payload is not valid Base64");
        }
        if (bytes.length == 0 || bytes.length > maximumBytes) {
            throw new IllegalArgumentException("Screenshot payload exceeds configured limit");
        }

        String normalizedContentType = normalizeContentType(contentType, bytes);
        String extension = extension(normalizedContentType);
        String key = normalizeFolder(properties.getS3Folder())
                + "/reviews/" + reviewId
                + "/" + checkId + "-" + UUID.randomUUID() + "." + extension;

        Duration uploadTimeout = ExternalReviewTimeoutPolicy.screenshotUploadTimeout(properties);
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .acl("public-read")
                .contentType(normalizedContentType)
                .overrideConfiguration(builder -> builder
                        .apiCallTimeout(uploadTimeout)
                        .apiCallAttemptTimeout(uploadTimeout))
                .build();

        s3Client.putObject(request, RequestBody.fromBytes(bytes));
        String url = publicObjectBaseUrl() + "/" + key;
        log.info(
                "Скриншот проверки отзыва загружен в S3: checkId={}, reviewId={}, keyHash={}",
                checkId,
                reviewId,
                keyFingerprint(key)
        );
        return new StoredScreenshot(key, url);
    }

    public void deleteBestEffort(@Nullable StoredScreenshot screenshot) {
        if (screenshot != null) {
            deleteBestEffort(screenshot.key());
        }
    }

    public void deleteBestEffort(@Nullable String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        try {
            Duration timeout = ExternalReviewTimeoutPolicy.screenshotUploadTimeout(properties);
            DeleteObjectRequest request = DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .overrideConfiguration(builder -> builder
                            .apiCallTimeout(timeout)
                            .apiCallAttemptTimeout(timeout))
                    .build();
            s3Client.deleteObject(request);
            log.info("Удалён устаревший скриншот проверки: keyHash={}", keyFingerprint(key));
        } catch (RuntimeException exception) {
            cleanupQueue.enqueueBestEffort(bucket, key, "external-review-screenshot");
            log.warn(
                    "Не удалось удалить устаревший скриншот проверки; поставлен в очередь: keyHash={}, failureType={}",
                    keyFingerprint(key),
                    exception.getClass().getSimpleName()
            );
        }
    }

    private String stripDataPrefix(String value) {
        int comma = value.indexOf(',');
        if (value.startsWith("data:") && comma >= 0) {
            return value.substring(comma + 1);
        }
        return value;
    }

    private String normalizeContentType(@Nullable String contentType, byte[] bytes) {
        if (contentType == null || contentType.isBlank()) {
            if (isPng(bytes)) {
                return "image/png";
            }
            if (isJpeg(bytes)) {
                return "image/jpeg";
            }
            throw new IllegalArgumentException("Screenshot signature is not PNG or JPEG");
        }
        String normalized = contentType.trim().toLowerCase(Locale.ROOT);
        if ("image/jpeg".equals(normalized) || "image/jpg".equals(normalized)) {
            if (!isJpeg(bytes)) {
                throw new IllegalArgumentException("Screenshot signature does not match JPEG content type");
            }
            return "image/jpeg";
        }
        if ("image/png".equals(normalized)) {
            if (!isPng(bytes)) {
                throw new IllegalArgumentException("Screenshot signature does not match PNG content type");
            }
            return "image/png";
        }
        throw new IllegalArgumentException("Screenshot content type is not supported");
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

    private boolean isPng(byte[] bytes) {
        byte[] signature = new byte[] {
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        };
        if (bytes.length < signature.length) {
            return false;
        }
        for (int index = 0; index < signature.length; index++) {
            if (bytes[index] != signature[index]) {
                return false;
            }
        }
        return true;
    }

    private boolean isJpeg(byte[] bytes) {
        return bytes.length >= 4
                && bytes[0] == (byte) 0xFF
                && bytes[1] == (byte) 0xD8
                && bytes[2] == (byte) 0xFF
                && bytes[bytes.length - 2] == (byte) 0xFF
                && bytes[bytes.length - 1] == (byte) 0xD9;
    }

    private String keyFingerprint(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException exception) {
            return "unavailable";
        }
    }

    public record StoredScreenshot(String key, String url) {
    }
}
