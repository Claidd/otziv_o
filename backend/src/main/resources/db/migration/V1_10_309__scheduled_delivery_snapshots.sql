ALTER TABLE scheduled_client_message_state
    ADD COLUMN delivery_envelope MEDIUMTEXT NULL AFTER delivery_message,
    ADD COLUMN delivery_channel VARCHAR(32) NULL AFTER delivery_envelope,
    ADD COLUMN delivery_recovery_checked_at DATETIME(6) NULL AFTER delivery_prepared_at;

-- A shared short-lived lock serializes daily/gap reservations across backend instances.
CREATE TABLE scheduled_client_message_dispatch_guard (
    guard_id INT NOT NULL PRIMARY KEY
) ENGINE=InnoDB;
INSERT INTO scheduled_client_message_dispatch_guard(guard_id) VALUES (1);

-- Existing uncertain/legacy claims are intentionally not rearmed. Only claims
-- stamped CLAIMED by the new writer can be proven to precede all external I/O.
