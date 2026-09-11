package com.hunt.otziv.s3.service;

import com.hunt.otziv.p_products.api.ReviewPhotoRecords;
import com.hunt.otziv.s3.api.ReviewPhotoUploads;
import com.hunt.otziv.s3.cleanup.service.S3UploadRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ReviewPhotoUploadService implements ReviewPhotoUploads {
    private final ReviewPhotoRecords records;
    private final S3UploadServiceImpl uploads;
    private final S3UploadRegistry registry;
    private final PlatformTransactionManager transactions;

    @Override
    public String replace(long reviewId, Long expectedOrderId, MultipartFile file, Authentication actor) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Photo upload must start outside a business transaction");
        }
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        // The scope guard discovers the parent before waiting for its lock.
        // A snapshot created by that lookup must not hide the preceding upload's commit.
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.executeWithoutResult(status -> records.requireAccess(reviewId, expectedOrderId, actor));
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Файл не выбран");
        }
        S3UploadRegistry.Upload staged = uploads.stageReviewPhoto(file, reviewId);
        try {
            transaction.executeWithoutResult(status -> {
                String previous = records.replace(reviewId, expectedOrderId, staged.url(), actor);
                registry.attach(staged);
                uploads.enqueueReplacedReviewPhoto(previous, reviewId);
            });
        } catch (RuntimeException failure) {
            try { registry.abandon(staged); }
            catch (RuntimeException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            throw failure;
        }
        return staged.url();
    }
}
