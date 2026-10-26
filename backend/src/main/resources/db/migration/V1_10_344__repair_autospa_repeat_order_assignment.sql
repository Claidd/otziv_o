-- Verified incident: repeat request 4131 copied the historical worker of order
-- 19403 after execution 8 had transferred company 748 from worker 13 to 6.
-- Repair only this unfinished successor. Published work keeps its author.
START TRANSACTION;
SELECT company_id INTO @autospa_repair_company_lock
FROM companies WHERE company_id = 748 FOR UPDATE;
SELECT order_id INTO @autospa_repair_order_lock
FROM orders WHERE order_id = 27728 FOR UPDATE;

CREATE TEMPORARY TABLE autospa_repeat_repair_order AS
SELECT o.order_id
FROM orders o
JOIN companies c ON c.company_id = o.order_company
JOIN workers target ON target.worker_id = 6
JOIN users target_user ON target_user.id = target.user_id
WHERE o.order_id = 27728 AND o.order_company = 748 AND o.order_worker = 13
  AND o.order_manager = 2 AND c.company_manager = 2
  AND COALESCE(o.order_complete, 0) = 0 AND o.order_pay_day IS NULL
  AND o.order_counter < o.order_amount AND target_user.active = 1
  AND EXISTS (SELECT 1 FROM managers_users mu
              WHERE mu.user_id = target.user_id AND mu.manager_id = 2)
  AND NOT EXISTS (SELECT 1 FROM managers_users mu
                  WHERE mu.user_id = target.user_id AND mu.manager_id <> 2)
  AND EXISTS (SELECT 1 FROM workers_companies wc WHERE wc.company_id = 748 AND wc.worker_id = 6)
  AND NOT EXISTS (SELECT 1 FROM workers_companies wc WHERE wc.company_id = 748 AND wc.worker_id = 13)
  AND EXISTS (SELECT 1 FROM workload_transfer_executions e
              WHERE e.workload_transfer_execution_id = 8 AND e.company_id = 748
                AND e.source_worker_id = 13 AND e.target_worker_id = 6 AND e.status = 'APPLIED')
  AND NOT EXISTS (SELECT 1 FROM workload_transfer_executions e
                  WHERE e.company_id = 748 AND e.status = 'APPLIED'
                    AND e.workload_transfer_execution_id > 8)
  AND EXISTS (SELECT 1 FROM next_order_requests nr WHERE nr.next_order_request_id = 4131
              AND nr.source_order_id = 19403 AND nr.created_order_id = o.order_id
              AND nr.request_status = 'CREATED')
  AND NOT EXISTS (SELECT 1 FROM zp z WHERE z.zp_order = o.order_id AND z.zp_active = 1)
  AND NOT EXISTS (SELECT 1 FROM payment_check pc WHERE pc.check_order = o.order_id AND pc.check_active = 1)
  AND NOT EXISTS (SELECT 1 FROM contractor_completion_reward_markers m WHERE m.order_id = o.order_id)
  AND NOT EXISTS (SELECT 1 FROM contractor_payment_allocations a
                  WHERE a.order_id = o.order_id OR EXISTS (
                      SELECT 1 FROM common_invoice_orders cio
                      WHERE cio.invoice_id = a.common_invoice_id AND cio.order_id = o.order_id))
  AND NOT EXISTS (SELECT 1 FROM reviews r JOIN order_details d ON d.order_detail_id = r.review_order_details
                  WHERE d.order_detail_order = o.order_id AND COALESCE(r.review_publish, 0) = 0
                    AND (r.review_worker IS NULL OR r.review_worker <> 13))
  AND NOT EXISTS (SELECT 1 FROM bad_review_tasks bt WHERE bt.bad_review_task_order = o.order_id
                  AND bt.bad_review_task_status = 'NEW'
                  AND (bt.bad_review_task_worker IS NULL OR bt.bad_review_task_worker <> 13))
  AND NOT EXISTS (SELECT 1 FROM review_recovery_tasks rt WHERE rt.review_recovery_task_order = o.order_id
                  AND rt.review_recovery_task_status = 'PLANNED'
                  AND (rt.review_recovery_task_worker IS NULL OR rt.review_recovery_task_worker <> 13));

CREATE TEMPORARY TABLE autospa_repeat_repair_reviews (review_id BIGINT PRIMARY KEY);
INSERT INTO autospa_repeat_repair_reviews (review_id)
SELECT r.review_id
FROM reviews r JOIN order_details d ON d.order_detail_id = r.review_order_details
JOIN autospa_repeat_repair_order fix ON fix.order_id = d.order_detail_order
WHERE COALESCE(r.review_publish, 0) = 0 AND r.review_worker = 13
FOR UPDATE;

INSERT INTO business_audit_events
    (created_at, actor, source, action, entity_type, entity_id, order_id, old_value, new_value, details)
SELECT CURRENT_TIMESTAMP(6), 'system', 'migration:1.10.344', 'next_order_assignment_repaired',
       'order', CAST(order_id AS CHAR), order_id, '13', '6',
       'requestId=4131;sourceOrderId=19403;transferExecutionId=8;publishedAuthorsPreserved=true'
FROM autospa_repeat_repair_order;

INSERT INTO business_audit_events
    (created_at, actor, source, action, entity_type, entity_id, order_id, review_id, old_value, new_value, details)
SELECT CURRENT_TIMESTAMP(6), 'system', 'migration:1.10.344', 'next_order_assignment_repaired',
       'review', CAST(review_id AS CHAR), 27728, review_id, '13', '6', 'transferExecutionId=8'
FROM autospa_repeat_repair_reviews;

DELETE preparation FROM worker_credential_preparations preparation
JOIN autospa_repeat_repair_reviews fix ON fix.review_id = preparation.review_id;

UPDATE reviews r JOIN autospa_repeat_repair_reviews fix ON fix.review_id = r.review_id
SET r.review_worker = 6, r.row_version = r.row_version + 1;

UPDATE bad_review_tasks bt JOIN autospa_repeat_repair_order fix ON fix.order_id = bt.bad_review_task_order
SET bt.bad_review_task_worker = 6
WHERE bt.bad_review_task_status = 'NEW' AND bt.bad_review_task_worker = 13;

UPDATE review_recovery_tasks rt JOIN autospa_repeat_repair_order fix ON fix.order_id = rt.review_recovery_task_order
SET rt.review_recovery_task_worker = 6, rt.review_recovery_task_updated_at = CURRENT_TIMESTAMP(6)
WHERE rt.review_recovery_task_status = 'PLANNED' AND rt.review_recovery_task_worker = 13;

UPDATE orders o JOIN autospa_repeat_repair_order fix ON fix.order_id = o.order_id
SET o.order_worker = 6, o.row_version = o.row_version + 1;

DROP TEMPORARY TABLE autospa_repeat_repair_reviews;
DROP TEMPORARY TABLE autospa_repeat_repair_order;
COMMIT;
