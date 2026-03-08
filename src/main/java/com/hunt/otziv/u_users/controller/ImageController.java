package com.hunt.otziv.u_users.controller;


import com.hunt.otziv.u_users.model.Image;
import com.hunt.otziv.u_users.repository.ImageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayInputStream;
import java.util.concurrent.TimeUnit;

@RestController
@RequiredArgsConstructor
public class ImageController {

    private static final long CACHE_MAX_AGE_SECONDS = 86400; // 1 день

    private final ImageRepository imageRepository;

    @GetMapping("/images/{id}")
    public ResponseEntity<byte[]> getImageById(
            @PathVariable Long id,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch
    ) {
        Image image = imageRepository.findById(id).orElse(null);

        if (image == null || image.getBytes() == null) {
            return ResponseEntity.notFound().build();
        }

        String eTag = buildETag(image);

        // Если браузер уже кэшировал картинку и она не изменилась
        if (eTag.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(eTag)
                    .cacheControl(CacheControl.maxAge(CACHE_MAX_AGE_SECONDS, TimeUnit.SECONDS).cachePublic())
                    .build();
        }

        MediaType mediaType = resolveMediaType(image.getContentType());

        return ResponseEntity.ok()
                .eTag(eTag)
                .cacheControl(CacheControl.maxAge(CACHE_MAX_AGE_SECONDS, TimeUnit.SECONDS).cachePublic())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + safeFileName(image.getOriginalFileName()) + "\"")
                .contentType(mediaType)
                .contentLength(image.getSize() != null ? image.getSize() : image.getBytes().length)
                .body(image.getBytes());
    }

    private String buildETag(Image image) {
        Long id = image.getId() != null ? image.getId() : 0L;
        Long size = image.getSize() != null ? image.getSize() : 0L;
        return "\"" + id + "-" + size + "\"";
    }

    private MediaType resolveMediaType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return MediaType.IMAGE_JPEG;
        }

        try {
            return MediaType.parseMediaType(contentType);
        } catch (Exception e) {
            return MediaType.IMAGE_JPEG;
        }
    }

    private String safeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "image";
        }
        return fileName.replace("\"", "");
    }
}
