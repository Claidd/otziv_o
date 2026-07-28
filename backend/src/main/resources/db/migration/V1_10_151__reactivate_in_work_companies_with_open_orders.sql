INSERT INTO business_audit_events (
    created_at,
    actor,
    source,
    action,
    entity_type,
    entity_id,
    old_value,
    new_value,
    details
)
SELECT
    NOW(6),
    'system',
    'flyway',
    'COMPANY_ACTIVE_REALIGNED',
    'COMPANY',
    CAST(company.company_id AS CHAR),
    '0',
    '1',
    'Компания со статусом «В работе» и незавершёнными заказами возвращена в активное состояние'
FROM companies company
JOIN company_status status
  ON status.company_status_id = company.company_status
WHERE COALESCE(company.company_active, 0) = 0
  AND status.status_title = 'В работе'
  AND EXISTS (
      SELECT 1
      FROM orders
      WHERE orders.order_company = company.company_id
        AND COALESCE(orders.order_complete, 0) = 0
  );

UPDATE companies company
JOIN company_status status
  ON status.company_status_id = company.company_status
SET company.company_active = 1
WHERE COALESCE(company.company_active, 0) = 0
  AND status.status_title = 'В работе'
  AND EXISTS (
      SELECT 1
      FROM orders
      WHERE orders.order_company = company.company_id
        AND COALESCE(orders.order_complete, 0) = 0
  );
