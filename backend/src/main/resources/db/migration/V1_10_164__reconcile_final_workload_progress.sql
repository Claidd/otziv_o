-- Repair final workload snapshots that disagree with a confirmed 100% result.
-- The workload projection remains authoritative for the denominator; the
-- operational daily row is used only to prove that the visible queue reached
-- zero before midnight.
UPDATE workload_shadow_worker_daily daily
JOIN worker_daily_performance actual
  ON actual.progress_date = daily.progress_date
 AND actual.worker_id = daily.worker_id
SET daily.active_units = GREATEST(
        daily.late_excluded_units,
        daily.active_units - (daily.eligible_units - daily.completed_units)
    ),
    daily.completed_units = daily.eligible_units,
    daily.progress_percent = 100,
    daily.reached_100 = TRUE,
    daily.reached_100_once = TRUE,
    daily.first_reached_100_at = COALESCE(
        daily.first_reached_100_at,
        actual.last_completed_at
    ),
    daily.last_reached_100_at = CASE
        WHEN daily.last_reached_100_at IS NULL
          OR actual.last_completed_at > daily.last_reached_100_at
        THEN actual.last_completed_at
        ELSE daily.last_reached_100_at
    END,
    daily.last_snapshot_at = GREATEST(
        daily.last_snapshot_at,
        actual.last_completed_at
    ),
    daily.finalized_at = GREATEST(
        COALESCE(daily.finalized_at, actual.last_completed_at),
        actual.last_completed_at
    ),
    daily.finalization_status = 'ON_TIME'
WHERE daily.finalized = TRUE
  AND daily.finalization_status = 'ON_TIME'
  AND daily.eligible_units > 0
  AND daily.completed_units < daily.eligible_units
  AND daily.external_blocked_units = 0
  AND actual.completed_count > 0
  AND actual.active_count = 0
  AND actual.reached_100 = TRUE
  AND actual.last_completed_at IS NOT NULL
  AND actual.last_completed_at < DATE_ADD(daily.progress_date, INTERVAL 1 DAY);
