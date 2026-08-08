-- A free-form transfer comment may accidentally contain personal data.
-- Widen it for the AES-GCM envelope; the application startup backfill encrypts
-- existing plaintext and old-key values idempotently.
ALTER TABLE contractor_payment_profiles
    MODIFY COLUMN payment_comment VARCHAR(2048) NULL;

ALTER TABLE contractor_payment_allocations
    MODIFY COLUMN payment_comment_snapshot VARCHAR(2048) NULL;
