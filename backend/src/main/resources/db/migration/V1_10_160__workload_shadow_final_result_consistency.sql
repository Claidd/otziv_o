-- Finalized workload rows created before this release did not include unfinished
-- externally blocked units in the denominator. Keep reached_100_once as the
-- informational intraday marker, but repair the final result used by history,
-- streaks, freeze credits and transfer eligibility.
UPDATE workload_shadow_worker_daily
SET progress_percent = LEAST(
        100.00,
        ROUND(
            completed_units * 100.00
                / (eligible_units + external_blocked_units),
            2
        )
    ),
    reached_100 = CASE
        WHEN completed_units >= eligible_units + external_blocked_units THEN TRUE
        ELSE FALSE
    END,
    eligible_units = eligible_units + external_blocked_units
WHERE finalized = TRUE
  AND finalization_status <> 'STALE_SNAPSHOT'
  AND external_blocked_units > 0;

-- Rebuild the simulated freeze ledger from the corrected final results during
-- the next projection run. Daily flags must be cleared first because the
-- recalculation only sets the days on which a freeze is actually consumed.
UPDATE workload_shadow_worker_daily
SET freeze_applied = FALSE
WHERE finalized = TRUE;

DELETE FROM workload_shadow_freeze_accounts;
