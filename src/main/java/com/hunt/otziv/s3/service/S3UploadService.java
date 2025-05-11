package com.hunt.otziv.s3.service;

import org.springframework.web.multipart.MultipartFile;

public interface S3UploadService {

    String uploadFile(MultipartFile file, String reviews, String url, Long id);
}
