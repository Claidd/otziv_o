-- R4 preparation only: current registration/sender behavior remains unchanged.
-- The table is empty in the current production fixture, so one checks-on COPY
-- DDL is safer than temporarily disabling FK validation.
ALTER TABLE mobile_push_tokens
    ADD COLUMN auth_epoch BIGINT UNSIGNED NOT NULL DEFAULT 0,
    ADD COLUMN revoked_at DATETIME(6) NULL,
    ADD COLUMN revoked_reason VARCHAR(160) NULL,
    ADD COLUMN revoked_by_user_id BIGINT NULL,
    ADD INDEX idx_mobile_push_tokens_revoked_by_user (revoked_by_user_id),
    ADD CONSTRAINT fk_mobile_push_tokens_revoked_by_user
        FOREIGN KEY (revoked_by_user_id) REFERENCES users (id)
        ON DELETE SET NULL,
    ADD CONSTRAINT ck_mobile_push_tokens_active_revocation
        CHECK (
            active = 0
            OR (
                revoked_at IS NULL
                AND revoked_reason IS NULL
            )
        ),
    ADD CONSTRAINT ck_mobile_push_tokens_revocation_timestamp
        CHECK (
            revoked_at IS NOT NULL
            OR revoked_reason IS NULL
        ),
    ALGORITHM=COPY,
    LOCK=SHARED;
