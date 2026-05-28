UPDATE next_order_requests
SET request_status = 'CANCELED',
    error_message = COALESCE(NULLIF(error_message, ''), 'Auto-created order was deleted; request canceled'),
    updated_at = CURRENT_TIMESTAMP(6)
WHERE request_status = 'CREATED'
  AND created_order_id IS NULL;
