INSERT INTO order_statuses (order_status_title)
SELECT 'Бан'
WHERE NOT EXISTS (
    SELECT 1
    FROM order_statuses
    WHERE order_status_title = 'Бан'
);
