-- Salary cutover: completion/publication is no longer an accounting event.
-- The migration is deliberately audit-first and never deletes a ZP row.

ALTER TABLE contractor_payment_rollout_state
    DROP CHECK ck_contractor_payment_rollout_authority;

INSERT INTO business_audit_events (
    created_at, actor, source, action, entity_type, entity_id,
    order_id, review_id, old_value, new_value, details
)
SELECT
    CURRENT_TIMESTAMP(6), 'system', 'flyway', 'SALARY_AUTHORITY_MIGRATED',
    'CONTRACTOR_ROLLOUT', CAST(state.id AS CHAR), NULL, NULL,
    state.accounting_authority, 'PAYMENT',
    'Односторонний writer переведен с выполнения работы на подтвержденную оплату'
FROM contractor_payment_rollout_state state
WHERE state.accounting_authority = 'COMPLETION';

UPDATE contractor_payment_rollout_state
SET accounting_authority = 'PAYMENT',
    updated_at = CURRENT_TIMESTAMP(6),
    updated_by = 'MIGRATION_PAYMENT_SALARY',
    row_version = row_version + 1
WHERE accounting_authority = 'COMPLETION';

ALTER TABLE contractor_payment_rollout_state
    ADD CONSTRAINT ck_contractor_payment_rollout_authority
        CHECK (accounting_authority IN ('LEGACY', 'PAYMENT'));

-- Quarantine every currently active order salary whose order is not paid.
-- The row and its original values remain available for financial audit.
CREATE TEMPORARY TABLE tmp_unpaid_active_salary (
    zp_id BIGINT NOT NULL PRIMARY KEY,
    order_id BIGINT NOT NULL
) ENGINE=InnoDB;

INSERT IGNORE INTO tmp_unpaid_active_salary (zp_id, order_id)
SELECT reward.zp_id, reward.zp_order
FROM zp reward
LEFT JOIN orders source_order ON source_order.order_id = reward.zp_order
LEFT JOIN order_statuses source_status ON source_status.order_status_id = source_order.order_status
WHERE reward.zp_active = 1
  AND reward.zp_order IS NOT NULL
  AND reward.zp_order > 0
  AND COALESCE(source_status.order_status_title, '') <> 'Оплачено';

INSERT INTO business_audit_events (
    created_at, actor, source, action, entity_type, entity_id,
    order_id, review_id, old_value, new_value, details
)
SELECT
    CURRENT_TIMESTAMP(6), 'system', 'flyway', 'UNPAID_SALARY_QUARANTINED',
    'ZP', CAST(reward.zp_id AS CHAR), reward.zp_order, NULL,
    CONCAT(
        'active=1;date=', COALESCE(CAST(reward.zp_date AS CHAR), 'null'),
        ';sum=', COALESCE(CAST(reward.zp_sum AS CHAR), 'null'),
        ';amount=', reward.zp_amount,
        ';user=', COALESCE(CAST(reward.zp_user AS CHAR), 'null'),
        ';profession=', COALESCE(CAST(reward.zp_profession AS CHAR), 'null'),
        ';role=', COALESCE(reward.zp_contractor_role, 'null'),
        ';source=', COALESCE(reward.zp_source, 'null')
    ),
    'active=0',
    'Активная строка исключена из ЗП: у заказа нет подтвержденного статуса оплаты'
FROM tmp_unpaid_active_salary anomaly
JOIN zp reward ON reward.zp_id = anomaly.zp_id;

UPDATE zp reward
JOIN tmp_unpaid_active_salary anomaly ON anomaly.zp_id = reward.zp_id
SET reward.zp_active = 0;

UPDATE contractor_reward_ledger ledger
JOIN tmp_unpaid_active_salary anomaly ON anomaly.zp_id = ledger.source_zp_id
SET ledger.active = 0,
    ledger.updated_at = CURRENT_TIMESTAMP(6);

UPDATE contractor_reward_sync_markers sync_marker
JOIN zp reward ON reward.zp_id = sync_marker.source_zp_id
JOIN tmp_unpaid_active_salary anomaly ON anomaly.zp_id = reward.zp_id
SET sync_marker.source_active = 0,
    sync_marker.source_updated_at = reward.zp_updated_at,
    sync_marker.processed_at = CURRENT_TIMESTAMP(6);

-- Premature markers must not suppress a future, legitimate payment accrual.
INSERT INTO business_audit_events (
    created_at, actor, source, action, entity_type, entity_id,
    order_id, review_id, old_value, new_value, details
)
SELECT
    CURRENT_TIMESTAMP(6), 'system', 'flyway', 'UNPAID_SALARY_MARKER_RESET',
    'ORDER', CAST(marker.order_id AS CHAR), marker.order_id, NULL,
    CONCAT('logical_source=', marker.logical_source, ';occurred_on=', marker.occurred_on),
    'marker_removed',
    'Маркер сохранен в аудите и сброшен для возможной будущей оплаты'
FROM contractor_completion_reward_markers marker
WHERE EXISTS (
    SELECT 1
    FROM tmp_unpaid_active_salary anomaly
    WHERE anomaly.order_id = marker.order_id
);

DELETE marker
FROM contractor_completion_reward_markers marker
WHERE EXISTS (
    SELECT 1
    FROM tmp_unpaid_active_salary anomaly
    WHERE anomaly.order_id = marker.order_id
);

DROP TEMPORARY TABLE tmp_unpaid_active_salary;

-- The ledger is a canonical salary reader too. Quarantine any historical
-- derivative drift even when its source ZP had already become inactive.
CREATE TEMPORARY TABLE tmp_unpaid_active_ledger (
    ledger_id BIGINT NOT NULL PRIMARY KEY,
    order_id BIGINT NOT NULL
) ENGINE=InnoDB;

INSERT IGNORE INTO tmp_unpaid_active_ledger (ledger_id, order_id)
SELECT ledger.id, ledger.order_id
FROM contractor_reward_ledger ledger
LEFT JOIN orders source_order ON source_order.order_id = ledger.order_id
LEFT JOIN order_statuses source_status ON source_status.order_status_id = source_order.order_status
WHERE ledger.active = 1
  AND ledger.order_id IS NOT NULL
  AND ledger.order_id > 0
  AND COALESCE(source_status.order_status_title, '') <> 'Оплачено';

INSERT INTO business_audit_events (
    created_at, actor, source, action, entity_type, entity_id,
    order_id, review_id, old_value, new_value, details
)
SELECT
    CURRENT_TIMESTAMP(6), 'system', 'flyway', 'UNPAID_LEDGER_QUARANTINED',
    'CONTRACTOR_LEDGER', CAST(ledger.id AS CHAR), ledger.order_id, NULL,
    CONCAT(
        'active=1;source_zp_id=', ledger.source_zp_id,
        ';profile_id=', ledger.profile_id,
        ';amount_kopecks=', ledger.amount_kopecks,
        ';occurred_on=', COALESCE(CAST(ledger.occurred_on AS CHAR), 'null')
    ),
    'active=0',
    'Производная строка ledger исключена из ЗП: у заказа нет подтвержденного статуса оплаты'
FROM tmp_unpaid_active_ledger anomaly
JOIN contractor_reward_ledger ledger ON ledger.id = anomaly.ledger_id;

UPDATE contractor_reward_ledger ledger
JOIN tmp_unpaid_active_ledger anomaly ON anomaly.ledger_id = ledger.id
SET ledger.active = 0,
    ledger.updated_at = CURRENT_TIMESTAMP(6);

DROP TEMPORARY TABLE tmp_unpaid_active_ledger;

-- Rows that became legitimate only after a later payment belong to the
-- payment day, not to the earlier completion/publication day.
CREATE TEMPORARY TABLE tmp_early_paid_salary (
    zp_id BIGINT NOT NULL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    old_date DATE NOT NULL,
    paid_date DATE NOT NULL
) ENGINE=InnoDB;

INSERT IGNORE INTO tmp_early_paid_salary (zp_id, order_id, old_date, paid_date)
SELECT reward.zp_id, reward.zp_order, reward.zp_date, source_order.order_pay_day
FROM zp reward
JOIN orders source_order ON source_order.order_id = reward.zp_order
JOIN order_statuses source_status ON source_status.order_status_id = source_order.order_status
JOIN contractor_completion_cutover_state cutover ON cutover.id = 1
WHERE reward.zp_active = 1
  AND source_status.order_status_title = 'Оплачено'
  AND source_order.order_pay_day IS NOT NULL
  AND reward.zp_date >= cutover.attribution_start_date
  AND reward.zp_date < source_order.order_pay_day;

INSERT INTO business_audit_events (
    created_at, actor, source, action, entity_type, entity_id,
    order_id, review_id, old_value, new_value, details
)
SELECT
    CURRENT_TIMESTAMP(6), 'system', 'flyway', 'SALARY_DATE_ALIGNED_TO_PAYMENT',
    'ZP', CAST(reward.zp_id AS CHAR), reward.zp_order, NULL,
    CONCAT('date=', correction.old_date), CONCAT('date=', correction.paid_date),
    'Дата активного начисления исправлена на подтвержденную дату оплаты'
FROM tmp_early_paid_salary correction
JOIN zp reward ON reward.zp_id = correction.zp_id;

UPDATE zp reward
JOIN tmp_early_paid_salary correction ON correction.zp_id = reward.zp_id
SET reward.zp_date = correction.paid_date;

UPDATE contractor_reward_ledger ledger
JOIN tmp_early_paid_salary correction ON correction.zp_id = ledger.source_zp_id
SET ledger.occurred_on = correction.paid_date,
    ledger.updated_at = CURRENT_TIMESTAMP(6);

UPDATE contractor_reward_sync_markers sync_marker
JOIN zp reward ON reward.zp_id = sync_marker.source_zp_id
JOIN tmp_early_paid_salary correction ON correction.zp_id = reward.zp_id
SET sync_marker.source_active = reward.zp_active,
    sync_marker.source_updated_at = reward.zp_updated_at,
    sync_marker.processed_at = CURRENT_TIMESTAMP(6);

UPDATE contractor_completion_reward_markers marker
JOIN orders source_order ON source_order.order_id = marker.order_id
JOIN order_statuses source_status ON source_status.order_status_id = source_order.order_status
JOIN contractor_completion_cutover_state cutover ON cutover.id = 1
SET marker.occurred_on = source_order.order_pay_day
WHERE source_status.order_status_title = 'Оплачено'
  AND source_order.order_pay_day IS NOT NULL
  AND marker.occurred_on >= cutover.attribution_start_date
  AND marker.occurred_on < source_order.order_pay_day;

DROP TEMPORARY TABLE tmp_early_paid_salary;

-- Permanent database boundary without triggers. Production binary logging
-- forbids application-owned CREATE TRIGGER unless the account has SUPER.
-- Generated active-order keys plus declarative foreign keys provide the same
-- fail-closed invariant and also block removing paid status too early.
ALTER TABLE order_statuses
    ADD COLUMN salary_paid_guard TINYINT
        GENERATED ALWAYS AS (
            CASE
                WHEN BINARY order_status_title = BINARY 'Оплачено' THEN 1
                ELSE NULL
            END
        ) STORED AFTER order_status_title,
    ADD CONSTRAINT uk_order_statuses_salary_id_guard
        UNIQUE (order_status_id, salary_paid_guard);

ALTER TABLE orders
    ADD CONSTRAINT uk_orders_salary_id_status
        UNIQUE (order_id, order_status);

CREATE TABLE salary_paid_order_status_guard (
    singleton_id TINYINT NOT NULL,
    order_status_id BIGINT NOT NULL,
    paid_guard TINYINT NOT NULL,
    PRIMARY KEY (singleton_id),
    CONSTRAINT uk_salary_paid_order_status_guard_status UNIQUE (order_status_id),
    CONSTRAINT ck_salary_paid_order_status_guard_singleton CHECK (singleton_id = 1),
    CONSTRAINT ck_salary_paid_order_status_guard_paid CHECK (paid_guard = 1),
    CONSTRAINT fk_salary_paid_order_status_guard_status
        FOREIGN KEY (order_status_id, paid_guard)
        REFERENCES order_statuses (order_status_id, salary_paid_guard)
) ENGINE=InnoDB;

INSERT INTO salary_paid_order_status_guard (
    singleton_id, order_status_id, paid_guard
)
SELECT 1, paid_status.order_status_id, paid_status.salary_paid_guard
FROM order_statuses paid_status
WHERE paid_status.order_status_title = 'Оплачено'
ORDER BY paid_status.order_status_id
LIMIT 1;

ALTER TABLE zp
    ADD COLUMN zp_payment_status_guard BIGINT NULL AFTER zp_order,
    ADD COLUMN zp_active_order_guard BIGINT
        GENERATED ALWAYS AS (
            CASE
                WHEN zp_active = 1 AND zp_order IS NOT NULL AND zp_order > 0 THEN zp_order
                ELSE NULL
            END
        ) STORED AFTER zp_payment_status_guard;

UPDATE zp reward
JOIN salary_paid_order_status_guard paid_guard ON paid_guard.singleton_id = 1
SET reward.zp_payment_status_guard = paid_guard.order_status_id
WHERE reward.zp_order IS NOT NULL
  AND reward.zp_order > 0;

ALTER TABLE zp
    ADD CONSTRAINT ck_zp_active_order_paid_guard
        CHECK (zp_active_order_guard IS NULL OR zp_payment_status_guard IS NOT NULL),
    ADD CONSTRAINT fk_zp_paid_status_guard
        FOREIGN KEY (zp_payment_status_guard)
        REFERENCES salary_paid_order_status_guard (order_status_id),
    ADD CONSTRAINT fk_zp_active_order_paid_status
        FOREIGN KEY (zp_active_order_guard, zp_payment_status_guard)
        REFERENCES orders (order_id, order_status);

ALTER TABLE contractor_reward_ledger
    ADD COLUMN payment_status_guard BIGINT NULL AFTER order_id,
    ADD COLUMN active_order_guard BIGINT
        GENERATED ALWAYS AS (
            CASE
                WHEN active = 1 AND order_id IS NOT NULL AND order_id > 0 THEN order_id
                ELSE NULL
            END
        ) STORED AFTER payment_status_guard;

UPDATE contractor_reward_ledger ledger
JOIN salary_paid_order_status_guard paid_guard ON paid_guard.singleton_id = 1
SET ledger.payment_status_guard = paid_guard.order_status_id
WHERE ledger.order_id IS NOT NULL
  AND ledger.order_id > 0;

ALTER TABLE contractor_reward_ledger
    ADD CONSTRAINT ck_ledger_active_order_paid_guard
        CHECK (active_order_guard IS NULL OR payment_status_guard IS NOT NULL),
    ADD CONSTRAINT fk_ledger_paid_status_guard
        FOREIGN KEY (payment_status_guard)
        REFERENCES salary_paid_order_status_guard (order_status_id),
    ADD CONSTRAINT fk_ledger_active_order_paid_status
        FOREIGN KEY (active_order_guard, payment_status_guard)
        REFERENCES orders (order_id, order_status);
