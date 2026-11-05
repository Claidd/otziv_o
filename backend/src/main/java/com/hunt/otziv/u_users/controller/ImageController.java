package com.hunt.otziv.u_users.controller;


import com.hunt.otziv.u_users.images.SignedImageUrls;
import com.hunt.otziv.u_users.model.Image;
import com.hunt.otziv.u_users.repository.ImageRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.EnumMap;
import java.util.Map;

@RestController
public class ImageController {

    private final ImageRepository imageRepository;
    private final SignedImageUrls signedImageUrls;
    private final Map<SignedImageUrls.AccessKind, Counter> requests = new EnumMap<>(SignedImageUrls.AccessKind.class);

    public ImageController(
            ImageRepository imageRepository,
            SignedImageUrls signedImageUrls,
            ObjectProvider<MeterRegistry> meterRegistry
    ) {
        this.imageRepository = imageRepository;
        this.signedImageUrls = signedImageUrls;
        MeterRegistry registry = meterRegistry.getIfAvailable();
        if (registry != null) {
            for (SignedImageUrls.AccessKind kind : SignedImageUrls.AccessKind.values()) {
                requests.put(kind, Counter.builder("otziv.images.requests")
                        .description("Image downloads by link kind; UNSIGNED shows remaining old clients")
                        .tag("access", kind.name().toLowerCase())
                        .register(registry));
            }
        }
    }

    @GetMapping("/images/{id}")
    public ResponseEntity<byte[]> getImageById(
            @PathVariable Long id,
            @RequestParam(value = "exp", required = false) String expiry,
            @RequestParam(value = "sig", required = false) String signature,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch
    ) {
        SignedImageUrls.Access access = id == null
                ? new SignedImageUrls.Access(SignedImageUrls.AccessKind.DENIED, null)
                : signedImageUrls.authorize(id, expiry, signature);
        Counter counter = requests.get(access.kind());
        if (counter != null) {
            counter.increment();
        }
        if (!access.allowed()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).cacheControl(CacheControl.noStore()).build();
        }

        Image image = imageRepository.findById(id).orElse(null);

        if (image == null || image.getBytes() == null) {
            return ResponseEntity.notFound().build();
        }

        String eTag = buildETag(image);
        // Private: photos of employees must not be kept by shared proxies. Signed links are cached only
        // while their signature is valid.
        CacheControl cacheControl = CacheControl.maxAge(signedImageUrls.browserCacheFor(access)).cachePrivate();

        // Если браузер уже кэшировал картинку и она не изменилась
        if (eTag.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(eTag)
                    .cacheControl(cacheControl)
                    .build();
        }

        MediaType mediaType = resolveMediaType(image.getContentType());

        return ResponseEntity.ok()
                .eTag(eTag)
                .cacheControl(cacheControl)
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
