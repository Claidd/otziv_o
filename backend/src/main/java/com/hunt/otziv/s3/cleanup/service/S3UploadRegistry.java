package com.hunt.otziv.s3.cleanup.service;

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Persist-before-PUT registry. Abandoned keys retain a recurring cleanup tombstone. */
@Service
@RequiredArgsConstructor
public class S3UploadRegistry {
    private final NamedParameterJdbcTemplate jdbc;
    private final S3ObjectCleanupQueue cleanup;
    private final PlatformTransactionManager transactions;

    public Upload reserve(String bucket, String key, String url, long ownerId) {
        Upload upload = new Upload(UUID.randomUUID().toString(), bucket, key, url, ownerId);
        inNewTransaction().executeWithoutResult(status -> jdbc.update("""
                INSERT INTO s3_upload_registry
                    (upload_id, bucket_name, object_key, object_url, owner_id, upload_state, expires_at, next_cleanup_at)
                VALUES (:id, :bucket, :key, :url, :owner, 'UPLOADING',
                    TIMESTAMPADD(MINUTE, 10, CURRENT_TIMESTAMP(6)), TIMESTAMPADD(MINUTE, 10, CURRENT_TIMESTAMP(6)))
                """, parameters(upload)));
        return upload;
    }

    public void uploaded(Upload upload) {
        Boolean ready = inNewTransaction().execute(status -> jdbc.update("""
                UPDATE s3_upload_registry SET upload_state = 'READY'
                WHERE upload_id = :id AND upload_state = 'UPLOADING' AND expires_at > CURRENT_TIMESTAMP(6)
                """, parameters(upload)) == 1);
        if (!Boolean.TRUE.equals(ready)) {
            abandon(upload);
            throw new IllegalStateException("Photo upload expired before attachment");
        }
    }

    /** Joins the business transaction: any failure must roll back the URL replacement. */
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void attach(Upload upload) {
        int changed = jdbc.update("""
                UPDATE s3_upload_registry SET upload_state = 'ATTACHED'
                WHERE upload_id = :id AND bucket_name = :bucket AND object_key = :key
                  AND owner_id = :owner AND upload_state = 'READY' AND expires_at > CURRENT_TIMESTAMP(6)
                """, parameters(upload));
        if (changed != 1) throw new IllegalStateException("Photo upload is no longer attachable");
    }

    public void abandon(Upload upload) {
        inNewTransaction().executeWithoutResult(status -> {
            int changed = jdbc.update("""
                    UPDATE s3_upload_registry SET upload_state = 'ABANDONED', next_cleanup_at = CURRENT_TIMESTAMP(6)
                    WHERE upload_id = :id AND upload_state <> 'ATTACHED'
                    """, parameters(upload));
            if (changed > 0) cleanup.enqueueRequired(upload.bucket(), upload.key(), "unattached-review-photo");
        });
    }

    @Scheduled(fixedDelayString = "${s3.upload-recovery.fixed-delay:PT1M}",
            initialDelayString = "${s3.upload-recovery.initial-delay:PT1M}")
    public void recoverUnattachedUploads() {
        List<String> due = jdbc.queryForList("""
                SELECT upload_id FROM s3_upload_registry
                WHERE upload_state IN ('UPLOADING', 'READY', 'ABANDONED') AND next_cleanup_at <= CURRENT_TIMESTAMP(6)
                ORDER BY next_cleanup_at, upload_id LIMIT 25
                """, new MapSqlParameterSource(), String.class);
        for (String id : due) recover(id);
    }

    private void recover(String id) {
        inNewTransaction().executeWithoutResult(status -> {
            List<Upload> locked = jdbc.query("""
                    SELECT upload_id, bucket_name, object_key, object_url, owner_id FROM s3_upload_registry
                    WHERE upload_id = :id AND upload_state IN ('UPLOADING', 'READY', 'ABANDONED')
                      AND next_cleanup_at <= CURRENT_TIMESTAMP(6) FOR UPDATE
                    """, new MapSqlParameterSource("id", id), (rs, row) -> new Upload(
                    rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5)));
            if (locked.isEmpty()) return;
            Upload upload = locked.getFirst();
            jdbc.update("""
                    UPDATE s3_upload_registry SET upload_state = 'ABANDONED',
                        next_cleanup_at = TIMESTAMPADD(DAY, 1, CURRENT_TIMESTAMP(6)) WHERE upload_id = :id
                    """, parameters(upload));
            cleanup.enqueueRequired(upload.bucket(), upload.key(), "abandoned-upload-recovery");
            // Retain the tombstone. A timed-out PUT may complete remotely after
            // an earlier DELETE (even if the uploading process has crashed).
        });
    }

    private TransactionTemplate inNewTransaction() {
        TransactionTemplate template = new TransactionTemplate(transactions);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    private MapSqlParameterSource parameters(Upload upload) {
        return new MapSqlParameterSource("id", upload.id()).addValue("bucket", upload.bucket())
                .addValue("key", upload.key()).addValue("url", upload.url()).addValue("owner", upload.ownerId());
    }

    public record Upload(String id, String bucket, String key, String url, long ownerId) {}
}
