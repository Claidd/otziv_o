package com.hunt.otziv.s3.api;

import org.springframework.security.core.Authentication;
import org.springframework.web.multipart.MultipartFile;

/** Authorized review upload, including durable attachment and cleanup. */
public interface ReviewPhotoUploads {
    String replace(long reviewId, Long expectedOrderId, MultipartFile file, Authentication actor);
}
