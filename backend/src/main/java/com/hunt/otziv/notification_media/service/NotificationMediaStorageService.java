package com.hunt.otziv.notification_media.service;

import com.hunt.otziv.uploads.service.FileUploadGuard;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationMediaStorageService {

    private final S3Client s3Client;
    private final FileUploadGuard fileUploadGuard;

    @Value("${s3.bucket}")
    private String bucket;

    @Value("${s3.projectId}")
    private String projectId;

    @Value("${s3.public-base-url:}")
    private String publicBaseUrl;

    @Value("${notification-media.s3-folder:notification-media}")
    private String rootFolder;

    public StoredNotificationImage store(MultipartFile file, String eventCode) {
        FileUploadGuard.ImageCheck image = fileUploadGuard.requireSupportedImage(file);
        String folder = normalizeFolder(rootFolder);
        String eventFolder = normalizeEventCode(eventCode);
        ProcessedImage processed = processImage(image);
        String key = folder + "/" + eventFolder + "/" + UUID.randomUUID() + "." + processed.extension();

        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .acl("public-read")
                .contentType(processed.contentType())
                .build();
        s3Client.putObject(request, RequestBody.fromBytes(processed.bytes()));

        String url = publicObjectBaseUrl() + "/" + key;
        log.info("Картинка уведомления загружена в S3: eventCode={}, key={}", eventCode, key);
        return new StoredNotificationImage(key, url, processed.contentType());
    }

    public void delete(String storageKey) {
        if (!isAllowedStorageKey(storageKey)) {
            log.warn("Удаление картинки уведомления пропущено: недопустимый S3 key={}", storageKey);
            return;
        }
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(storageKey)
                    .build());
            log.info("Картинка уведомления удалена из S3: key={}", storageKey);
        } catch (RuntimeException exception) {
            log.warn("Не удалось удалить картинку уведомления из S3 key={}: {}",
                    storageKey, exception.getMessage());
        }
    }

    public byte[] load(String storageKey) {
        if (!isAllowedStorageKey(storageKey)) {
            throw new IllegalArgumentException("Недопустимый S3 key картинки уведомления");
        }
        return s3Client.getObjectAsBytes(GetObjectRequest.builder()
                        .bucket(bucket)
                        .key(storageKey)
                        .build())
                .asByteArray();
    }

    private ProcessedImage processImage(FileUploadGuard.ImageCheck source) {
        if ("webp".equals(source.extension())) {
            return new ProcessedImage(source.bytes(), "webp", "image/webp");
        }
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if ("png".equals(source.extension())) {
                Thumbnails.of(new ByteArrayInputStream(source.bytes()))
                        .size(1600, 1600)
                        .keepAspectRatio(true)
                        .outputFormat("png")
                        .toOutputStream(output);
                return new ProcessedImage(output.toByteArray(), "png", "image/png");
            }
            Thumbnails.of(new ByteArrayInputStream(source.bytes()))
                    .size(1600, 1600)
                    .keepAspectRatio(true)
                    .outputFormat("jpg")
                    .outputQuality(0.9)
                    .toOutputStream(output);
            return new ProcessedImage(output.toByteArray(), "jpg", "image/jpeg");
        } catch (IOException exception) {
            throw new IllegalStateException("Не удалось обработать картинку уведомления", exception);
        }
    }

    private String normalizeEventCode(String eventCode) {
        if (eventCode == null || eventCode.isBlank()) {
            return "unknown";
        }
        return eventCode.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]", "-");
    }

    private String normalizeFolder(String value) {
        if (value == null || value.isBlank()) {
            return "notification-media";
        }
        return value.replace("\\", "/")
                .replaceAll("^/+", "")
                .replaceAll("/+$", "")
                .replaceAll("[^a-zA-Z0-9/_-]", "-");
    }

    private boolean isAllowedStorageKey(String storageKey) {
        String safeRoot = normalizeFolder(rootFolder) + "/";
        return storageKey != null
                && !storageKey.isBlank()
                && storageKey.startsWith(safeRoot);
    }

    private String publicObjectBaseUrl() {
        String value = publicBaseUrl == null || publicBaseUrl.isBlank()
                ? "https://" + projectId + ".selstorage.ru"
                : publicBaseUrl.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    public record StoredNotificationImage(String storageKey, String imageUrl, String contentType) {
    }

    private record ProcessedImage(byte[] bytes, String extension, String contentType) {
    }
}
