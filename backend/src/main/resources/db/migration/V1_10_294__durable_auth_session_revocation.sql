-- Expand only. Drain old authentication writers before enabling session-revocation-mode=enforce.
-- Existing JWTs do not receive a fabricated epoch; first binding needs live issuer evidence.
CREATE TABLE auth_session_bindings (
    keycloak_subject VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    session_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BIGINT NOT NULL,
    auth_epoch BIGINT NOT NULL,
    credential_created_ms BIGINT NOT NULL,
    session_started_ms BIGINT NOT NULL,
    offline_session BOOLEAN NOT NULL,
    bound_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (keycloak_subject, session_id),
    INDEX ix_auth_session_user_epoch (user_id, auth_epoch)
);
-- Bindings are immutable tombstones. No TTL cleanup until the accepted offline-session horizon is proven.
CREATE TABLE auth_security_mutations (
    operation_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    keycloak_subject VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    auth_epoch BIGINT NOT NULL,
    reason VARCHAR(64) NOT NULL,
    phase VARCHAR(32) NOT NULL,
    resolution_actor VARCHAR(255) NULL,
    resolution_reason VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_auth_mutation_generation (user_id, auth_epoch),
    INDEX ix_auth_mutation_pending (phase, updated_at, operation_id),
    INDEX ix_auth_mutation_user (user_id, phase)
);
CREATE TABLE auth_revocation_sessions (
    operation_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    session_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    offline_session BOOLEAN NOT NULL,
    PRIMARY KEY (operation_id, session_id, offline_session)
);
