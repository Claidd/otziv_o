ALTER TABLE companies ADD COLUMN paper_billing_automatic BOOLEAN NOT NULL DEFAULT FALSE,
 ADD COLUMN paper_billing_payer_choice_id CHAR(36) NULL;
ALTER TABLE orders ADD COLUMN paper_billing_payer_choice_id CHAR(36) NULL;
CREATE TABLE paper_invoice_automatic_error (
 target_kind VARCHAR(24) NOT NULL,target_id BIGINT NOT NULL,
 error_text VARCHAR(500) NOT NULL,next_attempt_at DATETIME(6) NOT NULL,
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),PRIMARY KEY(target_kind,target_id)
);
-- Preserve established paper-mode customers without changing ordinary company creation.
UPDATE companies c JOIN company_billing_payer_choice p ON p.company_id=c.company_id AND p.is_default=1 AND p.deleted_at IS NULL
 SET c.paper_billing_automatic=TRUE,c.paper_billing_payer_choice_id=p.id
 WHERE EXISTS(SELECT 1 FROM orders o WHERE o.order_company=c.company_id AND o.invoice_payment_mode='OWNER_PAPER_INVOICE');
UPDATE orders o JOIN companies c ON c.company_id=o.order_company
 SET o.paper_billing_payer_choice_id=c.paper_billing_payer_choice_id
 WHERE o.invoice_payment_mode='OWNER_PAPER_INVOICE';
