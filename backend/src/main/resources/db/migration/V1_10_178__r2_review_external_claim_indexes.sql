ALTER TABLE review_external_checks
    ADD UNIQUE INDEX uk_review_external_checks_dedup_hash
        (deduplication_key_hash),
    ADD UNIQUE INDEX uk_review_external_checks_processing_token
        (processing_token),
    ADD INDEX idx_review_external_checks_due_claim
        (status, check_after, processing_lease_until, review_external_check_id),
    ALGORITHM=INPLACE,
    LOCK=NONE;
