package com.hunt.otziv.performers.service;

import com.hunt.otziv.uploads.service.FileUploadGuard;
import com.hunt.otziv.s3.cleanup.service.S3ObjectCleanupQueue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PerformerAssignmentScreenshotStorage {

    private final S3Client s3Client;
    private final FileUploadGuard fileUploadGuard;
    private final S3ObjectCleanupQueue cleanupQueue;

    @Value("${s3.bucket}")
    private String bucket;

    @Value("${s3.projectId}")
    private String projectId;

    @Value("${s3.public-base-url:}")
    private String publicBaseUrl;

    public String store(MultipartFile file, Long assignmentId, ScreenshotKind kind, @Nullable String oldUrl) {
        if (assignmentId == null || kind == null) {
            throw new IllegalArgumentException("assignmentId and screenshot kind are required");
        }
        FileUploadGuard.ImageCheck imageCheck = fileUploadGuard.requireSupportedImage(file);
        String key = folder(kind) + "/" + assignmentId + "-" + UUID.randomUUID() + ".jpg";
        byte[] processedImage = processImage(imageCheck.bytes());
        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .acl("public-read")
                .contentType("image/jpeg")
                .build();

        s3Client.putObject(putRequest, RequestBody.fromBytes(processedImage));
        String url = publicObjectBaseUrl() + "/" + key;
        registerObjectLifecycle(key, ownedOldKey(oldUrl, assignmentId, kind));
        log.info("Скриншот задания исполнителя загружен: assignmentId={}, kind={}", assignmentId, kind);
        return url;
    }

    private byte[] processImage(byte[] source) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Thumbnails.of(new ByteArrayInputStream(source))
                    .size(1600, 1600)
                    .keepAspectRatio(true)
                    .outputFormat("jpg")
                    .outputQuality(0.82)
                    .toOutputStream(baos);
            return baos.toByteArray();
        } catch (IOException e) {
            log.error("Ошибка при обработке скриншота задания исполнителя", e);
            throw new RuntimeException("Не удалось обработать скриншот", e);
        }
    }

    private void registerObjectLifecycle(String newKey, @Nullable String oldKey) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteBestEffort(oldKey, "old");
                }

                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        deleteBestEffort(newKey, "rolled-back-new");
                    }
                }
            });
            return;
        }

        // Known callers are transactional. Preserve data if a future caller is
        // not: the new object is already durable, so only the obsolete object
        // can be safely removed here.
        deleteBestEffort(oldKey, "old");
    }

    private void deleteBestEffort(@Nullable String key, String reason) {
        if (key == null || key.isBlank()) {
            return;
        }
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build());
        } catch (RuntimeException exception) {
            cleanupQueue.enqueueBestEffort(bucket, key, "performer-" + reason);
            log.warn(
                    "Не удалось удалить скриншот задания; поставлен в очередь: reason={}, failureType={}",
                    reason,
                    exception.getClass().getSimpleName()
            );
        }
    }

    private String extractObjectKey(String url) {
        String publicPrefix = publicObjectBaseUrl() + "/";
        if (url.startsWith(publicPrefix)) {
            return url.substring(publicPrefix.length());
        }
        String legacyPrefix = "https://" + projectId + ".selstorage.ru/";
        if (url.startsWith(legacyPrefix)) {
            return url.substring(legacyPrefix.length());
        }
        return null;
    }

    private String ownedOldKey(@Nullable String oldUrl, Long assignmentId, ScreenshotKind kind) {
        if (oldUrl == null || oldUrl.isBlank()) {
            return null;
        }
        String key = extractObjectKey(oldUrl.trim());
        String expectedPrefix = folder(kind) + "/" + assignmentId + "-";
        if (key == null || !key.startsWith(expectedPrefix) || key.length() <= expectedPrefix.length()) {
            log.warn("Пропущено удаление старого скриншота: объект не принадлежит заданию");
            return null;
        }
        return key;
    }

    private String folder(ScreenshotKind kind) {
        return switch (kind) {
            case PERFORMER_PUBLICATION -> "performer-assignments/publication";
            case MANAGER_CONFIRMATION -> "performer-assignments/manager-confirmation";
        };
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

    public enum ScreenshotKind {
        PERFORMER_PUBLICATION,
        MANAGER_CONFIRMATION
    }
}
