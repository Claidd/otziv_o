-- The V82 index places external status before the publication timestamp. Since
-- the candidate query accepts every status except CONFIRMED (including legacy
-- NULLs), that order cannot also serve its oldest-first LIMIT and MySQL falls
-- back to a broad published-review scan plus filesort. Keep the old index for
-- rollback compatibility and add a scan path aligned with the query ordering.
ALTER TABLE reviews
    ADD INDEX idx_reviews_external_auto_candidates
        (review_publish, review_published_marked_at, review_id, review_external_confirm_status),
    ALGORITHM=INPLACE,
    LOCK=NONE;
