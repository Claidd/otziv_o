ALTER TABLE bank_recipient_change
    ADD COLUMN requested_action VARCHAR(16) NOT NULL DEFAULT 'RECIPIENT',
    ADD COLUMN paper_request MEDIUMTEXT NULL;

ALTER TABLE common_invoices
    ADD COLUMN bad_review_payable BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN bad_review_revision BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN bad_review_notice_pending BOOLEAN NOT NULL DEFAULT FALSE,
    ADD INDEX ix_common_bad_review_notice (bad_review_notice_pending, status, invoice_id);

-- Retain the original amounts, memberships, provider references and tokens for audit.
UPDATE common_invoices prior
JOIN common_invoices successor ON successor.supersedes_invoice_id = prior.invoice_id
    AND successor.invoice_purpose = 'BAD_REVIEW_SUCCESSOR' AND successor.account_id = prior.account_id
SET prior.status = 'ARCHIVED', prior.previous_status = 'UNPAID', prior.close_reason = 'BAD_REVIEW_REPLACED',
    prior.closed_at = COALESCE(prior.closed_at, successor.created_at), prior.next_reminder_at = NULL
WHERE prior.status = 'UNPAID' AND prior.paid_kopecks = 0 AND prior.client_reported_at IS NULL
    AND prior.manual_confirmed_at IS NULL AND prior.paper_bank_document_id IS NULL
    AND NOT EXISTS (SELECT 1 FROM common_invoice_orders i WHERE i.invoice_id = prior.invoice_id AND i.active_membership)
    AND NOT EXISTS (SELECT 1 FROM common_invoice_payment_refs r WHERE r.invoice_id = prior.invoice_id
        AND r.status NOT IN ('CANCELED','REJECTED','EXPIRED'));

-- Only strip the mechanically appended suffix on invoices belonging to a recorded chain.
UPDATE common_invoices SET title = REGEXP_REPLACE(title, '( - дополнительный счет)+$', '')
WHERE invoice_purpose = 'BAD_REVIEW_SUCCESSOR' AND supersedes_invoice_id IS NOT NULL
    AND title LIKE '% - дополнительный счет';
