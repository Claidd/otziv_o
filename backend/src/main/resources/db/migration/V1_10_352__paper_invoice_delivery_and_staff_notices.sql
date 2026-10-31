ALTER TABLE paper_invoice_document
    ADD COLUMN delivery_completed_at DATETIME(6) NULL,
    ADD COLUMN staff_notices_prepared_at DATETIME(6) NULL,
    ADD COLUMN staff_notice_next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6);

-- Do not notify staff retroactively about historical paid documents.
UPDATE paper_invoice_document SET staff_notices_prepared_at=NOW(6) WHERE paid_at IS NOT NULL;
UPDATE paper_invoice_document SET delivery_completed_at=updated_at
WHERE paid_at IS NOT NULL AND message_delivery='SENT' AND invoice_delivery='SENT' AND act_delivery='SENT';

CREATE TABLE paper_invoice_staff_notice (
    notice_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    document_id CHAR(36) NOT NULL,
    recipient_user_id BIGINT NOT NULL,
    manager_id BIGINT NULL,
    chat_id BIGINT NULL,
    body TEXT NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempt_token CHAR(36) NULL,
    message_id VARCHAR(96) NULL,
    error_text VARCHAR(500) NULL,
    next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_paper_staff_user (document_id,recipient_user_id),
    UNIQUE KEY uk_paper_staff_chat (document_id,chat_id),
    KEY idx_paper_staff_due (state,next_attempt_at)
);
