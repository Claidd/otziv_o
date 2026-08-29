-- Auto-repeat originally copied order_details.product and the current catalog
-- price. A manager can legitimately change individual review products later,
-- so order_details may be only a stale summary. Repair only active unpaid
-- orders. Paid and archived financial history is deliberately immutable.
-- Untouched repeats receive the actual product/price snapshot of every source
-- review. Edited repeats use the owner-approved 2GIS rule below and never
-- rewrite photo or any other non-2GIS product.

CREATE TABLE IF NOT EXISTS auto_repeat_review_term_repairs (
    repair_id BIGINT NOT NULL AUTO_INCREMENT,
    repair_key VARCHAR(190) NOT NULL,
    next_order_request_id BIGINT NOT NULL,
    source_order_id BIGINT NOT NULL,
    created_order_id BIGINT NOT NULL,
    review_id BIGINT NOT NULL,
    storage_type VARCHAR(16) NOT NULL,
    old_product_id BIGINT NULL,
    new_product_id BIGINT NOT NULL,
    old_price DECIMAL(10, 2) NULL,
    new_price DECIMAL(10, 2) NOT NULL,
    actor VARCHAR(150) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (repair_id),
    CONSTRAINT uk_auto_repeat_review_term_repair_key UNIQUE (repair_key),
    INDEX idx_auto_repeat_review_term_repair_order (created_order_id, review_id)
);

CREATE TEMPORARY TABLE v276_requests AS
SELECT next_order_request_id,
       source_order_id,
       created_order_id,
       request_status,
       created_at
FROM next_order_requests
WHERE next_order_request_id <= 2844
UNION ALL
SELECT archived.next_order_request_id,
       archived.source_order_id,
       archived.created_order_id,
       archived.request_status,
       archived.created_at
FROM archive_next_order_requests archived
WHERE archived.next_order_request_id <= 2844
  AND NOT EXISTS (
      SELECT 1
      FROM next_order_requests live
      WHERE live.next_order_request_id = archived.next_order_request_id
  );

CREATE INDEX idx_v276_requests_created ON v276_requests (created_order_id, request_status);
CREATE INDEX idx_v276_requests_source ON v276_requests (source_order_id);

CREATE TEMPORARY TABLE v276_details AS
SELECT 'LIVE' AS storage_type,
       order_detail_id,
       order_detail_order AS order_id,
       order_detail_product AS product_id,
       order_detail_amount AS amount,
       order_detail_price AS price
FROM order_details
UNION ALL
SELECT 'ARCHIVE' AS storage_type,
       archived.order_detail_id,
       archived.order_detail_order AS order_id,
       archived.order_detail_product AS product_id,
       archived.order_detail_amount AS amount,
       archived.order_detail_price AS price
FROM archive_order_details archived
WHERE NOT EXISTS (
    SELECT 1 FROM orders live WHERE live.order_id = archived.order_detail_order
);

CREATE INDEX idx_v276_details_order ON v276_details (order_id);
CREATE INDEX idx_v276_details_id ON v276_details (order_detail_id);

CREATE TEMPORARY TABLE v276_reviews AS
SELECT 'LIVE' AS storage_type,
       review_id,
       review_order_details AS order_detail_id,
       review_product AS product_id,
       review_price AS price
FROM reviews
UNION ALL
SELECT 'ARCHIVE' AS storage_type,
       archived.review_id,
       archived.review_order_details AS order_detail_id,
       archived.review_product AS product_id,
       archived.review_price AS price
FROM archive_reviews archived
JOIN archive_order_details archived_detail
  ON archived_detail.order_detail_id = archived.review_order_details
WHERE NOT EXISTS (
    SELECT 1 FROM orders live WHERE live.order_id = archived_detail.order_detail_order
);

CREATE INDEX idx_v276_reviews_detail ON v276_reviews (order_detail_id, review_id);

CREATE TEMPORARY TABLE v276_review_rows AS
SELECT detail.storage_type AS detail_storage_type,
       review.storage_type AS review_storage_type,
       detail.order_id,
       detail.order_detail_id,
       detail.product_id AS detail_product_id,
       detail.amount AS detail_amount,
       detail.price AS detail_price,
       review.review_id,
       review.product_id,
       review.price,
       ROW_NUMBER() OVER (
           PARTITION BY detail.order_id
           ORDER BY review.review_id
       ) AS review_no
FROM v276_details detail
JOIN v276_reviews review
  ON review.order_detail_id = detail.order_detail_id;

CREATE INDEX idx_v276_review_rows_order_no ON v276_review_rows (order_id, review_no);

-- MySQL temporary tables cannot be opened twice in one statement. Keep a
-- separate target snapshot for source/target positional comparisons.
CREATE TEMPORARY TABLE v276_target_review_rows AS
SELECT * FROM v276_review_rows;

CREATE INDEX idx_v276_target_review_rows_order_no
    ON v276_target_review_rows (order_id, review_no);

CREATE TEMPORARY TABLE v276_source_stats AS
SELECT request.next_order_request_id,
       request.source_order_id,
       request.created_order_id,
       request.created_at,
       COUNT(source_review.review_id) AS review_count,
       COUNT(DISTINCT source_review.product_id) AS product_count,
       COUNT(DISTINCT source_review.price) AS price_count,
       MIN(source_review.product_id) AS product_id,
       MIN(source_review.price) AS price,
       SUM(source_review.price) AS total
FROM v276_requests request
JOIN v276_review_rows source_review
  ON source_review.order_id = request.source_order_id
WHERE request.request_status = 'CREATED'
  AND request.created_order_id IS NOT NULL
GROUP BY request.next_order_request_id,
         request.source_order_id,
         request.created_order_id,
         request.created_at;

CREATE INDEX idx_v276_source_stats_request ON v276_source_stats (next_order_request_id);

CREATE TEMPORARY TABLE v276_created_stats AS
SELECT request.next_order_request_id,
       COUNT(created_review.review_id) AS review_count,
       COUNT(DISTINCT created_review.product_id) AS product_count,
       COUNT(DISTINCT created_review.price) AS price_count,
       MIN(created_review.product_id) AS product_id,
       MIN(created_review.price) AS price,
       SUM(created_review.price) AS total,
       COUNT(DISTINCT HEX(created_review.order_detail_id)) AS detail_count,
       MIN(created_review.detail_product_id) AS detail_product_id,
       MIN(created_review.detail_amount) AS detail_amount,
       MIN(created_review.detail_price) AS detail_price
FROM v276_requests request
JOIN v276_review_rows created_review
  ON created_review.order_id = request.created_order_id
WHERE request.request_status = 'CREATED'
  AND request.created_order_id IS NOT NULL
GROUP BY request.next_order_request_id;

CREATE INDEX idx_v276_created_stats_request ON v276_created_stats (next_order_request_id);

CREATE TEMPORARY TABLE v276_candidates AS
SELECT source.next_order_request_id,
       source.source_order_id,
       source.created_order_id,
       source.created_at,
       source.review_count,
       source.total AS new_order_total,
       created.total AS old_order_total
FROM v276_source_stats source
JOIN v276_created_stats created
  ON created.next_order_request_id = source.next_order_request_id
WHERE source.review_count = created.review_count
  AND created.detail_count = 1
  AND created.product_count = 1
  AND created.price_count = 1
  AND created.product_id <=> created.detail_product_id
  AND created.detail_amount = created.review_count
  AND created.detail_price = created.price * created.review_count
  AND (
      source.product_count <> 1
      OR source.price_count <> 1
      OR NOT (source.product_id <=> created.product_id)
      OR NOT (source.price <=> created.price)
  );

CREATE UNIQUE INDEX uk_v276_candidates_request ON v276_candidates (next_order_request_id);
CREATE UNIQUE INDEX uk_v276_candidates_order ON v276_candidates (created_order_id);

-- Audited production snapshot before status/payment filtering: 68 untouched
-- faulty repeats / 478 reviews, including 67 live and one archived order.
-- Clean databases have zero. This guard ensures the repair is never widened
-- silently if the historical shape is different on another installation.
CREATE TEMPORARY TABLE v276_preflight_guard (ok TINYINT NOT NULL);

INSERT INTO v276_preflight_guard (ok)
SELECT CASE
    WHEN COUNT(*) = 0 THEN 1
    WHEN COUNT(*) = 68
     AND COALESCE(SUM(candidate.review_count), 0) = 478
     AND SUM(EXISTS (
         SELECT 1 FROM orders live WHERE live.order_id = candidate.created_order_id
     )) = 67
    THEN 1
    ELSE NULL
END
FROM v276_candidates candidate;

-- Owner decision: paid and archived orders remain exactly as recorded. Repair
-- active, unpaid publication/payment cycles only. "На проверке" is included
-- because it is an active pre-publication order that would otherwise reach
-- the next invoice with the wrong amount.
CREATE TEMPORARY TABLE v276_active_candidates AS
SELECT candidate.*
FROM v276_candidates candidate
JOIN orders base_order
  ON base_order.order_id = candidate.created_order_id
JOIN order_statuses status_row
  ON status_row.order_status_id = base_order.order_status
WHERE status_row.order_status_title IN (
        'На проверке',
        'Коррекция',
        'Публикация',
        'Опубликовано',
        'Выставлен счет',
        'Напоминание',
        'Не оплачено',
        'Ожидает общего счета'
      )
  AND NOT EXISTS (
      SELECT 1
      FROM payment_links payment_link
      WHERE payment_link.order_id = base_order.order_id
        AND payment_link.status IN ('CONFIRMED', 'TEST_CONFIRMED')
  )
  AND NOT EXISTS (
      SELECT 1
      FROM common_invoice_orders invoice_order
      WHERE invoice_order.order_id = base_order.order_id
        AND invoice_order.paid = 1
  );

CREATE UNIQUE INDEX uk_v276_active_candidates_request
    ON v276_active_candidates (next_order_request_id);
CREATE UNIQUE INDEX uk_v276_active_candidates_order
    ON v276_active_candidates (created_order_id);

CREATE TEMPORARY TABLE v276_candidate_review_plan AS
SELECT candidate.next_order_request_id,
       candidate.source_order_id,
       candidate.created_order_id,
       target_review.review_storage_type AS storage_type,
       target_review.order_detail_id,
       target_review.review_id,
       target_review.product_id AS old_product_id,
       source_review.product_id AS new_product_id,
       target_review.price AS old_price,
       source_review.price AS new_price,
       'SOURCE_REVIEW_SNAPSHOT' AS repair_reason
FROM v276_active_candidates candidate
JOIN v276_review_rows source_review
  ON source_review.order_id = candidate.source_order_id
JOIN v276_target_review_rows target_review
  ON target_review.order_id = candidate.created_order_id
 AND target_review.review_no = source_review.review_no;

-- The remaining mismatches are edited after creation: their current review
-- set no longer has the untouched creation signature. De-duplicate repeated
-- request records by created order before applying the explicit owner rule.
CREATE TEMPORARY TABLE v276_sequence_mismatches AS
SELECT request.next_order_request_id,
       request.source_order_id,
       request.created_order_id
FROM v276_requests request
JOIN v276_review_rows source_review
  ON source_review.order_id = request.source_order_id
JOIN v276_target_review_rows target_review
  ON target_review.order_id = request.created_order_id
 AND target_review.review_no = source_review.review_no
WHERE request.request_status = 'CREATED'
  AND request.created_order_id IS NOT NULL
  AND (
      NOT (source_review.product_id <=> target_review.product_id)
      OR NOT (source_review.price <=> target_review.price)
  )
GROUP BY request.next_order_request_id,
         request.source_order_id,
         request.created_order_id;

CREATE INDEX idx_v276_sequence_mismatches_order
    ON v276_sequence_mismatches (created_order_id, next_order_request_id);

CREATE TEMPORARY TABLE v276_latest_mismatch_requests AS
SELECT created_order_id,
       MAX(next_order_request_id) AS next_order_request_id
FROM v276_sequence_mismatches
GROUP BY created_order_id;

CREATE UNIQUE INDEX uk_v276_latest_mismatch_requests_order
    ON v276_latest_mismatch_requests (created_order_id);

CREATE TEMPORARY TABLE v276_ambiguous_requests AS
SELECT mismatch.next_order_request_id,
       mismatch.source_order_id,
       mismatch.created_order_id
FROM v276_sequence_mismatches mismatch
JOIN v276_latest_mismatch_requests latest
  ON latest.created_order_id = mismatch.created_order_id
 AND latest.next_order_request_id = mismatch.next_order_request_id
WHERE NOT EXISTS (
    SELECT 1
    FROM v276_candidates candidate
    WHERE candidate.created_order_id = mismatch.created_order_id
);

CREATE UNIQUE INDEX uk_v276_ambiguous_requests_order
    ON v276_ambiguous_requests (created_order_id);

-- Owner-approved edited-order rule (2026-08-29): only a current
-- "Отзыв 2ГИС" snapshot (product 1, 200 RUB) becomes "Отзыв 2ГИС+"
-- (product 2, 250 RUB) when the same company had product 2 at 250 RUB in an
-- older live or archived order. Photo and every other product are untouched.
CREATE TEMPORARY TABLE v276_heuristic_review_plan AS
SELECT ambiguous.next_order_request_id,
       ambiguous.source_order_id,
       ambiguous.created_order_id,
       target_review.review_storage_type AS storage_type,
       target_review.order_detail_id,
       target_review.review_id,
       target_review.product_id AS old_product_id,
       2 AS new_product_id,
       target_review.price AS old_price,
       CAST(250.00 AS DECIMAL(10, 2)) AS new_price,
       'COMPANY_HAD_2GIS_PLUS_250' AS repair_reason
FROM v276_ambiguous_requests ambiguous
JOIN orders target_order
  ON target_order.order_id = ambiguous.created_order_id
JOIN order_statuses target_status
  ON target_status.order_status_id = target_order.order_status
JOIN v276_target_review_rows target_review
  ON target_review.order_id = ambiguous.created_order_id
WHERE target_status.order_status_title IN (
        'На проверке',
        'Коррекция',
        'Публикация',
        'Опубликовано',
        'Выставлен счет',
        'Напоминание',
        'Не оплачено',
        'Ожидает общего счета'
      )
  AND target_review.product_id = 1
  AND target_review.price = 200.00
  AND NOT EXISTS (
      SELECT 1
      FROM payment_links payment_link
      WHERE payment_link.order_id = target_order.order_id
        AND payment_link.status IN ('CONFIRMED', 'TEST_CONFIRMED')
  )
  AND NOT EXISTS (
      SELECT 1
      FROM common_invoice_orders invoice_order
      WHERE invoice_order.order_id = target_order.order_id
        AND invoice_order.paid = 1
  )
  AND (
      EXISTS (
          SELECT 1
          FROM orders older_order
          JOIN order_details older_detail
            ON older_detail.order_detail_order = older_order.order_id
          JOIN reviews older_review
            ON older_review.review_order_details = older_detail.order_detail_id
          WHERE older_order.order_company = target_order.order_company
            AND older_order.order_id < target_order.order_id
            AND older_review.review_product = 2
            AND older_review.review_price = 250.00
      )
      OR EXISTS (
          SELECT 1
          FROM archive_orders older_order
          JOIN archive_order_details older_detail
            ON older_detail.order_detail_order = older_order.order_id
          JOIN archive_reviews older_review
            ON older_review.review_order_details = older_detail.order_detail_id
          WHERE older_order.order_company = target_order.order_company
            AND older_order.order_id < target_order.order_id
            AND older_review.review_product = 2
            AND older_review.review_price = 250.00
            AND NOT EXISTS (
                SELECT 1
                FROM orders live_order
                WHERE live_order.order_id = older_order.order_id
            )
      )
  );

INSERT INTO v276_preflight_guard (ok)
SELECT CASE
    WHEN COUNT(*) = 0 THEN 1
    WHEN COUNT(*) <= 4
     AND COUNT(DISTINCT created_order_id) = 1
    THEN 1
    ELSE NULL
END
FROM v276_heuristic_review_plan;

CREATE TEMPORARY TABLE v276_review_plan AS
SELECT * FROM v276_candidate_review_plan
UNION ALL
SELECT * FROM v276_heuristic_review_plan;

CREATE UNIQUE INDEX uk_v276_review_plan_review ON v276_review_plan (review_id);
CREATE INDEX idx_v276_review_plan_order ON v276_review_plan (created_order_id);

INSERT INTO v276_preflight_guard (ok)
SELECT CASE
    WHEN COUNT(*) = 0 THEN 1
    WHEN COUNT(DISTINCT created_order_id) <= 69
     AND SUM(new_product_id IS NULL OR new_price IS NULL) = 0
    THEN 1
    ELSE NULL
END
FROM v276_review_plan;

INSERT INTO auto_repeat_review_term_repairs (
    repair_key,
    next_order_request_id,
    source_order_id,
    created_order_id,
    review_id,
    storage_type,
    old_product_id,
    new_product_id,
    old_price,
    new_price,
    actor,
    reason
)
SELECT CONCAT(
           'V276:REQUEST:', plan.next_order_request_id,
           ':REVIEW:', plan.review_id
       ),
       plan.next_order_request_id,
       plan.source_order_id,
       plan.created_order_id,
       plan.review_id,
       plan.storage_type,
       plan.old_product_id,
       plan.new_product_id,
       plan.old_price,
       plan.new_price,
       'owner:hunt',
       CASE plan.repair_reason
           WHEN 'SOURCE_REVIEW_SNAPSHOT'
               THEN 'Автоповтор восстановлен по фактическому продукту и сохраненной цене соответствующего отзыва предыдущего заказа'
           ELSE 'По решению владельца Отзыв 2ГИС 200 ₽ заменен на Отзыв 2ГИС+ 250 ₽: у компании подтверждена такая цена в старом заказе'
       END
FROM v276_review_plan plan
WHERE NOT (plan.old_product_id <=> plan.new_product_id)
   OR NOT (plan.old_price <=> plan.new_price);

UPDATE reviews review
JOIN v276_review_plan plan
  ON plan.storage_type = 'LIVE'
 AND plan.review_id = review.review_id
SET review.review_product = plan.new_product_id,
    review.review_price = plan.new_price,
    review.row_version = review.row_version + 1
WHERE NOT (review.review_product <=> plan.new_product_id)
   OR NOT (review.review_price <=> plan.new_price);

UPDATE archive_reviews review
JOIN v276_review_plan plan
  ON plan.storage_type = 'ARCHIVE'
 AND plan.review_id = review.review_id
SET review.review_product = plan.new_product_id,
    review.review_price = plan.new_price
WHERE NOT (review.review_product <=> plan.new_product_id)
   OR NOT (review.review_price <=> plan.new_price);

-- Apply planned terms to the target snapshot too, then total every current
-- review of a repaired order. This is essential for edited orders where only
-- some 2GIS cards change and photo/other products must remain in the total.
UPDATE v276_target_review_rows target_review
JOIN v276_review_plan plan
  ON plan.review_id = target_review.review_id
SET target_review.product_id = plan.new_product_id,
    target_review.price = plan.new_price;

CREATE TEMPORARY TABLE v276_repair_orders AS
SELECT plan.next_order_request_id,
       plan.source_order_id,
       plan.created_order_id,
       MAX(plan.repair_reason) AS repair_reason
FROM v276_review_plan plan
GROUP BY plan.next_order_request_id,
         plan.source_order_id,
         plan.created_order_id;

CREATE UNIQUE INDEX uk_v276_repair_orders_order
    ON v276_repair_orders (created_order_id);

CREATE TEMPORARY TABLE v276_target_detail_totals AS
SELECT target_review.review_storage_type AS storage_type,
       target_review.order_detail_id,
       target_review.order_id AS created_order_id,
       COUNT(*) AS amount,
       SUM(target_review.price) AS price,
       COUNT(DISTINCT target_review.product_id) AS product_count,
       MIN(target_review.product_id) AS product_id
FROM v276_target_review_rows target_review
JOIN v276_repair_orders repaired
  ON repaired.created_order_id = target_review.order_id
GROUP BY target_review.review_storage_type,
         target_review.order_detail_id,
         target_review.order_id;

CREATE UNIQUE INDEX uk_v276_target_detail_totals
    ON v276_target_detail_totals (order_detail_id);

UPDATE order_details detail
JOIN v276_target_detail_totals total
  ON total.storage_type = 'LIVE'
 AND total.order_detail_id = detail.order_detail_id
SET detail.order_detail_amount = total.amount,
    detail.order_detail_price = total.price,
    detail.order_detail_product = CASE
        WHEN total.product_count = 1 THEN total.product_id
        ELSE detail.order_detail_product
    END,
    detail.row_version = detail.row_version + 1;

UPDATE archive_order_details detail
JOIN v276_target_detail_totals total
  ON total.storage_type = 'ARCHIVE'
 AND total.order_detail_id = detail.order_detail_id
SET detail.order_detail_amount = total.amount,
    detail.order_detail_price = total.price,
    detail.order_detail_product = CASE
        WHEN total.product_count = 1 THEN total.product_id
        ELSE detail.order_detail_product
    END;

CREATE TEMPORARY TABLE v276_target_order_totals AS
SELECT created_order_id,
       SUM(amount) AS amount,
       SUM(price) AS price
FROM v276_target_detail_totals
GROUP BY created_order_id;

CREATE UNIQUE INDEX uk_v276_target_order_totals
    ON v276_target_order_totals (created_order_id);

CREATE TEMPORARY TABLE v276_order_changes AS
SELECT repaired.next_order_request_id,
       repaired.source_order_id,
       repaired.created_order_id,
       repaired.repair_reason,
       base_order.order_amount AS old_amount,
       base_order.order_sum AS old_price,
       total.amount AS new_amount,
       total.price AS new_price
FROM v276_repair_orders repaired
JOIN orders base_order
  ON base_order.order_id = repaired.created_order_id
JOIN v276_target_order_totals total
  ON total.created_order_id = repaired.created_order_id;

CREATE UNIQUE INDEX uk_v276_order_changes_order
    ON v276_order_changes (created_order_id);

UPDATE orders base_order
JOIN v276_target_order_totals total
  ON total.created_order_id = base_order.order_id
SET base_order.order_amount = total.amount,
    base_order.order_sum = total.price,
    base_order.row_version = base_order.row_version + 1;

UPDATE archive_orders base_order
JOIN v276_target_order_totals total
  ON total.created_order_id = base_order.order_id
SET base_order.order_amount = total.amount,
    base_order.order_sum = total.price
WHERE NOT EXISTS (
    SELECT 1 FROM orders live WHERE live.order_id = base_order.order_id
);

INSERT INTO business_audit_events (
    created_at, actor, source, action, entity_type, entity_id,
    order_id, old_value, new_value, details
)
SELECT CURRENT_TIMESTAMP(6),
       'owner:hunt',
       'V1_10_276_auto_repeat_repair',
       'AUTO_REPEAT_ACTIVE_ORDER_RESTORED',
       'ORDER',
       CAST(change_row.created_order_id AS CHAR),
       change_row.created_order_id,
       CONCAT(
           'amount=', change_row.old_amount,
           ';total=', change_row.old_price
       ),
       CONCAT(
           'amount=', change_row.new_amount,
           ';total=', change_row.new_price
       ),
       CONCAT(
           'next_order_request=', change_row.next_order_request_id,
           ';source_order=', change_row.source_order_id,
           ';rule=', change_row.repair_reason,
           ';paid_history_untouched=1'
       )
FROM v276_order_changes change_row;

-- Refresh unpaid common invoices when no payment evidence or provider payment
-- session exists. This includes the already-sent Bочкарёв public invoice: the
-- stable public token remains valid, while its live payable and selected route
-- change from 1,900 to 2,250 RUB. Financially unsafe cycles are quarantined.
CREATE TEMPORARY TABLE v276_affected_common_invoices AS
SELECT DISTINCT invoice_order.invoice_id
FROM common_invoice_orders invoice_order
JOIN v276_target_order_totals repaired
  ON repaired.created_order_id = invoice_order.order_id
WHERE invoice_order.active_membership = 1
  AND invoice_order.paid = 0;

CREATE UNIQUE INDEX uk_v276_affected_common_invoices
    ON v276_affected_common_invoices (invoice_id);

CREATE TEMPORARY TABLE v276_safe_common_invoices AS
SELECT invoice.invoice_id,
       invoice.amount_kopecks AS old_amount_kopecks
FROM v276_affected_common_invoices affected
JOIN common_invoices invoice
  ON invoice.invoice_id = affected.invoice_id
LEFT JOIN contractor_payment_allocations allocation
  ON allocation.id = invoice.contractor_allocation_id
WHERE invoice.status NOT IN ('PAID', 'BAN', 'DISABLED', 'ARCHIVED')
  AND invoice.paid_kopecks = 0
  AND invoice.paid_at IS NULL
  AND invoice.client_reported_at IS NULL
  AND invoice.manual_confirmed_at IS NULL
  AND invoice.tbank_order_id IS NULL
  AND invoice.tbank_payment_id IS NULL
  AND invoice.payment_url IS NULL
  AND NOT EXISTS (
      SELECT 1
      FROM common_invoice_orders paid_item
      WHERE paid_item.invoice_id = invoice.invoice_id
        AND paid_item.paid = 1
  )
  AND NOT EXISTS (
      SELECT 1
      FROM common_invoice_payment_refs payment_ref
      WHERE payment_ref.invoice_id = invoice.invoice_id
        AND payment_ref.status NOT IN ('ARCHIVED', 'CANCELED', 'CANCELLED', 'FAILED', 'REVERSED')
  )
  AND (
      allocation.id IS NULL
      OR (
          allocation.confirmed_kopecks = 0
          AND allocation.returned_kopecks = 0
          AND allocation.client_reported_at IS NULL
          AND allocation.confirmed_at IS NULL
      )
  );

CREATE UNIQUE INDEX uk_v276_safe_common_invoices
    ON v276_safe_common_invoices (invoice_id);

UPDATE common_invoice_orders invoice_order
JOIN v276_target_order_totals total
  ON total.created_order_id = invoice_order.order_id
JOIN v276_safe_common_invoices safe_invoice
  ON safe_invoice.invoice_id = invoice_order.invoice_id
SET invoice_order.amount_kopecks = CAST(ROUND(total.price * 100) AS SIGNED),
    invoice_order.updated_at = CURRENT_TIMESTAMP(6)
WHERE invoice_order.active_membership = 1
  AND invoice_order.paid = 0;

CREATE TEMPORARY TABLE v276_safe_common_totals AS
SELECT safe_invoice.invoice_id,
       safe_invoice.old_amount_kopecks,
       SUM(invoice_order.amount_kopecks) AS new_amount_kopecks
FROM v276_safe_common_invoices safe_invoice
JOIN common_invoice_orders invoice_order
  ON invoice_order.invoice_id = safe_invoice.invoice_id
 AND invoice_order.active_membership = 1
GROUP BY safe_invoice.invoice_id,
         safe_invoice.old_amount_kopecks;

CREATE UNIQUE INDEX uk_v276_safe_common_totals
    ON v276_safe_common_totals (invoice_id);

UPDATE contractor_payment_allocations allocation
JOIN common_invoices invoice
  ON invoice.contractor_allocation_id = allocation.id
JOIN v276_safe_common_totals total
  ON total.invoice_id = invoice.invoice_id
SET allocation.amount_kopecks = total.new_amount_kopecks,
    allocation.row_version = allocation.row_version + 1,
    allocation.updated_at = CURRENT_TIMESTAMP(6)
WHERE allocation.confirmed_kopecks = 0
  AND allocation.returned_kopecks = 0
  AND allocation.client_reported_at IS NULL
  AND allocation.confirmed_at IS NULL;

UPDATE common_invoices invoice
JOIN v276_safe_common_totals total
  ON total.invoice_id = invoice.invoice_id
SET invoice.amount_kopecks = total.new_amount_kopecks,
    invoice.payment_route_amount_kopecks = CASE
        WHEN invoice.payment_route_amount_kopecks IS NULL THEN NULL
        ELSE total.new_amount_kopecks
    END,
    invoice.shadow_route_amount_kopecks = CASE
        WHEN invoice.shadow_route_amount_kopecks IS NULL THEN NULL
        ELSE total.new_amount_kopecks
    END,
    invoice.updated_at = CURRENT_TIMESTAMP(6);

INSERT INTO business_audit_events (
    created_at, actor, source, action, entity_type, entity_id,
    order_id, old_value, new_value, details
)
SELECT CURRENT_TIMESTAMP(6),
       'owner:hunt',
       'V1_10_276_auto_repeat_repair',
       'UNPAID_COMMON_INVOICE_AMOUNT_REFRESHED',
       'COMMON_INVOICE',
       CAST(total.invoice_id AS CHAR),
       NULL,
       CONCAT('amount_kopecks=', total.old_amount_kopecks),
       CONCAT('amount_kopecks=', total.new_amount_kopecks),
       'Payment evidence absent; public token preserved; selected route amount refreshed explicitly by owner decision'
FROM v276_safe_common_totals total
WHERE total.old_amount_kopecks <> total.new_amount_kopecks;

UPDATE common_invoices invoice
JOIN v276_affected_common_invoices affected
  ON affected.invoice_id = invoice.invoice_id
SET invoice.previous_status = invoice.status,
    invoice.next_reminder_at = NULL,
    invoice.last_error = CONCAT(
        'payable_change_requires_reissue: auto_repeat_terms_repaired;',
        'source_invoice=', invoice.invoice_id,
        ';previous=', invoice.status
    ),
    invoice.status = 'NEEDS_ATTENTION',
    invoice.updated_at = CURRENT_TIMESTAMP(6)
WHERE NOT EXISTS (
    SELECT 1
    FROM v276_safe_common_invoices safe_invoice
    WHERE safe_invoice.invoice_id = invoice.invoice_id
)
  AND invoice.status NOT IN ('PAID', 'BAN', 'DISABLED', 'ARCHIVED');

-- Refresh an existing unpaid standalone link only when no bank/manual payment
-- evidence exists. Historical expired links remain untouched; the current
-- waiting link keeps its token and recipient but receives the corrected sum.
CREATE TEMPORARY TABLE v276_safe_payment_links AS
SELECT payment_link.id AS payment_link_id,
       payment_link.order_id,
       payment_link.amount_kopecks AS old_amount_kopecks,
       CAST(ROUND(repaired.price * 100) AS SIGNED) AS new_amount_kopecks
FROM payment_links payment_link
JOIN v276_target_order_totals repaired
  ON repaired.created_order_id = payment_link.order_id
WHERE payment_link.status IN ('CREATED', 'WAITING_MANUAL_PAYMENT')
  AND payment_link.paid_at IS NULL
  AND payment_link.initiated_at IS NULL
  AND payment_link.manual_reported_at IS NULL
  AND payment_link.manual_confirmed_at IS NULL
  AND payment_link.confirmed_amount_kopecks IS NULL
  AND payment_link.tbank_order_id IS NULL
  AND payment_link.tbank_payment_id IS NULL
  AND payment_link.payment_url IS NULL
  AND payment_link.offer_consent_at IS NULL
  AND payment_link.privacy_consent_at IS NULL
  AND payment_link.receipt_consent_at IS NULL
  AND NOT EXISTS (
      SELECT 1
      FROM contractor_payment_allocations allocation
      WHERE (
              allocation.id = payment_link.contractor_allocation_id
              OR (
                  allocation.source_type = 'PAYMENT_LINK'
                  AND allocation.source_id = payment_link.id
              )
            )
        AND (
            allocation.confirmed_kopecks <> 0
            OR allocation.returned_kopecks <> 0
            OR allocation.client_reported_at IS NOT NULL
            OR allocation.confirmed_at IS NOT NULL
        )
  );

CREATE UNIQUE INDEX uk_v276_safe_payment_links
    ON v276_safe_payment_links (payment_link_id);

UPDATE contractor_payment_allocations allocation
JOIN payment_links payment_link
  ON payment_link.contractor_allocation_id = allocation.id
JOIN v276_safe_payment_links safe_link
  ON safe_link.payment_link_id = payment_link.id
SET allocation.amount_kopecks = safe_link.new_amount_kopecks,
    allocation.row_version = allocation.row_version + 1,
    allocation.updated_at = CURRENT_TIMESTAMP(6)
WHERE allocation.confirmed_kopecks = 0
  AND allocation.returned_kopecks = 0
  AND allocation.client_reported_at IS NULL
  AND allocation.confirmed_at IS NULL;

UPDATE contractor_payment_allocations allocation
JOIN v276_safe_payment_links safe_link
  ON allocation.source_type = 'PAYMENT_LINK'
 AND allocation.source_id = safe_link.payment_link_id
SET allocation.amount_kopecks = safe_link.new_amount_kopecks,
    allocation.row_version = allocation.row_version + 1,
    allocation.updated_at = CURRENT_TIMESTAMP(6)
WHERE allocation.confirmed_kopecks = 0
  AND allocation.returned_kopecks = 0
  AND allocation.client_reported_at IS NULL
  AND allocation.confirmed_at IS NULL;

UPDATE payment_links payment_link
JOIN v276_safe_payment_links safe_link
  ON safe_link.payment_link_id = payment_link.id
SET payment_link.amount_kopecks = safe_link.new_amount_kopecks,
    payment_link.reserved_amount_kopecks = CASE
        WHEN payment_link.reserved_amount_kopecks IS NULL THEN NULL
        ELSE safe_link.new_amount_kopecks
    END,
    payment_link.shadow_route_amount_kopecks = CASE
        WHEN payment_link.shadow_route_amount_kopecks IS NULL THEN NULL
        ELSE safe_link.new_amount_kopecks
    END,
    payment_link.row_version = payment_link.row_version + 1,
    payment_link.updated_at = CURRENT_TIMESTAMP(6);

INSERT INTO business_audit_events (
    created_at, actor, source, action, entity_type, entity_id,
    order_id, old_value, new_value, details
)
SELECT CURRENT_TIMESTAMP(6),
       'owner:hunt',
       'V1_10_276_auto_repeat_repair',
       'UNPAID_PAYMENT_LINK_AMOUNT_REFRESHED',
       'PAYMENT_LINK',
       CAST(safe_link.payment_link_id AS CHAR),
       safe_link.order_id,
       CONCAT('amount_kopecks=', safe_link.old_amount_kopecks),
       CONCAT('amount_kopecks=', safe_link.new_amount_kopecks),
       'Payment evidence absent; link token and recipient preserved; route amount refreshed explicitly by owner decision'
FROM v276_safe_payment_links safe_link
WHERE safe_link.old_amount_kopecks <> safe_link.new_amount_kopecks;

-- Evidence-bearing open links cannot be rewritten. Stop only those exceptional
-- cases for explicit reissue; the known waiting link is handled above.
UPDATE payment_links payment_link
JOIN v276_target_order_totals repaired
  ON repaired.created_order_id = payment_link.order_id
SET payment_link.status = 'NEEDS_RECONCILIATION',
    payment_link.last_error = CONCAT(
        'auto_repeat_terms_repaired;old_amount=', payment_link.amount_kopecks,
        ';new_amount=', CAST(ROUND(repaired.price * 100) AS SIGNED),
        ';requires_reissue=1'
    ),
    payment_link.expires_at = LEAST(payment_link.expires_at, CURRENT_TIMESTAMP(6)),
    payment_link.row_version = payment_link.row_version + 1,
    payment_link.updated_at = CURRENT_TIMESTAMP(6)
WHERE payment_link.status IN (
    'CREATED',
    'INITIATED',
    'AUTHORIZED',
    'WAITING_MANUAL_PAYMENT',
    'MANUAL_REPORTED'
)
  AND NOT EXISTS (
      SELECT 1
      FROM v276_safe_payment_links safe_link
      WHERE safe_link.payment_link_id = payment_link.id
  )
  AND payment_link.amount_kopecks <> CAST(ROUND(repaired.price * 100) AS SIGNED);

DROP TEMPORARY TABLE v276_safe_payment_links;
DROP TEMPORARY TABLE v276_safe_common_totals;
DROP TEMPORARY TABLE v276_safe_common_invoices;
DROP TEMPORARY TABLE v276_affected_common_invoices;
DROP TEMPORARY TABLE v276_order_changes;
DROP TEMPORARY TABLE v276_target_order_totals;
DROP TEMPORARY TABLE v276_target_detail_totals;
DROP TEMPORARY TABLE v276_repair_orders;
DROP TEMPORARY TABLE v276_review_plan;
DROP TEMPORARY TABLE v276_heuristic_review_plan;
DROP TEMPORARY TABLE v276_ambiguous_requests;
DROP TEMPORARY TABLE v276_latest_mismatch_requests;
DROP TEMPORARY TABLE v276_sequence_mismatches;
DROP TEMPORARY TABLE v276_candidate_review_plan;
DROP TEMPORARY TABLE v276_active_candidates;
DROP TEMPORARY TABLE v276_candidates;
DROP TEMPORARY TABLE v276_created_stats;
DROP TEMPORARY TABLE v276_source_stats;
DROP TEMPORARY TABLE v276_target_review_rows;
DROP TEMPORARY TABLE v276_review_rows;
DROP TEMPORARY TABLE v276_reviews;
DROP TEMPORARY TABLE v276_details;
DROP TEMPORARY TABLE v276_requests;
DROP TEMPORARY TABLE v276_preflight_guard;
