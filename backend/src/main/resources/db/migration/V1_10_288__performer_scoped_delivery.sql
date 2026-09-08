-- Additive cutover: drain old performer schedulers/mutation instances before enabling
-- the new dispatcher. Existing evidence is preserved; missing evidence is UNKNOWN.
ALTER TABLE review_performer_assignments
    ADD COLUMN publication_generation BIGINT NOT NULL DEFAULT 0;

ALTER TABLE review_performer_offers
    ADD COLUMN delivery_state VARCHAR(32) NOT NULL DEFAULT 'LEGACY_UNKNOWN',
    ADD COLUMN delivered_at DATETIME(6) NULL,
    ADD COLUMN response_ttl_minutes INT NOT NULL DEFAULT 10;

UPDATE review_performer_offers
SET delivery_state = 'LEGACY_CONFIRMED'
WHERE telegram_message_id IS NOT NULL;

CREATE TABLE performer_notification_intents (
    notification_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    operation_key VARCHAR(160) NOT NULL,
    assignment_id BIGINT NOT NULL,
    offer_id BIGINT NULL,
    notification_type VARCHAR(32) NOT NULL,
    generation BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    due_at DATETIME(6) NOT NULL,
    processing_token VARCHAR(36) NULL,
    lease_until DATETIME(6) NULL,
    attempts INT NOT NULL DEFAULT 0,
    telegram_message_id INT NULL,
    outcome_code VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_performer_notification_operation (operation_key),
    INDEX idx_performer_notification_due (status, due_at, notification_id),
    INDEX idx_performer_notification_lease (status, lease_until, notification_id),
    INDEX idx_performer_notification_assignment (assignment_id)
);

CREATE TABLE performer_notification_resolutions (
    resolution_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    notification_id BIGINT NOT NULL,
    action VARCHAR(32) NOT NULL,
    actor VARCHAR(255) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    resolved_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_performer_resolution_notification (notification_id, resolution_id)
);

-- Do not call historical readiness notifications SENT or resend the whole backlog.
INSERT INTO performer_notification_intents
    (operation_key, assignment_id, notification_type, generation, status, due_at, outcome_code)
SELECT CONCAT('READY:', assignment_id, ':0'), assignment_id, 'READY', 0,
       'LEGACY_UNKNOWN', COALESCE(publish_available_at, CURRENT_TIMESTAMP(6)), 'legacy_delivery_unverified'
FROM review_performer_assignments WHERE status = 'WAITING_PUBLICATION';

INSERT INTO performer_notification_intents
    (operation_key, assignment_id, offer_id, notification_type, status, due_at, telegram_message_id, outcome_code)
SELECT CONCAT('OFFER:', offer_id, ':0'), assignment_id, offer_id, 'OFFER',
       CASE WHEN telegram_message_id IS NULL THEN 'LEGACY_UNKNOWN' ELSE 'SENT' END,
       offered_at, telegram_message_id, 'legacy_delivery_evidence'
FROM review_performer_offers WHERE status = 'OFFERED';
