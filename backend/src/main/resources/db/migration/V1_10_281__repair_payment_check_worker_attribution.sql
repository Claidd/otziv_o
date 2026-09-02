-- PaymentCheckService historically copied the manager user id into both
-- check_manager and check_worker. Repair only active financial facts for which
-- the order still has an unambiguous worker -> user identity. Unresolvable
-- history remains untouched rather than guessing an attribution.

DROP TEMPORARY TABLE IF EXISTS v281_live_payment_check_worker_repairs;
DROP TEMPORARY TABLE IF EXISTS v281_archive_payment_check_worker_repairs;
DROP TEMPORARY TABLE IF EXISTS v281_live_payment_check_worker_unresolved;
DROP TEMPORARY TABLE IF EXISTS v281_archive_payment_check_worker_unresolved;

CREATE TEMPORARY TABLE v281_live_payment_check_worker_repairs AS
SELECT payment.check_id,
       payment.check_order AS order_id,
       payment.check_worker AS previous_worker_user_id,
       actual_worker.user_id AS actual_worker_user_id
FROM payment_check payment
JOIN orders base_order ON base_order.order_id = payment.check_order
JOIN workers actual_worker ON actual_worker.worker_id = base_order.order_worker
WHERE payment.check_active = 1
  AND payment.check_manager IS NOT NULL
  AND payment.check_worker = payment.check_manager
  AND actual_worker.user_id IS NOT NULL
  AND NOT (payment.check_worker <=> actual_worker.user_id);

CREATE TEMPORARY TABLE v281_archive_payment_check_worker_repairs AS
SELECT payment.check_id,
       payment.check_order AS order_id,
       payment.check_worker AS previous_worker_user_id,
       actual_worker.user_id AS actual_worker_user_id
FROM archive_payment_check payment
JOIN archive_orders base_order ON base_order.order_id = payment.check_order
JOIN workers actual_worker ON actual_worker.worker_id = base_order.order_worker
WHERE payment.check_active = 1
  AND payment.check_manager IS NOT NULL
  AND payment.check_worker = payment.check_manager
  AND actual_worker.user_id IS NOT NULL
  AND NOT (payment.check_worker <=> actual_worker.user_id);

CREATE TEMPORARY TABLE v281_live_payment_check_worker_unresolved AS
SELECT payment.check_id,
       payment.check_order AS order_id,
       payment.check_worker AS previous_worker_user_id,
       CASE
           WHEN base_order.order_id IS NULL THEN 'order_missing'
           WHEN base_order.order_worker IS NULL THEN 'order_worker_missing'
           WHEN actual_worker.worker_id IS NULL THEN 'worker_profile_missing'
           WHEN actual_worker.user_id IS NULL THEN 'worker_user_missing'
           ELSE 'worker_mismatch_requires_review'
       END AS resolution_reason
FROM payment_check payment
LEFT JOIN orders base_order ON base_order.order_id = payment.check_order
LEFT JOIN workers actual_worker ON actual_worker.worker_id = base_order.order_worker
WHERE payment.check_active = 1
  AND (
      actual_worker.user_id IS NULL
      OR (
          NOT (payment.check_worker <=> actual_worker.user_id)
          AND NOT (
              payment.check_manager IS NOT NULL
              AND (payment.check_worker <=> payment.check_manager)
          )
      )
  );

CREATE TEMPORARY TABLE v281_archive_payment_check_worker_unresolved AS
SELECT payment.check_id,
       payment.check_order AS order_id,
       payment.check_worker AS previous_worker_user_id,
       CASE
           WHEN base_order.order_id IS NULL THEN 'archive_order_missing'
           WHEN base_order.order_worker IS NULL THEN 'archive_order_worker_missing'
           WHEN actual_worker.worker_id IS NULL THEN 'worker_profile_missing'
           WHEN actual_worker.user_id IS NULL THEN 'worker_user_missing'
           ELSE 'worker_mismatch_requires_review'
       END AS resolution_reason
FROM archive_payment_check payment
LEFT JOIN archive_orders base_order ON base_order.order_id = payment.check_order
LEFT JOIN workers actual_worker ON actual_worker.worker_id = base_order.order_worker
WHERE payment.check_active = 1
  AND (
      actual_worker.user_id IS NULL
      OR (
          NOT (payment.check_worker <=> actual_worker.user_id)
          AND NOT (
              payment.check_manager IS NOT NULL
              AND (payment.check_worker <=> payment.check_manager)
          )
      )
  );

-- The existing durable repair worker rebuilds closed and current analytics
-- months after the source attribution changes. Set the flag before rewriting
-- rows so a partially interrupted migration cannot lose the rebuild request.
INSERT INTO app_settings (setting_key, setting_value, updated_at)
SELECT 'financial-integrity.v268-analytics-rebuild-pending',
       'true',
       CURRENT_TIMESTAMP(6)
FROM DUAL
WHERE EXISTS (SELECT 1 FROM v281_live_payment_check_worker_repairs)
   OR EXISTS (SELECT 1 FROM v281_archive_payment_check_worker_repairs)
ON DUPLICATE KEY UPDATE
    setting_value = VALUES(setting_value),
    updated_at = VALUES(updated_at);

INSERT INTO business_audit_events (
    created_at, actor, source, action, entity_type, entity_id,
    order_id, old_value, new_value, details
)
SELECT CURRENT_TIMESTAMP(6),
       'system:flyway-v281',
       'financial_repair',
       'PAYMENT_CHECK_WORKER_REATTRIBUTED',
       'PAYMENT_CHECK',
       CAST(repair.check_id AS CHAR),
       repair.order_id,
       CONCAT('check_worker=', COALESCE(CAST(repair.previous_worker_user_id AS CHAR), 'NULL')),
       CONCAT('check_worker=', repair.actual_worker_user_id),
       'Исполнитель чека восстановлен из orders.order_worker -> workers.user_id'
FROM v281_live_payment_check_worker_repairs repair
WHERE NOT EXISTS (
    SELECT 1
    FROM business_audit_events existing
    WHERE existing.actor = 'system:flyway-v281'
      AND existing.action = 'PAYMENT_CHECK_WORKER_REATTRIBUTED'
      AND existing.entity_type = 'PAYMENT_CHECK'
      AND existing.entity_id COLLATE utf8mb4_unicode_ci =
          CAST(repair.check_id AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_unicode_ci
);

INSERT INTO business_audit_events (
    created_at, actor, source, action, entity_type, entity_id,
    order_id, old_value, new_value, details
)
SELECT CURRENT_TIMESTAMP(6),
       'system:flyway-v281',
       'financial_repair',
       'PAYMENT_CHECK_WORKER_REATTRIBUTED',
       'ARCHIVE_PAYMENT_CHECK',
       CAST(repair.check_id AS CHAR),
       repair.order_id,
       CONCAT('check_worker=', COALESCE(CAST(repair.previous_worker_user_id AS CHAR), 'NULL')),
       CONCAT('check_worker=', repair.actual_worker_user_id),
       'Исполнитель архивного чека восстановлен из archive_orders.order_worker -> workers.user_id'
FROM v281_archive_payment_check_worker_repairs repair
WHERE NOT EXISTS (
    SELECT 1
    FROM business_audit_events existing
    WHERE existing.actor = 'system:flyway-v281'
      AND existing.action = 'PAYMENT_CHECK_WORKER_REATTRIBUTED'
      AND existing.entity_type = 'ARCHIVE_PAYMENT_CHECK'
      AND existing.entity_id COLLATE utf8mb4_unicode_ci =
          CAST(repair.check_id AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_unicode_ci
);

INSERT INTO business_audit_events (
    created_at, actor, source, action, entity_type, entity_id,
    order_id, old_value, new_value, details
)
SELECT CURRENT_TIMESTAMP(6),
       'system:flyway-v281',
       'financial_repair',
       'PAYMENT_CHECK_WORKER_REATTRIBUTION_REQUIRED',
       'PAYMENT_CHECK',
       CAST(unresolved.check_id AS CHAR),
       unresolved.order_id,
       CONCAT('check_worker=', COALESCE(CAST(unresolved.previous_worker_user_id AS CHAR), 'NULL')),
       'check_worker=UNCHANGED',
       CONCAT('Автоматическое исправление запрещено: ', unresolved.resolution_reason)
FROM v281_live_payment_check_worker_unresolved unresolved
WHERE NOT EXISTS (
    SELECT 1
    FROM business_audit_events existing
    WHERE existing.actor = 'system:flyway-v281'
      AND existing.action = 'PAYMENT_CHECK_WORKER_REATTRIBUTION_REQUIRED'
      AND existing.entity_type = 'PAYMENT_CHECK'
      AND existing.entity_id COLLATE utf8mb4_unicode_ci =
          CAST(unresolved.check_id AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_unicode_ci
);

INSERT INTO business_audit_events (
    created_at, actor, source, action, entity_type, entity_id,
    order_id, old_value, new_value, details
)
SELECT CURRENT_TIMESTAMP(6),
       'system:flyway-v281',
       'financial_repair',
       'PAYMENT_CHECK_WORKER_REATTRIBUTION_REQUIRED',
       'ARCHIVE_PAYMENT_CHECK',
       CAST(unresolved.check_id AS CHAR),
       unresolved.order_id,
       CONCAT('check_worker=', COALESCE(CAST(unresolved.previous_worker_user_id AS CHAR), 'NULL')),
       'check_worker=UNCHANGED',
       CONCAT('Автоматическое исправление запрещено: ', unresolved.resolution_reason)
FROM v281_archive_payment_check_worker_unresolved unresolved
WHERE NOT EXISTS (
    SELECT 1
    FROM business_audit_events existing
    WHERE existing.actor = 'system:flyway-v281'
      AND existing.action = 'PAYMENT_CHECK_WORKER_REATTRIBUTION_REQUIRED'
      AND existing.entity_type = 'ARCHIVE_PAYMENT_CHECK'
      AND existing.entity_id COLLATE utf8mb4_unicode_ci =
          CAST(unresolved.check_id AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_unicode_ci
);

UPDATE payment_check payment
JOIN v281_live_payment_check_worker_repairs repair ON repair.check_id = payment.check_id
SET payment.check_worker = repair.actual_worker_user_id;

UPDATE archive_payment_check payment
JOIN v281_archive_payment_check_worker_repairs repair ON repair.check_id = payment.check_id
SET payment.check_worker = repair.actual_worker_user_id;

DROP TEMPORARY TABLE v281_archive_payment_check_worker_unresolved;
DROP TEMPORARY TABLE v281_live_payment_check_worker_unresolved;
DROP TEMPORARY TABLE v281_archive_payment_check_worker_repairs;
DROP TEMPORARY TABLE v281_live_payment_check_worker_repairs;
