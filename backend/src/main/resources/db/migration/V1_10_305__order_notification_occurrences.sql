-- Business occurrence survives fallback status changes and retries; no FK to the
-- locked Order row because reservation commits independently before provider dispatch.
CREATE TABLE order_client_message_occurrences (
    order_id BIGINT NOT NULL,
    logical_kind VARCHAR(180) NOT NULL,
    operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    generation BIGINT NOT NULL,
    business_generation BIGINT NOT NULL,
    confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY(order_id,logical_kind),
    UNIQUE KEY uq_order_message_operation(operation_id)
) ENGINE=InnoDB;

ALTER TABLE orders ADD COLUMN client_message_generation BIGINT NOT NULL DEFAULT 0;

-- Existing route jobs have an unknowable delivery history. Only new enqueue commands
-- prove a post-cutover business occurrence; INSERT IGNORE cannot promote an old row.
ALTER TABLE payment_route_change_notification_outbox
    ADD COLUMN operation_identity_ready BOOLEAN NOT NULL DEFAULT FALSE;
