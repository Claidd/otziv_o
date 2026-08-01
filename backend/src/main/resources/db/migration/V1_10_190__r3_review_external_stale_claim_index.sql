-- Complements idx_review_external_checks_due_claim. CHECKING recovery does not
-- filter by check_after, so it needs a lease-first path of its own.
ALTER TABLE review_external_checks
    ADD INDEX idx_review_external_checks_stale_claim
        (status, processing_lease_until, attempt_count, review_external_check_id),
    ALGORITHM=INPLACE,
    LOCK=NONE;
