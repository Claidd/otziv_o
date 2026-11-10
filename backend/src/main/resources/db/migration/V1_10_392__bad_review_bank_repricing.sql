ALTER TABLE payment_links
    ADD COLUMN bad_review_bank_check_at DATETIME(6) NULL,
    ADD INDEX ix_link_bad_review_bank (bad_review_bank_check_at, id);

ALTER TABLE common_invoices
    ADD COLUMN bad_review_bank_ref_id BIGINT NULL,
    ADD COLUMN bad_review_bank_order_id BIGINT NULL,
    ADD COLUMN bad_review_bank_task_id BIGINT NULL,
    ADD COLUMN bad_review_bank_check_at DATETIME(6) NULL,
    ADD INDEX ix_common_bad_review_bank (bad_review_bank_check_at, invoice_id);

-- Recover a previously interrupted cycle only from its actual task, membership and bank attempt.
UPDATE common_invoices i
JOIN common_invoice_orders o ON o.invoice_id=i.invoice_id AND o.active_membership AND NOT o.paid
JOIN bad_review_tasks t ON t.bad_review_task_order=o.order_id AND t.bad_review_task_status='DONE'
JOIN common_invoice_payment_refs r ON r.invoice_id=i.invoice_id AND r.payment_profile_id=i.payment_route_profile_id
    AND r.amount_kopecks=i.payment_route_amount_kopecks AND r.status IN ('CURRENT','EXPIRED','CANCELED','REJECTED','DEADLINE_EXPIRED')
LEFT JOIN common_invoice_payment_refs newer ON newer.invoice_id=i.invoice_id AND newer.payment_ref_id>r.payment_ref_id
SET i.bad_review_bank_ref_id=r.payment_ref_id, i.bad_review_bank_task_id=t.bad_review_task_id, i.bad_review_bank_order_id=o.order_id,
    i.bad_review_bank_check_at=CURRENT_TIMESTAMP(6)
WHERE i.status='NEEDS_ATTENTION' AND newer.payment_ref_id IS NULL AND i.paid_kopecks=0
    AND i.client_reported_at IS NULL AND i.manual_confirmed_at IS NULL AND i.paper_bank_document_id IS NULL
    AND i.last_error LIKE CONCAT('bad_review_supplement_required: source_invoice=',i.invoice_id,
        ';order=',o.order_id,';task=',t.bad_review_task_id,';reason=%');
