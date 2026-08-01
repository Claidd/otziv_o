-- Nullable until the R3 runtime starts claim-and-fence processing. The dedup hash
-- is application-populated; it is not generated from review_id, so current
-- enqueue behavior remains unchanged.
ALTER TABLE review_external_checks
    ADD COLUMN deduplication_key_hash BINARY(32) NULL,
    ADD COLUMN processing_token CHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN processing_owner VARCHAR(128) NULL,
    ADD COLUMN processing_started_at DATETIME(6) NULL,
    ADD COLUMN processing_lease_until DATETIME(6) NULL,
    ALGORITHM=INSTANT;
