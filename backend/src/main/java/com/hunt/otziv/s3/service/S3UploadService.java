package com.hunt.otziv.s3.service;

import org.springframework.web.multipart.MultipartFile;

public interface S3UploadService {

    /**
     * Uploads a new immutable object. {@code oldUrl} is retained for source
     * compatibility, but is never deleted by this method: the caller must
     * first persist the returned URL and then call
     * {@link #deleteFileAfterCommit(String, String, Long)}.
     */
    String uploadFile(MultipartFile file, String reviews, String url, Long id);

    /**
     * Schedules deletion after the surrounding DB commit, or deletes now when
     * no transaction exists. The folder and owner id are required so a URL
     * copied from another entity cannot delete that entity's object.
     */
    void deleteFileAfterCommit(String url, String folder, Long ownerId);
}
