-- Keep unresolved commands in a searchable FIFO scope; completed history cannot block.
-- Stored generation/index construction can rebuild a large table. Drain consumers and
-- budget migration time before deploying code that reads blocking_lead_id.
ALTER TABLE lead_command_queue
    ADD COLUMN blocking_lead_id BIGINT GENERATED ALWAYS AS (
        CASE WHEN delivery_state IN ('READY','PROCESSING','UNKNOWN','LEGACY','QUARANTINED','DEAD')
             THEN lead_id ELSE NULL END
    ) STORED,
    ADD KEY ix_lead_command_blocking_order (blocking_lead_id, id),
    ADD KEY ix_lead_command_health (delivery_state, created_at);
