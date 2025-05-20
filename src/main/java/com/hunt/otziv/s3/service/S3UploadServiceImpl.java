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

        String url = String.format("https://%s.selstorage.ru/%s", projectId, key);
        log.info("Новое фото загружено: {}", url);
        return url;
    }

    private void deleteOldFile(@Nullable String oldUrl) {
        if (oldUrl == null) return;

        String expectedPrefix = "https://" + projectId + ".selstorage.ru/";
        if (oldUrl.startsWith(expectedPrefix)) {
            String oldKey = oldUrl.substring(expectedPrefix.length());
            log.info("Удаление старого файла: {}", oldKey);

            DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(oldKey)
                    .build();

            s3Client.deleteObject(deleteRequest);
        } else {
            log.warn("Пропущено удаление: старый URL не из нашего хранилища: {}", oldUrl);
        }
    }

    private byte[] processImage(MultipartFile file) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Thumbnails.of(file.getInputStream())
//                    .size(600, 400)
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

}

