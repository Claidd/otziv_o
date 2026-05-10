SELECT installed_rank, version, description, success, installed_on
FROM flyway_schema_history
WHERE version IN ('1.3.3', '1.5.3', '1.5.9')
ORDER BY installed_rank;

SELECT table_name, index_name, GROUP_CONCAT(column_name ORDER BY seq_in_index) AS columns
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name IN ('orders', 'reviews', 'bots', 'companies', 'zp', 'payment_check')
  AND index_name IN (
      'idx_orders_worker_waiting_status_changed',
      'idx_orders_status_worker_waiting_changed',
      'idx_orders_manager_complete_changed_status',
      'idx_orders_worker_complete_changed_status',
      'idx_orders_status_payday_changed',
      'idx_reviews_worker_publish_vigul_date_bot',
      'idx_reviews_publish_vigul_date_bot_worker',
      'idx_reviews_worker_order_details',
      'idx_reviews_order_details_worker',
      'idx_reviews_filial_publish_bot',
      'idx_bots_active_status',
      'idx_bots_active_id',
      'idx_companies_update_status',
      'idx_companies_manager_update_status',
      'idx_zp_user_date',
      'idx_zp_date_user_totals',
      'idx_zp_order_date',
      'idx_payment_check_manager_date',
      'idx_payment_check_order_date'
  )
GROUP BY table_name, index_name
ORDER BY table_name, index_name;

SELECT table_name
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name IN (
      'archive_batches',
      'archive_orders',
      'archive_order_details',
      'archive_reviews',
      'archive_bad_review_tasks',
      'archive_next_order_requests',
      'archive_zp',
      'archive_payment_check'
  )
ORDER BY table_name;
