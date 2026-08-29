-- Close the historical hole where an order pre-marked complete could be moved
-- to "Оплачено" without the rest of the financial transition. Preserve every
-- correction in the business audit and add database guards for future writes.

CREATE TEMPORARY TABLE v268_stale_checks AS
SELECT payment.check_id,
       payment.check_order AS order_id,
       payment.check_company AS company_id,
       COALESCE(payment.check_sum, 0.00) AS check_sum,
       COALESCE(
           base_order.order_amount + COALESCE(bad_tasks.done_count, 0),
           historical_salary.payable_amount,
           0
       ) AS payable_amount
FROM payment_check payment
LEFT JOIN orders base_order ON base_order.order_id = payment.check_order
LEFT JOIN order_statuses order_status ON order_status.order_status_id = base_order.order_status
LEFT JOIN (
    SELECT bad_review_task_order AS order_id,
           SUM(CASE WHEN bad_review_task_status = 'DONE' THEN 1 ELSE 0 END) AS done_count
    FROM bad_review_tasks
    GROUP BY bad_review_task_order
) bad_tasks ON bad_tasks.order_id = base_order.order_id
LEFT JOIN (
    SELECT zp_order AS order_id, MAX(zp_amount) AS payable_amount
    FROM zp
    GROUP BY zp_order
) historical_salary ON historical_salary.order_id = payment.check_order
WHERE payment.check_active = 1
  AND (
      base_order.order_id IS NULL
      OR order_status.order_status_title IS NULL
      OR order_status.order_status_title <> 'Оплачено'
  );

INSERT INTO business_audit_events (
    created_at, actor, source, action, entity_type, entity_id,
    order_id, old_value, new_value, details
)
SELECT CURRENT_TIMESTAMP(6),
       'system:flyway-v268',
       'financial_repair',
       'PAYMENT_CHECK_QUARANTINED',
       'PAYMENT_CHECK',
       CAST(stale.check_id AS CHAR),
       stale.order_id,
       CONCAT('active=1;sum=', stale.check_sum),
       CONCAT('active=0;sum=', stale.check_sum),
       'Активный чек не подтвержден текущим статусом Оплачено; запись сохранена в истории'
FROM v268_stale_checks stale;

UPDATE companies company
JOIN (
    SELECT company_id,
           SUM(check_sum) AS canceled_sum,
           SUM(payable_amount) AS canceled_amount
    FROM v268_stale_checks
    GROUP BY company_id
) correction ON correction.company_id = company.company_id
SET company.company_sum = GREATEST(0.00, COALESCE(company.company_sum, 0.00) - correction.canceled_sum),
    company.company_counter_pay = GREATEST(0, COALESCE(company.company_counter_pay, 0) - correction.canceled_amount),
    company.row_version = company.row_version + 1;

UPDATE payment_check payment
JOIN v268_stale_checks stale ON stale.check_id = payment.check_id
SET payment.check_active = 0;

CREATE TEMPORARY TABLE v268_missing_checks AS
SELECT base_order.order_id,
       base_order.order_company AS company_id,
       company.company_title,
       manager_user.user_id AS manager_user_id,
       base_order.order_amount + COALESCE(bad_tasks.done_count, 0) AS payable_amount,
       CASE
           WHEN COALESCE(link_cash.cash_kopecks, 0) > 0 THEN link_cash.cash_kopecks
           ELSE invoice_cash.cash_kopecks
       END AS paid_kopecks,
       CASE
           WHEN COALESCE(link_cash.cash_kopecks, 0) > 0 THEN DATE(link_cash.paid_at)
           ELSE DATE(invoice_cash.paid_at)
       END AS paid_on
FROM orders base_order
JOIN order_statuses order_status
  ON order_status.order_status_id = base_order.order_status
 AND order_status.order_status_title = 'Оплачено'
JOIN companies company ON company.company_id = base_order.order_company
LEFT JOIN managers manager_user ON manager_user.manager_id = base_order.order_manager
LEFT JOIN (
    SELECT bad_review_task_order AS order_id,
           SUM(CASE WHEN bad_review_task_status = 'DONE' THEN 1 ELSE 0 END) AS done_count
    FROM bad_review_tasks
    GROUP BY bad_review_task_order
) bad_tasks ON bad_tasks.order_id = base_order.order_id
LEFT JOIN (
    SELECT payment_link.order_id,
           SUM(COALESCE(
               payment_link.confirmed_amount_kopecks,
               payment_link.reserved_amount_kopecks,
               payment_link.amount_kopecks
           )) AS cash_kopecks,
           MIN(COALESCE(payment_link.manual_confirmed_at, payment_link.paid_at)) AS paid_at
    FROM payment_links payment_link
    WHERE payment_link.status IN ('CONFIRMED', 'AMOUNT_MISMATCH')
    GROUP BY payment_link.order_id
) link_cash ON link_cash.order_id = base_order.order_id
LEFT JOIN (
    SELECT invoice_order.order_id,
           SUM(invoice_order.amount_kopecks) AS cash_kopecks,
           MIN(invoice_order.paid_at) AS paid_at
    FROM common_invoice_orders invoice_order
    WHERE invoice_order.paid = 1
    GROUP BY invoice_order.order_id
) invoice_cash ON invoice_cash.order_id = base_order.order_id
WHERE NOT EXISTS (
    SELECT 1
    FROM payment_check active_check
    WHERE active_check.check_order = base_order.order_id
      AND active_check.check_active = 1
)
  AND (
      COALESCE(link_cash.cash_kopecks, 0) > 0
      OR COALESCE(invoice_cash.cash_kopecks, 0) > 0
  );

INSERT INTO business_audit_events (
    created_at, actor, source, action, entity_type, entity_id,
    order_id, old_value, new_value, details
)
SELECT CURRENT_TIMESTAMP(6),
       'system:flyway-v268',
       'financial_repair',
       'MISSING_PAYMENT_CHECK_RESTORED',
       'ORDER',
       CAST(repair.order_id AS CHAR),
       repair.order_id,
       'active_check=missing',
       CONCAT('active_check=', repair.paid_kopecks, ' kopecks;paid_on=', repair.paid_on),
       'Чек восстановлен только по сохраненному денежному подтверждению; итоги компании восстановлены той же транзакцией'
FROM v268_missing_checks repair;

INSERT INTO payment_check (
    check_title,
    check_company,
    check_order,
    check_manager,
    check_worker,
    check_date,
    check_sum,
    check_active
)
SELECT repair.company_title,
       repair.company_id,
       repair.order_id,
       repair.manager_user_id,
       repair.manager_user_id,
       COALESCE(repair.paid_on, CURRENT_DATE()),
       repair.paid_kopecks / 100.0,
       1
FROM v268_missing_checks repair;

UPDATE orders base_order
JOIN v268_missing_checks repair ON repair.order_id = base_order.order_id
SET base_order.order_pay_day = COALESCE(base_order.order_pay_day, repair.paid_on),
    base_order.row_version = base_order.row_version + 1;

UPDATE companies company
JOIN (
    SELECT company_id,
           SUM(paid_kopecks) / 100.0 AS restored_sum,
           SUM(payable_amount) AS restored_amount
    FROM v268_missing_checks
    GROUP BY company_id
) repair ON repair.company_id = company.company_id
SET company.company_sum = COALESCE(company.company_sum, 0.00) + repair.restored_sum,
    company.company_counter_pay = COALESCE(company.company_counter_pay, 0) + repair.restored_amount,
    company.row_version = company.row_version + 1;

-- Turn already-known cash/order amount differences into visible review states.
-- Cash is never rewritten automatically: overpayment may require a real refund.
CREATE TEMPORARY TABLE v268_cash_link_rows AS
SELECT payment_link.id AS link_id,
       payment_link.order_id,
       COALESCE(
           payment_link.confirmed_amount_kopecks,
           payment_link.reserved_amount_kopecks,
           payment_link.amount_kopecks
       ) AS link_kopecks,
       SUM(COALESCE(
           payment_link.confirmed_amount_kopecks,
           payment_link.reserved_amount_kopecks,
           payment_link.amount_kopecks
       )) OVER (PARTITION BY payment_link.order_id) AS order_cash_kopecks,
       COUNT(*) OVER (PARTITION BY payment_link.order_id) AS cash_link_count,
       ROW_NUMBER() OVER (
           PARTITION BY payment_link.order_id
           ORDER BY COALESCE(payment_link.manual_confirmed_at, payment_link.paid_at), payment_link.id
       ) AS cash_sequence,
       ROUND((COALESCE(base_order.order_sum, 0.00)
           + COALESCE(bad_tasks.done_sum, 0.00)) * 100) AS payable_kopecks
FROM payment_links payment_link
JOIN orders base_order ON base_order.order_id = payment_link.order_id
LEFT JOIN (
    SELECT bad_review_task_order AS order_id,
           SUM(CASE
               WHEN bad_review_task_status = 'DONE' THEN COALESCE(bad_review_task_price, 0.00)
               ELSE 0.00
           END) AS done_sum
    FROM bad_review_tasks
    GROUP BY bad_review_task_order
) bad_tasks ON bad_tasks.order_id = base_order.order_id
WHERE payment_link.status IN ('CONFIRMED', 'AMOUNT_MISMATCH');

CREATE TEMPORARY TABLE v268_link_mismatches AS
SELECT cash.*
FROM v268_cash_link_rows cash
WHERE cash.order_cash_kopecks <> cash.payable_kopecks
  AND (
      cash.cash_link_count = 1
      OR cash.cash_sequence > 1
      OR cash.link_kopecks <> cash.payable_kopecks
  );

INSERT INTO business_audit_events (
    created_at, actor, source, action, entity_type, entity_id,
    order_id, old_value, new_value, details
)
SELECT CURRENT_TIMESTAMP(6),
       'system:flyway-v268',
       'financial_repair',
       'PAYMENT_AMOUNT_RECONCILIATION_REQUIRED',
       'PAYMENT_LINK',
       CAST(mismatch.link_id AS CHAR),
       mismatch.order_id,
       CONCAT('status=CONFIRMED;cash=', mismatch.order_cash_kopecks),
       CONCAT('status=AMOUNT_MISMATCH;payable=', mismatch.payable_kopecks),
       'Фактические деньги сохранены без изменения; требуется проверка банка и при необходимости возврат'
FROM v268_link_mismatches mismatch
JOIN payment_links payment_link ON payment_link.id = mismatch.link_id
WHERE payment_link.status = 'CONFIRMED';

UPDATE payment_links payment_link
JOIN v268_link_mismatches mismatch ON mismatch.link_id = payment_link.id
SET payment_link.status = 'AMOUNT_MISMATCH',
    payment_link.last_error = LEFT(CONCAT(
        'payment_order_amount_mismatch: фактически по заказу ',
        mismatch.order_cash_kopecks,
        ' коп., текущая стоимость ',
        mismatch.payable_kopecks,
        ' коп.; автоматический возврат запрещен'
    ), 512),
    payment_link.row_version = payment_link.row_version + 1;

ALTER TABLE payment_check
    ADD COLUMN check_payment_status_guard BIGINT NULL AFTER check_order,
    ADD COLUMN check_active_order_guard BIGINT
        GENERATED ALWAYS AS (
            CASE
                WHEN check_active = 1 AND check_order IS NOT NULL AND check_order > 0 THEN check_order
                ELSE NULL
            END
        ) STORED AFTER check_payment_status_guard;

UPDATE payment_check payment
JOIN salary_paid_order_status_guard paid_guard ON paid_guard.singleton_id = 1
SET payment.check_payment_status_guard = paid_guard.order_status_id
WHERE payment.check_active = 1;

ALTER TABLE payment_check
    ADD CONSTRAINT uk_payment_check_active_order UNIQUE (check_active_order_guard),
    ADD CONSTRAINT ck_payment_check_active_paid_guard
        CHECK (check_active_order_guard IS NULL OR check_payment_status_guard IS NOT NULL),
    ADD CONSTRAINT fk_payment_check_paid_status_guard
        FOREIGN KEY (check_payment_status_guard)
        REFERENCES salary_paid_order_status_guard (order_status_id),
    ADD CONSTRAINT fk_payment_check_active_paid_status
        FOREIGN KEY (check_active_order_guard, check_payment_status_guard)
        REFERENCES orders (order_id, order_status);

ALTER TABLE archive_payment_check
    ADD COLUMN check_payment_status_guard BIGINT NULL AFTER check_order;

-- The post-salary-repair reserve tail is exposure, not paid money. Existing
-- client-facing routes are retained fail-closed and made auditable; future
-- reservations remain blocked by the normal capacity calculation.
CREATE TEMPORARY TABLE v268_reserve_overrun AS
SELECT profile.id AS profile_id,
       profile.user_id,
       profile.contractor_role,
       profile.opening_balance_kopecks + COALESCE(ledger.accrued_kopecks, 0) AS accrued_kopecks,
       GREATEST(0, COALESCE(exposure.confirmed_kopecks, 0) - COALESCE(exposure.returned_kopecks, 0)) AS paid_kopecks,
       COALESCE(exposure.outstanding_kopecks, 0) AS outstanding_kopecks,
       GREATEST(
           0,
           GREATEST(0, COALESCE(exposure.confirmed_kopecks, 0) - COALESCE(exposure.returned_kopecks, 0))
               + COALESCE(exposure.outstanding_kopecks, 0)
               - (profile.opening_balance_kopecks + COALESCE(ledger.accrued_kopecks, 0))
       ) AS overrun_kopecks,
       exposure.sample_order_id
FROM contractor_payment_profiles profile
LEFT JOIN (
    SELECT profile_id,
           SUM(CASE WHEN active = 1 THEN amount_kopecks ELSE 0 END) AS accrued_kopecks
    FROM contractor_reward_ledger
    GROUP BY profile_id
) ledger ON ledger.profile_id = profile.id
LEFT JOIN (
    SELECT recipient_profile_id,
           SUM(GREATEST(0, confirmed_kopecks)) AS confirmed_kopecks,
           SUM(GREATEST(0, returned_kopecks)) AS returned_kopecks,
           SUM(CASE
               WHEN status IN ('RESERVED', 'CLIENT_REPORTED', 'PARTIALLY_CONFIRMED')
                   THEN GREATEST(0, amount_kopecks - GREATEST(0, confirmed_kopecks - returned_kopecks))
               ELSE 0
           END) AS outstanding_kopecks,
           MIN(CASE
               WHEN status IN ('RESERVED', 'CLIENT_REPORTED', 'PARTIALLY_CONFIRMED') THEN order_id
               ELSE NULL
           END) AS sample_order_id
    FROM contractor_payment_allocations
    WHERE mode = 'LIVE'
    GROUP BY recipient_profile_id
) exposure ON exposure.recipient_profile_id = profile.id
HAVING overrun_kopecks > 0;

INSERT INTO business_audit_events (
    created_at, actor, source, action, entity_type, entity_id,
    order_id, old_value, new_value, details
)
SELECT CURRENT_TIMESTAMP(6),
       'system:flyway-v268',
       'financial_repair',
       'CONTRACTOR_RESERVE_OVERRUN_FROZEN',
       'CONTRACTOR_PAYMENT_PROFILE',
       CAST(overrun.profile_id AS CHAR),
       overrun.sample_order_id,
       CONCAT('accrued=', overrun.accrued_kopecks, ';paid=', overrun.paid_kopecks),
       CONCAT('outstanding=', overrun.outstanding_kopecks, ';overrun=', overrun.overrun_kopecks),
       'Существующие реквизиты могли быть показаны клиентам: автоматическое освобождение запрещено, новые резервы сверх доступного заработка заблокированы'
FROM v268_reserve_overrun overrun;

INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES (
    'financial-integrity.v268-analytics-rebuild-pending',
    'true',
    CURRENT_TIMESTAMP(6)
)
ON DUPLICATE KEY UPDATE
    setting_value = VALUES(setting_value),
    updated_at = VALUES(updated_at);

DROP TEMPORARY TABLE v268_reserve_overrun;
DROP TEMPORARY TABLE v268_link_mismatches;
DROP TEMPORARY TABLE v268_cash_link_rows;
DROP TEMPORARY TABLE v268_missing_checks;
DROP TEMPORARY TABLE v268_stale_checks;
