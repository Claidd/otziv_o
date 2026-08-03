-- These indexes serve mutable live boards (waiting/client/worker publication
-- queues). Runtime archive queries use the archive PK, batch/restore indexes,
-- manager/status indexes and company/latest-order index instead.
SET @drop_indexes = (
    SELECT GROUP_CONCAT(
        DISTINCT CONCAT('DROP INDEX `', index_name, '`')
        ORDER BY index_name SEPARATOR ', '
    )
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'archive_orders'
      AND index_name IN (
          'idx_orders_manager_complete_changed_status',
          'idx_orders_manager_status_waiting_changed',
          'idx_orders_manager_waiting_status',
          'idx_orders_status_worker_waiting_changed',
          'idx_orders_waiting_for_client',
          'idx_orders_worker_changed',
          'idx_orders_worker_complete_changed_status',
          'idx_orders_worker_status_changed',
          'idx_orders_worker_status_waiting_changed',
          'idx_orders_worker_waiting_changed',
          'idx_orders_worker_waiting_status_changed'
      )
);
SET @sql = IF(@drop_indexes IS NULL,
    'SELECT ''archive_orders live-workflow indexes already absent''',
    CONCAT('ALTER TABLE archive_orders ', @drop_indexes, ', ALGORITHM=INPLACE, LOCK=NONE'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- archive_reviews is queried by primary key, order_details and filial. The
-- following wide indexes are copies of live publication/worker dashboards and
-- have no predicate consumer in the archive repositories.
SET @drop_indexes = (
    SELECT GROUP_CONCAT(
        DISTINCT CONCAT('DROP INDEX `', index_name, '`')
        ORDER BY index_name SEPARATOR ', '
    )
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'archive_reviews'
      AND index_name IN (
          'idx_reviews_filial_publish_bot',
          'idx_reviews_filial_publish_details',
          'idx_reviews_order_details_worker',
          'idx_reviews_publish_date',
          'idx_reviews_publish_date_details_bot',
          'idx_reviews_publish_date_worker_bot',
          'idx_reviews_publish_filial_bot',
          'idx_reviews_publish_filial_details',
          'idx_reviews_publish_order_details',
          'idx_reviews_publish_vigul_date',
          'idx_reviews_publish_vigul_date_bot_worker',
          'idx_reviews_text_hash_id',
          'idx_reviews_worker_metrics',
          'idx_reviews_worker_order_details',
          'idx_reviews_worker_publish_date',
          'idx_reviews_worker_publish_vigul_date',
          'idx_reviews_worker_publish_vigul_date_bot'
      )
);
SET @sql = IF(@drop_indexes IS NULL,
    'SELECT ''archive_reviews live-workflow indexes already absent''',
    CONCAT('ALTER TABLE archive_reviews ', @drop_indexes, ', ALGORITHM=INPLACE, LOCK=NONE'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
