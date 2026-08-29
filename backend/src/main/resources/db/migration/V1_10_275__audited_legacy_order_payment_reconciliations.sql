-- Preserve the real bank/card history while explicitly reconciling three
-- historical order-price differences confirmed by the owner on 2026-08-29.
-- A positive adjustment accepts a historical settlement below the current
-- order price. A negative adjustment allocates excess cash outside the order
-- as client overpayment. Neither direction pretends that money moved.

CREATE TABLE order_payment_reconciliations (
    id BIGINT NOT NULL AUTO_INCREMENT,
    reconciliation_key VARCHAR(160) NOT NULL,
    order_id BIGINT NOT NULL,
    reconciliation_type VARCHAR(32) NOT NULL,
    adjustment_kopecks BIGINT NOT NULL,
    effective_at DATETIME(6) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    evidence_reference VARCHAR(255) NOT NULL,
    actor VARCHAR(150) NOT NULL,
    active TINYINT(1) NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_order_payment_reconciliation_key UNIQUE (reconciliation_key),
    CONSTRAINT ck_order_payment_reconciliation_type CHECK (
        reconciliation_type IN ('ACCEPTED_SETTLEMENT', 'CLIENT_OVERPAYMENT')
    ),
    CONSTRAINT ck_order_payment_reconciliation_amount CHECK (adjustment_kopecks <> 0),
    CONSTRAINT fk_order_payment_reconciliation_order
        FOREIGN KEY (order_id) REFERENCES orders (order_id),
    INDEX idx_order_payment_reconciliation_active (order_id, active, id)
);

CREATE TEMPORARY TABLE v275_preflight_guard (
    ok TINYINT NOT NULL
);

-- A clean installation has none of the production orders and needs no data
-- repair. If any target order exists, every primary source must match the
-- audited pre-repair state; otherwise the migration fails closed.
INSERT INTO v275_preflight_guard (ok)
SELECT CASE
    WHEN NOT EXISTS (
        SELECT 1 FROM orders WHERE order_id IN (24273, 24378, 25667)
    ) THEN 1
    WHEN EXISTS (
        SELECT 1
        FROM orders base_order
        JOIN order_statuses order_status
          ON order_status.order_status_id = base_order.order_status
        JOIN payment_check payment
          ON payment.check_id = 20319
         AND payment.check_order = base_order.order_id
         AND payment.check_active = 1
        JOIN payment_links payment_link
          ON payment_link.id = 4117
         AND payment_link.order_id = base_order.order_id
        WHERE base_order.order_id = 24273
          AND order_status.order_status_title = 'Оплачено'
          AND base_order.order_sum = 3000.00
          AND payment.check_sum = 2550.00
          AND payment_link.status = 'AMOUNT_MISMATCH'
          AND payment_link.amount_kopecks = 255000
          AND payment_link.confirmed_amount_kopecks = 255000
    )
    AND EXISTS (
        SELECT 1
        FROM orders base_order
        JOIN order_statuses order_status
          ON order_status.order_status_id = base_order.order_status
        JOIN payment_check payment
          ON payment.check_id = 20240
         AND payment.check_order = base_order.order_id
         AND payment.check_active = 1
        JOIN payment_links first_payment
          ON first_payment.id = 3815
         AND first_payment.order_id = base_order.order_id
        JOIN payment_links second_payment
          ON second_payment.id = 3918
         AND second_payment.order_id = base_order.order_id
        WHERE base_order.order_id = 24378
          AND order_status.order_status_title = 'Оплачено'
          AND base_order.order_sum = 1000.00
          AND payment.check_sum = 2000.00
          AND first_payment.status = 'CONFIRMED'
          AND first_payment.confirmed_amount_kopecks = 100000
          AND second_payment.status = 'AMOUNT_MISMATCH'
          AND second_payment.confirmed_amount_kopecks = 100000
    )
    AND EXISTS (
        SELECT 1
        FROM orders base_order
        JOIN order_statuses order_status
          ON order_status.order_status_id = base_order.order_status
        JOIN payment_check payment
          ON payment.check_id = 20971
         AND payment.check_order = base_order.order_id
         AND payment.check_active = 1
        JOIN payment_links payment_link
          ON payment_link.id = 5758
         AND payment_link.order_id = base_order.order_id
        WHERE base_order.order_id = 25667
          AND order_status.order_status_title = 'Оплачено'
          AND base_order.order_sum = 200.00
          AND payment.check_sum = 250.00
          AND payment_link.status = 'AMOUNT_MISMATCH'
          AND payment_link.amount_kopecks = 25000
          AND payment_link.confirmed_amount_kopecks = 25000
    ) THEN 1
    ELSE NULL
END;

INSERT INTO order_payment_reconciliations (
    reconciliation_key,
    order_id,
    reconciliation_type,
    adjustment_kopecks,
    effective_at,
    reason,
    evidence_reference,
    actor,
    active
)
SELECT 'V275:ORDER:24273:ACCEPTED-SETTLEMENT',
       24273,
       'ACCEPTED_SETTLEMENT',
       45000,
       TIMESTAMP('2026-07-21 00:00:00'),
       'Клиент оплатил согласованные на дату платежа 2550.00 RUB; увеличение текущей стоимости на 450.00 RUB произошло после оплаты. По подтверждению владельца заказ закрыт без изменения денежного поступления.',
       'OWNER_CONFIRMATION:2026-08-29',
       'owner:hunt',
       1
WHERE EXISTS (SELECT 1 FROM orders WHERE order_id = 24273);

INSERT INTO order_payment_reconciliations (
    reconciliation_key,
    order_id,
    reconciliation_type,
    adjustment_kopecks,
    effective_at,
    reason,
    evidence_reference,
    actor,
    active
)
SELECT 'V275:ORDER:24378:CLIENT-OVERPAYMENT',
       24378,
       'CLIENT_OVERPAYMENT',
       -100000,
       TIMESTAMP('2026-07-10 10:42:58.556844'),
       'Два фактических платежа по 1000.00 RUB сохранены. К стоимости заказа отнесено 1000.00 RUB; вторые 1000.00 RUB выделены как нераспределенная переплата клиента до отдельного зачета или возврата.',
       'OWNER_CONFIRMATION:2026-08-29',
       'owner:hunt',
       1
WHERE EXISTS (SELECT 1 FROM orders WHERE order_id = 24378);

INSERT INTO order_payment_reconciliations (
    reconciliation_key,
    order_id,
    reconciliation_type,
    adjustment_kopecks,
    effective_at,
    reason,
    evidence_reference,
    actor,
    active
)
SELECT 'V275:ORDER:25667:CLIENT-OVERPAYMENT',
       25667,
       'CLIENT_OVERPAYMENT',
       -5000,
       TIMESTAMP('2026-08-07 11:53:56.171892'),
       'Фактически получено 250.00 RUB, после чего цена заказа стала 200.00 RUB. Разница 50.00 RUB выделена как переплата клиента до отдельного зачета или возврата.',
       'OWNER_CONFIRMATION:2026-08-29',
       'owner:hunt',
       1
WHERE EXISTS (SELECT 1 FROM orders WHERE order_id = 25667);

INSERT INTO business_audit_events (
    created_at, actor, source, action, entity_type, entity_id,
    order_id, old_value, new_value, details
)
SELECT CURRENT_TIMESTAMP(6),
       reconciliation.actor,
       'owner_confirmed_payment_reconciliation',
       'ORDER_PAYMENT_HISTORY_RECONCILED',
       'ORDER',
       CAST(reconciliation.order_id AS CHAR),
       reconciliation.order_id,
       CASE reconciliation.order_id
           WHEN 24273 THEN 'cash=255000;payable=300000'
           WHEN 24378 THEN 'cash=200000;payable=100000'
           WHEN 25667 THEN 'cash=25000;payable=20000'
       END,
       CONCAT(
           'adjustment=', reconciliation.adjustment_kopecks,
           ';type=', reconciliation.reconciliation_type,
           ';active=1'
       ),
       reconciliation.reason
FROM order_payment_reconciliations reconciliation
WHERE reconciliation.reconciliation_key IN (
    'V275:ORDER:24273:ACCEPTED-SETTLEMENT',
    'V275:ORDER:24378:CLIENT-OVERPAYMENT',
    'V275:ORDER:25667:CLIENT-OVERPAYMENT'
);

-- The money was already received; AMOUNT_MISMATCH represented an unresolved
-- review state, not a provider failure. The reconciliation rows now carry the
-- durable explanation, so the payment links may return to CONFIRMED.
UPDATE payment_links
SET status = 'CONFIRMED',
    last_error = NULL,
    row_version = row_version + 1,
    updated_at = CURRENT_TIMESTAMP(6)
WHERE id IN (4117, 3918, 5758)
  AND status = 'AMOUNT_MISMATCH';

UPDATE personal_reminders
SET completed_at = COALESCE(completed_at, CURRENT_TIMESTAMP(6)),
    updated_at = CURRENT_TIMESTAMP(6)
WHERE source_order_id IN (24273, 24378, 25667)
  AND source_type IN (
      'PAYMENT_ACCOUNTING_MISMATCH',
      'PAYMENT_ORDER_FACT_MISMATCH'
  );

DROP TEMPORARY TABLE v275_preflight_guard;
