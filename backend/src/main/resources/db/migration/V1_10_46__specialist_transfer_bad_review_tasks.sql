ALTER TABLE specialist_transfer_audit
    ADD COLUMN bad_review_task_count INT NOT NULL DEFAULT 0 AFTER review_count;
