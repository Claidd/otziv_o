-- Keep earned rewards active when their paid order moves to cold storage.
-- Both routes enforce the original paid-status guard; no financial row is deleted.
ALTER TABLE archive_orders
    ADD UNIQUE KEY uk_archive_order_paid_status (order_id, order_status);

ALTER TABLE contractor_reward_ledger
    DROP FOREIGN KEY fk_ledger_active_order_paid_status,
    ADD COLUMN archived_order_id BIGINT NULL AFTER order_id,
    MODIFY COLUMN active_order_guard BIGINT
        GENERATED ALWAYS AS (
            CASE WHEN active = 1 AND order_id > 0 AND archived_order_id IS NULL
                THEN order_id ELSE NULL END
        ) STORED,
    ADD COLUMN active_archive_order_guard BIGINT
        GENERATED ALWAYS AS (
            CASE WHEN active = 1 THEN archived_order_id ELSE NULL END
        ) STORED,
    ADD CONSTRAINT ck_ledger_archive_order_identity
        CHECK (archived_order_id IS NULL OR (order_id IS NOT NULL AND archived_order_id = order_id)),
    ADD CONSTRAINT ck_ledger_active_archive_paid_guard
        CHECK (active_archive_order_guard IS NULL OR payment_status_guard IS NOT NULL),
    ADD CONSTRAINT fk_ledger_archive_order
        FOREIGN KEY (archived_order_id) REFERENCES archive_orders (order_id),
    ADD CONSTRAINT fk_ledger_live_order_paid_status
        FOREIGN KEY (active_order_guard, payment_status_guard)
        REFERENCES orders (order_id, order_status),
    ADD CONSTRAINT fk_ledger_active_archive_paid_status
        FOREIGN KEY (active_archive_order_guard, payment_status_guard)
        REFERENCES archive_orders (order_id, order_status);
