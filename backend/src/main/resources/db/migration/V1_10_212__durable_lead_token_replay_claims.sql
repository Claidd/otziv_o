CREATE TABLE lead_integration_token_claims (
    token_hash BINARY(32) NOT NULL,
    expires_at_epoch_seconds BIGINT UNSIGNED NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (token_hash),
    KEY idx_lead_integration_token_claims_expiry (expires_at_epoch_seconds)
) ENGINE=InnoDB;
