-- Generic local barrier for keyed Telegram/MAX dispatch. No message or destination plaintext.
CREATE TABLE client_message_operations (
    operation_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    platform VARCHAR(16) NOT NULL,
    destination_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    envelope_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(24) NOT NULL,
    claim_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempts INT NOT NULL DEFAULT 1,
    result_channel VARCHAR(32) NULL,
    provider_message_id VARCHAR(512) NULL,
    error_code VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT chk_client_message_platform CHECK (platform IN ('TELEGRAM','MAX')),
    CONSTRAINT chk_client_message_state CHECK (state IN ('PREPARED','SUCCEEDED','FAILED_KNOWN','UNKNOWN'))
) ENGINE=InnoDB;

CREATE TABLE client_message_operation_resolutions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    operation_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    claim_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    envelope_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor VARCHAR(128) NOT NULL,
    provider_message_id VARCHAR(512) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_client_message_resolution_operation FOREIGN KEY(operation_id) REFERENCES client_message_operations(operation_id),
    UNIQUE KEY uq_client_message_resolution_claim(operation_id,claim_token)
) ENGINE=InnoDB;
