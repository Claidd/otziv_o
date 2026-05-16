CREATE TABLE IF NOT EXISTS review_recovery_batches (
    review_recovery_batch_id BIGINT NOT NULL AUTO_INCREMENT,
    review_recovery_batch_order BIGINT NOT NULL,
    review_recovery_batch_manager BIGINT NULL,
    review_recovery_batch_status VARCHAR(32) NOT NULL,
    review_recovery_batch_created_by BIGINT NULL,
    review_recovery_batch_client_notified_by BIGINT NULL,
    review_recovery_batch_completed_at DATETIME(6) NULL,
    review_recovery_batch_client_notified_at DATETIME(6) NULL,
    review_recovery_batch_archived_at DATETIME(6) NULL,
    review_recovery_batch_created_at DATETIME(6) NOT NULL,
    review_recovery_batch_updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (review_recovery_batch_id),
    INDEX idx_review_recovery_batches_order_status (review_recovery_batch_order, review_recovery_batch_status),
    INDEX idx_review_recovery_batches_manager_status (review_recovery_batch_manager, review_recovery_batch_status),
    INDEX idx_review_recovery_batches_completed (review_recovery_batch_status, review_recovery_batch_completed_at),
    INDEX idx_review_recovery_batches_client_notified (review_recovery_batch_status, review_recovery_batch_client_notified_at),
    CONSTRAINT fk_review_recovery_batches_order
        FOREIGN KEY (review_recovery_batch_order) REFERENCES orders (order_id)
        ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT fk_review_recovery_batches_manager
        FOREIGN KEY (review_recovery_batch_manager) REFERENCES managers (manager_id)
        ON DELETE SET NULL ON UPDATE CASCADE,
    CONSTRAINT fk_review_recovery_batches_created_by
        FOREIGN KEY (review_recovery_batch_created_by) REFERENCES users (id)
        ON DELETE SET NULL ON UPDATE CASCADE,
    CONSTRAINT fk_review_recovery_batches_notified_by
        FOREIGN KEY (review_recovery_batch_client_notified_by) REFERENCES users (id)
        ON DELETE SET NULL ON UPDATE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS review_recovery_tasks (
    review_recovery_task_id BIGINT NOT NULL AUTO_INCREMENT,
    review_recovery_task_batch BIGINT NOT NULL,
    review_recovery_task_order BIGINT NOT NULL,
    review_recovery_task_review BIGINT NOT NULL,
    review_recovery_task_worker BIGINT NULL,
    review_recovery_task_manager BIGINT NULL,
    review_recovery_task_bot BIGINT NULL,
    review_recovery_task_status VARCHAR(32) NOT NULL,
    review_recovery_task_original_text LONGTEXT NULL,
    review_recovery_task_recovery_text LONGTEXT NOT NULL,
    review_recovery_task_original_answer LONGTEXT NULL,
    review_recovery_task_recovery_answer LONGTEXT NULL,
    review_recovery_task_bot_login_snapshot VARCHAR(255) NULL,
    review_recovery_task_bot_password_snapshot VARCHAR(255) NULL,
    review_recovery_task_bot_fio_snapshot VARCHAR(255) NULL,
    review_recovery_task_scheduled_date DATE NOT NULL,
    review_recovery_task_completed_date DATE NULL,
    review_recovery_task_created_by BIGINT NULL,
    review_recovery_task_completed_by BIGINT NULL,
    review_recovery_task_created_at DATETIME(6) NOT NULL,
    review_recovery_task_updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (review_recovery_task_id),
    INDEX idx_review_recovery_tasks_batch_status (review_recovery_task_batch, review_recovery_task_status),
    INDEX idx_review_recovery_tasks_order_status_date (review_recovery_task_order, review_recovery_task_status, review_recovery_task_scheduled_date),
    INDEX idx_review_recovery_tasks_worker_status_date (review_recovery_task_worker, review_recovery_task_status, review_recovery_task_scheduled_date),
    INDEX idx_review_recovery_tasks_manager_status_date (review_recovery_task_manager, review_recovery_task_status, review_recovery_task_scheduled_date),
    INDEX idx_review_recovery_tasks_review_status (review_recovery_task_review, review_recovery_task_status),
    CONSTRAINT fk_review_recovery_tasks_batch
        FOREIGN KEY (review_recovery_task_batch) REFERENCES review_recovery_batches (review_recovery_batch_id)
        ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT fk_review_recovery_tasks_order
        FOREIGN KEY (review_recovery_task_order) REFERENCES orders (order_id)
        ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT fk_review_recovery_tasks_review
        FOREIGN KEY (review_recovery_task_review) REFERENCES reviews (review_id)
        ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT fk_review_recovery_tasks_worker
        FOREIGN KEY (review_recovery_task_worker) REFERENCES workers (worker_id)
        ON DELETE SET NULL ON UPDATE CASCADE,
    CONSTRAINT fk_review_recovery_tasks_manager
        FOREIGN KEY (review_recovery_task_manager) REFERENCES managers (manager_id)
        ON DELETE SET NULL ON UPDATE CASCADE,
    CONSTRAINT fk_review_recovery_tasks_bot
        FOREIGN KEY (review_recovery_task_bot) REFERENCES bots (bot_id)
        ON DELETE SET NULL ON UPDATE CASCADE,
    CONSTRAINT fk_review_recovery_tasks_created_by
        FOREIGN KEY (review_recovery_task_created_by) REFERENCES users (id)
        ON DELETE SET NULL ON UPDATE CASCADE,
    CONSTRAINT fk_review_recovery_tasks_completed_by
        FOREIGN KEY (review_recovery_task_completed_by) REFERENCES users (id)
        ON DELETE SET NULL ON UPDATE CASCADE
) ENGINE=InnoDB;
