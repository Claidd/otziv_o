ALTER TABLE paper_invoice_document
    ADD COLUMN cancel_requested_at DATETIME(6) NULL,
    ADD COLUMN cancelled_at DATETIME(6) NULL,
    ADD COLUMN cancel_requested_by VARCHAR(150) NULL,
    ADD COLUMN cancel_reason VARCHAR(500) NULL,
    ADD COLUMN cancel_manager_ids_json TEXT NULL,
    ADD COLUMN cancel_step VARCHAR(32) NULL,
    ADD COLUMN cancel_token CHAR(36) NULL,
    ADD COLUMN cancel_attempt_at DATETIME(6) NULL,
    ADD COLUMN cancel_next_attempt_at DATETIME(6) NULL,
    ADD COLUMN invoice_deleted_at DATETIME(6) NULL,
    ADD COLUMN act_deleted_at DATETIME(6) NULL,
    ADD COLUMN cancel_statement_id VARCHAR(96) NULL,
    ADD COLUMN cancel_scan_date DATE NULL,
    ADD COLUMN cancel_window_end DATE NULL,
    ADD COLUMN cancel_message_delivery VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN cancel_message_id VARCHAR(512) NULL,
    ADD COLUMN active_target_id BIGINT GENERATED ALWAYS AS (IF(cancelled_at IS NULL,target_id,NULL)) STORED,
    DROP INDEX uk_paper_document_target,
    ADD UNIQUE KEY uk_paper_document_active_target(target_kind,active_target_id),
    ADD KEY idx_paper_cancel_due(cancel_step,cancel_next_attempt_at);

ALTER TABLE orders ADD COLUMN paper_invoice_automation_hold BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN paper_invoice_review_hold BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE common_invoices ADD COLUMN paper_invoice_automation_hold BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN paper_invoice_review_hold BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE paper_invoice_event (
    document_id CHAR(36) NOT NULL,
    event_key VARCHAR(32) NOT NULL,
    body TEXT NOT NULL,
    prepared_at DATETIME(6) NULL,
    next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY(document_id,event_key)
);
ALTER TABLE paper_invoice_staff_notice
    ADD COLUMN event_key VARCHAR(32) NOT NULL DEFAULT 'PAID',
    DROP INDEX uk_paper_staff_user,
    DROP INDEX uk_paper_staff_chat,
    ADD UNIQUE KEY uk_paper_staff_user(document_id,event_key,recipient_user_id),
    ADD UNIQUE KEY uk_paper_staff_chat(document_id,event_key,chat_id);
