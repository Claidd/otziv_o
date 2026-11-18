-- Keep historical UNKNOWN/sent/attempted operations unchanged. Only a command refused
-- before dispatch, without any transport envelope, can be retired after full payment.
UPDATE common_invoice_message_queue q
JOIN common_invoices i ON i.invoice_id=q.invoice_id
SET q.state='SUPERSEDED', q.error_code='invoice_paid_before_dispatch', q.updated_at=CURRENT_TIMESTAMP(6)
WHERE q.state='FAILED' AND q.error_code='context_changed'
  AND q.dispatch_started_at IS NULL AND q.claim_token IS NULL AND q.lease_until IS NULL
  AND q.replacement_operation_id IS NULL
  AND i.status='PAID' AND i.amount_kopecks>0 AND i.paid_kopecks>=i.amount_kopecks
  AND NOT EXISTS (SELECT 1 FROM whatsapp_business_send_operations w WHERE w.operation_id=q.operation_id)
  AND NOT EXISTS (SELECT 1 FROM client_message_operations m WHERE m.operation_id=q.operation_id);
