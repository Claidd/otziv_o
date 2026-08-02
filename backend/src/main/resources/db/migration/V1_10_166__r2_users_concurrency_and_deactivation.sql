-- R2 is intentionally additive and behavior-neutral. users is small enough for
-- one checks-on COPY DDL, which keeps the columns, index, FK, and CHECKs atomic.
-- MySQL does not permit a CHECK to reference an FK column used by ON DELETE SET
-- NULL, so actor integrity is enforced by the FK and the remaining metadata by
-- the CHECK constraints.
ALTER TABLE users
    ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN auth_epoch BIGINT UNSIGNED NOT NULL DEFAULT 0,
    ADD COLUMN deactivated_at DATETIME(6) NULL,
    ADD COLUMN deactivated_by_user_id BIGINT NULL,
    ADD COLUMN deactivation_reason VARCHAR(500) NULL,
    ADD INDEX idx_users_deactivated_by_user (deactivated_by_user_id),
    ADD CONSTRAINT fk_users_deactivated_by_user
        FOREIGN KEY (deactivated_by_user_id) REFERENCES users (id)
        ON DELETE SET NULL,
    ADD CONSTRAINT ck_users_deactivation_metadata
        CHECK (
            active IS NULL
            OR active = 0
            OR (
                deactivated_at IS NULL
                AND deactivation_reason IS NULL
            )
        ),
    ADD CONSTRAINT ck_users_deactivation_timestamp
        CHECK (
            deactivated_at IS NOT NULL
            OR deactivation_reason IS NULL
        ),
    ALGORITHM=COPY,
    LOCK=SHARED;
