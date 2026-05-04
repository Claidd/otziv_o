CREATE TABLE IF NOT EXISTS bad_review_tasks (
  bad_review_task_id BIGINT NOT NULL AUTO_INCREMENT,
  bad_review_task_order BIGINT NOT NULL,
  bad_review_task_review BIGINT NOT NULL,
  bad_review_task_worker BIGINT NULL,
  bad_review_task_bot BIGINT NULL,
  bad_review_task_status VARCHAR(30) NOT NULL DEFAULT 'NEW',
  bad_review_task_original_rating INT NULL DEFAULT 5,
  bad_review_task_target_rating INT NULL DEFAULT 2,
  bad_review_task_price NUMERIC(10,2) NULL DEFAULT 0,
  bad_review_task_scheduled_date DATE NULL,
  bad_review_task_completed_date DATE NULL,
  bad_review_task_created DATE NULL,
  bad_review_task_changed DATE NULL,
  bad_review_task_comment VARCHAR(2000) NULL,
  PRIMARY KEY (bad_review_task_id),
  INDEX idx_bad_review_tasks_order (bad_review_task_order),
  INDEX idx_bad_review_tasks_review (bad_review_task_review),
  INDEX idx_bad_review_tasks_worker_status_date (bad_review_task_worker, bad_review_task_status, bad_review_task_scheduled_date),
  INDEX idx_bad_review_tasks_status_date (bad_review_task_status, bad_review_task_scheduled_date),
  CONSTRAINT fk_bad_review_tasks_order
    FOREIGN KEY (bad_review_task_order)
    REFERENCES orders (order_id)
    ON DELETE CASCADE
    ON UPDATE CASCADE,
  CONSTRAINT fk_bad_review_tasks_review
    FOREIGN KEY (bad_review_task_review)
    REFERENCES reviews (review_id)
    ON DELETE CASCADE
    ON UPDATE CASCADE,
  CONSTRAINT fk_bad_review_tasks_worker
    FOREIGN KEY (bad_review_task_worker)
    REFERENCES workers (worker_id)
    ON DELETE SET NULL
    ON UPDATE CASCADE,
  CONSTRAINT fk_bad_review_tasks_bot
    FOREIGN KEY (bad_review_task_bot)
    REFERENCES bots (bot_id)
    ON DELETE SET NULL
    ON UPDATE CASCADE
) ENGINE = InnoDB;
