UPDATE reviews r
JOIN order_details od ON od.order_detail_id = r.review_order_details
JOIN orders o ON o.order_id = od.order_detail_order
SET r.review_worker = o.order_worker
WHERE COALESCE(r.review_publish, 0) = 0
  AND COALESCE(o.order_complete, 0) = 0
  AND o.order_worker IS NOT NULL
  AND (r.review_worker IS NULL OR r.review_worker <> o.order_worker);

UPDATE bad_review_tasks brt
JOIN orders o ON o.order_id = brt.bad_review_task_order
SET brt.bad_review_task_worker = o.order_worker
WHERE brt.bad_review_task_status = 'NEW'
  AND COALESCE(o.order_complete, 0) = 0
  AND o.order_worker IS NOT NULL
  AND (brt.bad_review_task_worker IS NULL OR brt.bad_review_task_worker <> o.order_worker);
