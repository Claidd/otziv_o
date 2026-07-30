ALTER TABLE workload_shadow_worker_daily
    ADD COLUMN reached_100_once BIT NOT NULL DEFAULT 0 AFTER reached_100,
    ADD COLUMN first_reached_100_at DATETIME(6) NULL AFTER reached_100_once,
    ADD COLUMN last_reached_100_at DATETIME(6) NULL AFTER first_reached_100_at;

UPDATE workload_shadow_worker_daily daily
SET daily.reached_100_once =
        CASE
            WHEN daily.reached_100 = TRUE THEN TRUE
            ELSE FALSE
        END,
    daily.first_reached_100_at =
        CASE
            WHEN daily.reached_100 = TRUE
                THEN daily.last_snapshot_at
            ELSE NULL
        END,
    daily.last_reached_100_at =
        CASE
            WHEN daily.reached_100 = TRUE
                THEN daily.last_snapshot_at
            ELSE NULL
        END;
