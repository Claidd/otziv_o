-- Empty hash-only capability store. R3 performs dual-write and a controlled,
-- resumable live+archive catch-up; this migration intentionally does no backfill.
-- scope_mask supports least-privilege combinations of public actions.
-- order_detail_id has no FK because the resource moves live <-> archive, and it
-- is not unique because capability rotation requires multiple rows per resource.
CREATE TABLE review_check_capabilities (
    review_check_capability_id BIGINT NOT NULL AUTO_INCREMENT,
    order_detail_id BINARY(16) NOT NULL,
    token_hash BINARY(32) NOT NULL,
    token_type VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    scope_mask BIGINT UNSIGNED NOT NULL,
    issued_by_user_id BIGINT NULL,
    expires_at DATETIME(6) NULL,
    revoked_at DATETIME(6) NULL,
    revoked_by_user_id BIGINT NULL,
    revocation_reason VARCHAR(160) NULL,
    last_used_at DATETIME(6) NULL,
    issued_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (review_check_capability_id),
    UNIQUE KEY uk_review_check_capabilities_token_hash (token_hash),
    INDEX idx_review_check_capabilities_resource
        (order_detail_id, revoked_at, expires_at, review_check_capability_id),
    INDEX idx_review_check_capabilities_expiry
        (expires_at, review_check_capability_id),
    INDEX idx_review_check_capabilities_issued_by (issued_by_user_id),
    INDEX idx_review_check_capabilities_revoked_by (revoked_by_user_id),
    CONSTRAINT fk_review_check_capabilities_issued_by
        FOREIGN KEY (issued_by_user_id) REFERENCES users (id)
        ON DELETE SET NULL,
    CONSTRAINT fk_review_check_capabilities_revoked_by
        FOREIGN KEY (revoked_by_user_id) REFERENCES users (id)
        ON DELETE SET NULL,
    CONSTRAINT ck_review_check_capabilities_token_type
        CHECK (token_type IN ('LEGACY_UUID', 'OPAQUE')),
    CONSTRAINT ck_review_check_capabilities_scope_mask
        CHECK (scope_mask > 0),
    CONSTRAINT ck_review_check_capabilities_revocation
        CHECK (
            revoked_at IS NOT NULL
            OR revocation_reason IS NULL
        )
) ENGINE=InnoDB;
