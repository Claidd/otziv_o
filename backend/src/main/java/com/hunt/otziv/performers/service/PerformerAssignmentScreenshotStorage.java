package com.hunt.otziv.performers.service;

import com.hunt.otziv.uploads.service.FileUploadGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
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

    @Value("${s3.bucket}")
    private String bucket;

    @Value("${s3.projectId}")
    private String projectId;

    @Value("${s3.public-base-url:}")
    private String publicBaseUrl;

    public String store(MultipartFile file, Long assignmentId, ScreenshotKind kind, @Nullable String oldUrl) {
        FileUploadGuard.ImageCheck imageCheck = fileUploadGuard.requireSupportedImage(file);
        String key = folder(kind) + "/" + assignmentId + "-" + UUID.randomUUID() + ".jpg";

        deleteOldFile(oldUrl);

        byte[] processedImage = processImage(imageCheck.bytes());
        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .acl("public-read")
                .contentType("image/jpeg")
                .build();

        s3Client.putObject(putRequest, RequestBody.fromBytes(processedImage));
        String url = publicObjectBaseUrl() + "/" + key;
        log.info("Скриншот задания исполнителя загружен: assignmentId={}, kind={}, url={}", assignmentId, kind, url);
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

    private void deleteOldFile(@Nullable String oldUrl) {
        if (oldUrl == null || oldUrl.isBlank()) {
            return;
        }
        String key = extractObjectKey(oldUrl.trim());
        if (key == null) {
            log.warn("Пропущено удаление старого скриншота: URL не из нашего S3: {}", oldUrl);
            return;
        }
        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build());
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
