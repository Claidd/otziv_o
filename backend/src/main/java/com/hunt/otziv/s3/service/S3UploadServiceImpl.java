package com.hunt.otziv.s3.service;

import com.hunt.otziv.uploads.service.FileUploadGuard;
import com.hunt.otziv.s3.cleanup.service.S3ObjectCleanupQueue;
import lombok.RequiredArgsConstructor;

import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import net.coobird.thumbnailator.geometry.Positions;
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

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class S3UploadServiceImpl implements S3UploadService {

    @Value("${s3.bucket}")
    private String bucket;

    @Value("${s3.region}")
    private String region;

    @Value("${s3.projectId}")
    private String projectId;

    @Value("${s3.public-base-url:}")
    private String publicBaseUrl;

    private final S3Client s3Client;
    private final FileUploadGuard fileUploadGuard;
    private final S3ObjectCleanupQueue cleanupQueue;

    @Override
    public String uploadFile(MultipartFile file, String folder, @Nullable String oldUrl, Long reviewId) {
        FileUploadGuard.ImageCheck imageCheck = fileUploadGuard.requireSupportedImage(file);
        String filename = generatedImageName(reviewId);
        String key = normalizeFolder(folder) + "/" + filename;
        byte[] processedImage = processImage(imageCheck.bytes());

        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .acl("public-read")
                .contentType("image/jpeg")
                .build();

        s3Client.putObject(putRequest, RequestBody.fromBytes(processedImage));
        registerRollbackCleanup(key);

        String url = publicObjectBaseUrl() + "/" + key;
        log.info("Новое фото загружено: {}", url);
        return url;
    }

    @Override
    public void deleteFileAfterCommit(
            @Nullable String url,
            String folder,
            @Nullable Long ownerId
    ) {
        if (url == null || url.isBlank() || ownerId == null) {
            return;
        }

        String ownedKey = extractOwnedObjectKey(url, folder, ownerId);
        if (ownedKey == null) {
            log.warn("Пропущено удаление: URL не принадлежит ожидаемому объекту");
            return;
        }

        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteObjectBestEffort(ownedKey, "replaced-upload");
                }
            });
            return;
        }

        deleteObjectBestEffort(ownedKey, "replaced-upload");
    }

    private void deleteObjectBestEffort(String oldKey, String reason) {
        log.info("Удаление устаревшего файла из хранилища");

        DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(oldKey)
                .build();

        try {
            s3Client.deleteObject(deleteRequest);
        } catch (RuntimeException exception) {
            cleanupQueue.enqueueBestEffort(bucket, oldKey, reason);
            log.warn(
                    "Не удалось удалить файл из хранилища; поставлен в очередь: failureType={}",
                    exception.getClass().getSimpleName()
            );
        }
    }

    private void registerRollbackCleanup(String newKey) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    deleteObjectBestEffort(newKey, "transaction-rollback");
                }
            }
        });
    }

    private byte[] processImage(byte[] source) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Thumbnails.of(new ByteArrayInputStream(source))
                    .size(1200, 1000)
                    .crop(Positions.CENTER)
                    .outputFormat("jpg")
                    .outputQuality(0.7)
                    .toOutputStream(baos);

            return baos.toByteArray();
        } catch (IOException e) {
            log.error("Ошибка при обработке изображения", e);
            throw new RuntimeException("Не удалось обработать изображение", e);
        }
    }

    private String generatedImageName(@Nullable Long reviewId) {
        if (reviewId != null) {
            return reviewId + "-" + UUID.randomUUID() + ".jpg";
        }
        return UUID.randomUUID() + ".jpg";
    }

    private String normalizeFolder(String folder) {
        if (folder == null || folder.isBlank()) {
            return "uploads";
        }
        return folder.replace("\\", "/")
                .replaceAll("^/+", "")
                .replaceAll("/+$", "")
                .replaceAll("[^a-zA-Z0-9/_-]", "-");
    }

    private String extractObjectKey(String oldUrl) {
        String normalizedUrl = oldUrl.trim();
        String publicPrefix = publicObjectBaseUrl() + "/";
        if (normalizedUrl.startsWith(publicPrefix)) {
            return normalizedUrl.substring(publicPrefix.length());
        }

        String legacyPrefix = legacyObjectBaseUrl() + "/";
        if (normalizedUrl.startsWith(legacyPrefix)) {
            return normalizedUrl.substring(legacyPrefix.length());
        }

        return null;
    }

    private String extractOwnedObjectKey(String oldUrl, String folder, Long ownerId) {
        String key = extractObjectKey(oldUrl);
        if (key == null) {
            return null;
        }

        // Historical uploads used "<id>-<original filename>" while current
        // uploads use "<id>-<uuid>.jpg". Requiring the stable folder/id prefix
        // supports both formats and prevents cross-entity object deletion.
        String expectedPrefix = normalizeFolder(folder) + "/" + ownerId + "-";
        return key.startsWith(expectedPrefix) && key.length() > expectedPrefix.length()
                ? key
                : null;
    }

    private String publicObjectBaseUrl() {
        if (publicBaseUrl != null && !publicBaseUrl.isBlank()) {
            return trimTrailingSlash(publicBaseUrl.trim());
        }
        return legacyObjectBaseUrl();
    }

    private String legacyObjectBaseUrl() {
        return "https://" + projectId + ".selstorage.ru";
    }

    private String trimTrailingSlash(String value) {
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
