package com.hunt.otziv.s3.service;

import lombok.RequiredArgsConstructor;

import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import net.coobird.thumbnailator.geometry.Positions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.UUID;

@Slf4j // Добавь эту аннотацию наверху класса (если используешь Lombok)
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

    @Override
    public String uploadFile(MultipartFile file, String folder, @Nullable String oldUrl, Long reviewId) {
        String filename;
        if (reviewId != null){
            filename = reviewId + "-" + file.getOriginalFilename();
        }
        else {
            filename = UUID.randomUUID() + "-" + file.getOriginalFilename();
        }
        String key = folder + "/" + filename;

        deleteOldFile(oldUrl);

        byte[] processedImage = processImage(file);

        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .acl("public-read")
                .contentType("image/jpeg")
                .build();

        s3Client.putObject(putRequest, RequestBody.fromBytes(processedImage));

        String url = publicObjectBaseUrl() + "/" + key;
        log.info("Новое фото загружено: {}", url);
        return url;
    }

    private void deleteOldFile(@Nullable String oldUrl) {
        if (oldUrl == null) return;

        String oldKey = extractObjectKey(oldUrl);
        if (oldKey == null) {
            log.warn("Пропущено удаление: старый URL не из нашего хранилища: {}", oldUrl);
            return;
        }

        log.info("Удаление старого файла: {}", oldKey);

        DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(oldKey)
                .build();

        s3Client.deleteObject(deleteRequest);
    }

    private byte[] processImage(MultipartFile file) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Thumbnails.of(file.getInputStream())
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

