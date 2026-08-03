package com.hunt.otziv.s3.cleanup.service;

import com.hunt.otziv.scheduler.SchedulerLeaseService;
import com.hunt.otziv.scheduler.SchedulerLeaseService.Lease;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

/**
 * Durable retry queue for S3 deletes that happen after a database commit or
 * rollback. S3 deletion is idempotent, so a crash between the remote delete
 * and queue-row deletion is safe and simply causes another delete attempt.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class S3ObjectCleanupQueue {

    private static final String LEASE_NAME = "s3-object-cleanup";
    private static final int MAX_S3_KEY_BYTES = 1024;

    private final NamedParameterJdbcTemplate jdbc;
    private final S3Client s3Client;
    private final SchedulerLeaseService schedulerLeaseService;

    @Value("${s3.cleanup.batch-size:25}")
    private int configuredBatchSize;

    @Value("${s3.cleanup.lease-duration:PT1H}")
    private Duration leaseDuration;

    @Value("${s3.cleanup.delete-timeout:PT30S}")
    private Duration deleteTimeout;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean enqueueBestEffort(String bucket, String objectKey, String reason) {
        if (!validBucket(bucket) || !validObjectKey(objectKey)) {
            log.error("S3 cleanup could not be queued because bucket or object key is invalid");
            return false;
        }
        byte[] identityHash = identityHash(bucket, objectKey);
        try {
            jdbc.update("""
                    INSERT INTO s3_object_cleanup_queue (
                        object_identity_hash, bucket_name, object_key,
                        cleanup_reason, attempts, next_attempt_at,
                        created_at, updated_at
                    ) VALUES (
                        :identityHash, :bucket, :objectKey,
                        :reason, 0, CURRENT_TIMESTAMP(6),
                        CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
                    )
                    ON DUPLICATE KEY UPDATE
                        cleanup_reason = :reason,
                        next_attempt_at = LEAST(next_attempt_at, CURRENT_TIMESTAMP(6)),
                        updated_at = CURRENT_TIMESTAMP(6)
                    """, new MapSqlParameterSource()
                    .addValue("identityHash", identityHash)
                    .addValue("bucket", bucket.trim())
                    .addValue("objectKey", objectKey)
                    .addValue("reason", normalizedReason(reason)));
            log.info("S3 object cleanup queued: objectHash={}", fingerprint(identityHash));
            return true;
        } catch (RuntimeException exception) {
            // Deletion is cleanup after the primary operation. Never turn a
            // successful database commit into an apparent failure here.
            log.error(
                    "S3 cleanup queue write failed: objectHash={}, failureType={}",
                    fingerprint(identityHash),
                    exception.getClass().getSimpleName()
            );
            return false;
        }
    }

    @Scheduled(
            fixedDelayString = "${s3.cleanup.fixed-delay:PT1M}",
            initialDelayString = "${s3.cleanup.initial-delay:PT30S}"
    )
    public void cleanupDueObjects() {
        Optional<Lease> acquired = schedulerLeaseService.tryAcquire(
                LEASE_NAME,
                effectiveLeaseDuration()
        );
        if (acquired.isEmpty()) {
            return;
        }
        try {
            processDueBatch();
        } catch (RuntimeException exception) {
            log.error("S3 cleanup batch failed", exception);
        } finally {
            schedulerLeaseService.release(acquired.get());
        }
    }

    int processDueBatch() {
        List<CleanupItem> items = jdbc.query("""
                SELECT cleanup_id, object_identity_hash, bucket_name,
                       object_key, attempts
                FROM s3_object_cleanup_queue
                WHERE next_attempt_at <= CURRENT_TIMESTAMP(6)
                ORDER BY next_attempt_at, cleanup_id
                LIMIT :batchSize
                """, new MapSqlParameterSource("batchSize", effectiveBatchSize()),
                (rs, rowNum) -> new CleanupItem(
                        rs.getLong("cleanup_id"),
                        rs.getBytes("object_identity_hash"),
                        rs.getString("bucket_name"),
                        rs.getString("object_key"),
                        rs.getInt("attempts")
                ));

        int deleted = 0;
        for (CleanupItem item : items) {
            try {
                s3Client.deleteObject(DeleteObjectRequest.builder()
                        .bucket(item.bucket())
                        .key(item.objectKey())
                        .overrideConfiguration(builder -> builder
                                .apiCallTimeout(effectiveDeleteTimeout())
                                .apiCallAttemptTimeout(effectiveDeleteTimeout()))
                        .build());
                jdbc.update("""
                        DELETE FROM s3_object_cleanup_queue
                        WHERE cleanup_id = :cleanupId
                          AND object_identity_hash = :identityHash
                        """, new MapSqlParameterSource()
                        .addValue("cleanupId", item.id())
                        .addValue("identityHash", item.identityHash()));
                deleted++;
            } catch (RuntimeException exception) {
                scheduleRetry(item, exception);
            }
        }
        if (deleted > 0) {
            log.info("S3 cleanup batch deleted objects: count={}", deleted);
        }
        return deleted;
    }

    private void scheduleRetry(CleanupItem item, RuntimeException failure) {
        int nextAttempt = Math.min(1_000_000, item.attempts() + 1);
        long delayMinutes = Math.min(1440L, 1L << Math.min(nextAttempt, 10));
        String errorCode = failure.getClass().getSimpleName();
        if (errorCode == null || errorCode.isBlank()) {
            errorCode = "RuntimeException";
        }
        jdbc.update("""
                UPDATE s3_object_cleanup_queue
                SET attempts = :attempts,
                    next_attempt_at = TIMESTAMPADD(MINUTE, :delayMinutes, CURRENT_TIMESTAMP(6)),
                    last_error_code = :errorCode,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE cleanup_id = :cleanupId
                  AND object_identity_hash = :identityHash
                """, new MapSqlParameterSource()
                .addValue("attempts", nextAttempt)
                .addValue("delayMinutes", delayMinutes)
                .addValue("errorCode", asciiToken(errorCode, 128))
                .addValue("cleanupId", item.id())
                .addValue("identityHash", item.identityHash()));
        log.warn(
                "S3 cleanup retry scheduled: objectHash={}, attempt={}, failureType={}",
                fingerprint(item.identityHash()),
                nextAttempt,
                asciiToken(errorCode, 128)
        );
    }

    private int effectiveBatchSize() {
        return Math.max(1, Math.min(25, configuredBatchSize));
    }

    private Duration effectiveLeaseDuration() {
        if (leaseDuration == null || leaseDuration.isNegative() || leaseDuration.isZero()) {
            return Duration.ofHours(1);
        }
        return leaseDuration.compareTo(Duration.ofHours(1)) > 0
                ? Duration.ofHours(1)
                : leaseDuration;
    }

    private Duration effectiveDeleteTimeout() {
        if (deleteTimeout == null || deleteTimeout.isNegative() || deleteTimeout.isZero()) {
            return Duration.ofSeconds(30);
        }
        if (deleteTimeout.compareTo(Duration.ofSeconds(1)) < 0) {
            return Duration.ofSeconds(1);
        }
        return deleteTimeout.compareTo(Duration.ofMinutes(2)) > 0
                ? Duration.ofMinutes(2)
                : deleteTimeout;
    }

    private boolean validBucket(String bucket) {
        if (bucket == null) {
            return false;
        }
        String normalized = bucket.trim();
        return !normalized.isEmpty()
                && normalized.length() <= 255
                && StandardCharsets.US_ASCII.newEncoder().canEncode(normalized);
    }

    private boolean validObjectKey(String objectKey) {
        return objectKey != null
                && !objectKey.isBlank()
                && objectKey.getBytes(StandardCharsets.UTF_8).length <= MAX_S3_KEY_BYTES;
    }

    private String normalizedReason(String reason) {
        String normalized = reason == null ? "unspecified" : reason.trim().toLowerCase(Locale.ROOT);
        return asciiToken(normalized, 64);
    }

    private String asciiToken(String value, int maximumLength) {
        String sanitized = value.replaceAll("[^A-Za-z0-9._:-]", "_");
        if (sanitized.isBlank()) {
            sanitized = "unspecified";
        }
        return sanitized.substring(0, Math.min(sanitized.length(), maximumLength));
    }

    private byte[] identityHash(String bucket, String objectKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(bucket.trim().getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) 0);
            return digest.digest(objectKey.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String fingerprint(byte[] identityHash) {
        if (identityHash == null || identityHash.length < 8) {
            return "unavailable";
        }
        return HexFormat.of().formatHex(identityHash, 0, 8);
    }

    record CleanupItem(long id, byte[] identityHash, String bucket, String objectKey, int attempts) {
    }
}
