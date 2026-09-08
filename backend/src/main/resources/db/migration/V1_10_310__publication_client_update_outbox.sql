-- Only new publication transactions create these intents. Historical published
-- reviews and uncertain notification occurrences must not be replayed/backfilled.
CREATE TABLE order_publication_client_updates (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    publication_occurrence VARCHAR(180) NOT NULL,
    operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    delivery_envelope MEDIUMTEXT NULL,
    delivery_state VARCHAR(16) NOT NULL,
    claim_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    attempt_count BIGINT NOT NULL DEFAULT 0,
    last_error_code VARCHAR(96) NULL,
    provider_channel VARCHAR(32) NULL,
    provider_message_id VARCHAR(512) NULL,
    completion_done BOOLEAN NOT NULL DEFAULT FALSE,
    completion_next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    billing_done BOOLEAN NOT NULL DEFAULT FALSE,
    billing_next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_publication_client_event (order_id, publication_occurrence),
    UNIQUE KEY uq_publication_client_operation (operation_id),
    KEY idx_publication_client_order (order_id, id, delivery_state),
    KEY idx_publication_client_delivery (delivery_state, next_attempt_at, id),
    KEY idx_publication_client_completion (completion_done, completion_next_attempt_at, id),
    KEY idx_publication_client_billing (completion_done, billing_done, billing_next_attempt_at, id)
) ENGINE=InnoDB;
