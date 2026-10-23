-- Deliberately NOT a Flyway migration. Only restore_invoice_175.py renders/runs it.
-- No client sends, route cancellation, order transitions or payment changes.
SET NAMES utf8mb4;
SET SESSION innodb_lock_wait_timeout = 10;
SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ;
CREATE TEMPORARY TABLE repair_175_guards (
    guard_name VARCHAR(80) NOT NULL,
    passed TINYINT NOT NULL,
    CONSTRAINT repair_175_guard_failed CHECK (passed = 1)
) ENGINE=InnoDB;
START TRANSACTION;

-- Canonical order: Orders -> PaymentLinks -> OWNER allocation (no profile) -> account -> invoice.
SELECT order_id FROM orders WHERE order_id IN (25645,25646,25647) ORDER BY order_id FOR UPDATE;
SELECT id FROM payment_links WHERE order_id IN (25645,25646,25647) ORDER BY order_id,id FOR UPDATE;
SELECT id FROM contractor_payment_allocations WHERE id=1145 FOR UPDATE;
SELECT account_id FROM common_billing_accounts WHERE account_id=80 FOR UPDATE;
SELECT invoice_id FROM common_invoices WHERE invoice_id=175 FOR UPDATE;
SELECT invoice_order_id FROM common_invoice_orders
 WHERE invoice_id=175 OR (order_id IN (25645,25646,25647) AND active_membership=1)
 ORDER BY invoice_order_id FOR UPDATE;
SELECT invoice_id FROM common_invoices WHERE supersedes_invoice_id=175 FOR UPDATE;
SELECT invoice_id FROM common_invoice_payment_refs WHERE invoice_id=175 FOR UPDATE;
SELECT operation_id FROM common_invoice_message_queue WHERE invoice_id=175 FOR UPDATE;
SELECT outbox_id FROM payment_link_return_reconciliation_outbox WHERE payment_link_id=7406 FOR UPDATE;
SELECT delivery_id FROM common_invoice_payment_notification_outbox WHERE invoice_id=175 FOR UPDATE;
SELECT state_id FROM scheduled_client_message_state
 WHERE order_id IN (25645,25646,25647) ORDER BY state_id FOR UPDATE;
-- Nested card lookup is a read only selector; never acquire card locks after Order locks.
SELECT operation_id FROM manager_client_message_queue WHERE card_id IN (
 SELECT control_entity_id FROM manager_daily_control_concrete_items
 WHERE (entity_type IN ('ORDER','WORKER_ORDER_NEW','WORKER_ORDER_CORRECT') AND entity_id IN (25645,25646,25647))
    OR (entity_type IN ('COMMON_INVOICE','COMMON_INVOICE_AUTOMATION') AND entity_id=175)
) ORDER BY operation_id FOR UPDATE;
SELECT event_id FROM business_audit_events
 WHERE action='common_invoice_archive_restored' AND entity_type='common_invoice' AND entity_id='175'
 ORDER BY event_id FOR UPDATE;

-- guard: exact original group and terminal children
INSERT INTO repair_175_guards SELECT 'children', COUNT(*)=3 AND SUM(
 (o.order_id IN (25645,25646) AND s.order_status_title='Архив')
 OR (o.order_id=25647 AND s.order_status_title='Оплачено'))=3
 FROM orders o JOIN order_statuses s ON s.order_status_id=o.order_status
 WHERE o.order_id IN (25645,25646,25647);
INSERT INTO repair_175_guards SELECT 'disabled_account',COUNT(*)=1
 FROM common_billing_accounts WHERE account_id=80 AND enabled=0;
-- guard: exact manual archive, money and no active common payment route
INSERT INTO repair_175_guards SELECT 'invoice',COUNT(*)=1 FROM common_invoices
 WHERE invoice_id=175 AND account_id=80 AND status IN ('PARTIALLY_PAID','ARCHIVED')
 AND amount_kopecks=450000 AND paid_kopecks=150000
 AND previous_status='COLLECTING' AND close_reason='MANUAL_ARCHIVE' AND closed_by='vika'
 AND closed_at='2026-07-30 21:31:26.321616'
 AND supersedes_invoice_id IS NULL AND sent_at IS NULL AND paper_invoice_issued_at IS NULL
 AND tbank_order_id IS NULL AND tbank_payment_id IS NULL AND tbank_terminal_key IS NULL
 AND tbank_payment_amount_kopecks IS NULL AND payment_url IS NULL
 AND payment_message_operation_id IS NULL AND payment_message_operation_kind IS NULL
 AND payment_message_confirmed=0 AND payment_route_type IS NULL
 AND payment_route_profile_id IS NULL AND contractor_allocation_id IS NULL
 AND payment_route_manual_task_id IS NULL AND payment_route_selected_at IS NULL
 AND (last_error IS NULL OR last_error='')
 AND (status<>'ARCHIVED' OR next_reminder_at IS NULL);
-- guard: exact original audit proves this is restoration of an existing archive
INSERT INTO repair_175_guards SELECT 'original_archive_audit',COUNT(*)=3
 FROM business_audit_events WHERE source='common_billing' AND action='order_status_changed' AND entity_type='order'
 AND CAST(entity_id AS BINARY)=CAST(CAST(order_id AS CHAR) AS BINARY)
 AND old_value='На проверке' AND new_value='Архив'
 AND created_at BETWEEN '2026-07-30 21:31:20' AND '2026-07-30 21:31:30'
 AND ((event_id=41615 AND order_id=25645) OR (event_id=41616 AND order_id=25646)
      OR (event_id=41617 AND order_id=25647));
-- guard: membership and preserved standalone provenance
INSERT INTO repair_175_guards SELECT 'membership_count',COUNT(*)=3
 FROM common_invoice_orders WHERE invoice_id=175;
INSERT INTO repair_175_guards SELECT 'active_membership_count',COUNT(*)=3
 FROM common_invoice_orders WHERE order_id IN (25645,25646,25647) AND active_membership=1;
INSERT INTO repair_175_guards SELECT 'membership_snapshot',COUNT(*)=3
 FROM common_invoice_orders WHERE invoice_id=175 AND active_membership=1 AND amount_kopecks=150000
 AND unpaid=0 AND archive_source_order_status_title='На проверке'
 AND ((invoice_order_id=594 AND order_id=25645 AND paid=0 AND ready=0 AND paid_at IS NULL
       AND source_payment_link_id IS NULL AND payment_method IS NULL)
   OR (invoice_order_id=595 AND order_id=25646 AND paid=0 AND ready=0 AND paid_at IS NULL
       AND source_payment_link_id IS NULL AND payment_method IS NULL)
   OR (invoice_order_id=596 AND order_id=25647 AND paid=1 AND ready=1
       AND source_payment_link_id=7406 AND payment_method='TBANK'
       AND paid_at='2026-09-08 17:41:11.858199'));
INSERT INTO repair_175_guards SELECT 'only_source_payment',COUNT(*)=1
 FROM payment_links WHERE order_id IN (25645,25646,25647);
INSERT INTO repair_175_guards SELECT 'confirmed_source',COUNT(*)=1 FROM payment_links
 WHERE id=7406 AND order_id=25647 AND status='CONFIRMED' AND payment_method='SBP_QR'
 AND amount_kopecks=150000 AND confirmed_amount_kopecks=150000 AND reserved_amount_kopecks=150000
 AND contractor_allocation_id=1145 AND paid_at='2026-09-08 17:41:11.858199'
 AND COALESCE(provider_terminal_status,'CONFIRMED')='CONFIRMED'
 AND bank_init_nonce IS NULL AND bank_init_lease_until IS NULL
 AND bank_cancel_nonce IS NULL AND bank_cancel_lease_until IS NULL AND bank_cancel_origin_status IS NULL
 AND return_recovery_processed_at IS NULL
 AND (last_error IS NULL OR last_error='');
-- guard: exact live OWNER allocation, with no return or competing reconciliation
INSERT INTO repair_175_guards SELECT 'confirmed_allocation',COUNT(*)=1 FROM contractor_payment_allocations
 WHERE id=1145 AND mode='LIVE' AND source_type='PAYMENT_LINK' AND source_id=7406 AND order_id=25647
 AND attempt_no=1 AND common_invoice_id IS NULL AND manual_payment_task_id IS NULL
 AND recipient_type='OWNER' AND recipient_profile_id IS NULL AND status='CONFIRMED'
 AND amount_kopecks=150000 AND confirmed_kopecks=150000 AND returned_kopecks=0 AND needs_return_amount=0
 AND reconcile_claim_token IS NULL AND reconcile_lease_until IS NULL AND reconcile_attempts=0;
-- guard: no successor, current provider reference, outbox or delivery ambiguity
INSERT INTO repair_175_guards SELECT 'no_successor',COUNT(*)=0 FROM common_invoices WHERE supersedes_invoice_id=175;
INSERT INTO repair_175_guards SELECT 'no_common_refs',COUNT(*)=0 FROM common_invoice_payment_refs WHERE invoice_id=175;
INSERT INTO repair_175_guards SELECT 'no_common_message',COUNT(*)=0 FROM common_invoice_message_queue WHERE invoice_id=175;
INSERT INTO repair_175_guards SELECT 'no_return',COUNT(*)=0 FROM payment_link_return_reconciliation_outbox WHERE payment_link_id=7406;
INSERT INTO repair_175_guards SELECT 'no_common_notification',COUNT(*)=0 FROM common_invoice_payment_notification_outbox WHERE invoice_id=175;
INSERT INTO repair_175_guards SELECT 'no_scheduled_uncertainty',COUNT(*)=0 FROM scheduled_client_message_state
 WHERE order_id IN (25645,25646,25647)
 AND (delivery_status IN ('CLAIMED','PREPARED','SENDING','UNKNOWN','RETRYABLE')
      OR locked_until IS NOT NULL OR delivery_token IS NOT NULL OR delivery_envelope IS NOT NULL);
INSERT INTO repair_175_guards SELECT 'no_manual_uncertainty',COUNT(*)=0 FROM manager_client_message_queue q
 WHERE (state<>'SENT' OR error_code='finalization_required' OR claim_token IS NOT NULL OR lease_until IS NOT NULL)
 AND card_id IN (SELECT control_entity_id FROM manager_daily_control_concrete_items
 WHERE (entity_type IN ('ORDER','WORKER_ORDER_NEW','WORKER_ORDER_CORRECT') AND entity_id IN (25645,25646,25647))
    OR (entity_type IN ('COMMON_INVOICE','COMMON_INVOICE_AUTOMATION') AND entity_id=175));
INSERT INTO repair_175_guards SELECT 'no_active_recovery',COUNT(*)=0 FROM review_recovery_tasks
 WHERE review_recovery_task_order IN (25645,25646,25647) AND review_recovery_task_status='PLANNED';

SET @repair_needed=(SELECT status='PARTIALLY_PAID' FROM common_invoices WHERE invoice_id=175);
SET @repair_audits=(SELECT COUNT(*) FROM business_audit_events
 WHERE action='common_invoice_archive_restored' AND entity_type='common_invoice' AND entity_id='175');
-- guard: replay only our exact completed operation; no silent archive of a different state
INSERT INTO repair_175_guards SELECT 'idempotency',
 (@repair_needed=1 AND @repair_audits=0) OR (@repair_needed=0 AND @repair_audits=1 AND
  EXISTS(SELECT 1 FROM business_audit_events
   WHERE action='common_invoice_archive_restored' AND entity_type='common_invoice' AND entity_id='175'
   AND source='operational_repair' AND old_value='PARTIALLY_PAID' AND new_value='ARCHIVED'
   AND JSON_VALID(details) AND JSON_UNQUOTE(JSON_EXTRACT(details,'$.repair_key'))='invoice-175-archive-20261023'
   AND JSON_UNQUOTE(JSON_EXTRACT(details,'$.plan_sha256'))=__PLAN_HASH__));

__SNAPSHOT__
SET @before_digest=@financial_digest;
INSERT INTO repair_175_guards SELECT 'replay_financial_snapshot',@repair_needed=1 OR EXISTS(
 SELECT 1 FROM business_audit_events WHERE action='common_invoice_archive_restored'
 AND entity_type='common_invoice' AND entity_id='175' AND JSON_VALID(details)
 AND JSON_UNQUOTE(JSON_EXTRACT(details,'$.financial_digest'))=@before_digest);
__FINALIZE__
