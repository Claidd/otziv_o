-- Owner decision of 05.11.2026: specialist worker #6 kept 100% for 57 days in a row; 2026-11-01
-- closed at 28 of 29 only because recovery task #1160 of another firm had no account and was
-- moved by the manager the next morning. Count that day as reached, like the 2026-10-29
-- correction: the real 28/29 numbers stay as evidence, the streak continues.
-- Guards keep the migration a no-op on any database where these rows look different.

UPDATE end_of_day_achievement_results day_row
JOIN (
    SELECT COUNT(*) AS matches
    FROM end_of_day_achievement_results previous_day
    WHERE previous_day.actor_role = 'WORKER'
      AND previous_day.actor_id = 6
      AND previous_day.result_date = '2026-10-31'
      AND previous_day.reached_100 = b'1'
      AND previous_day.streak_days = 57
) previous_ok ON previous_ok.matches = 1
SET day_row.reached_100 = b'1',
    day_row.streak_days = 58
WHERE day_row.actor_role = 'WORKER'
  AND day_row.actor_id = 6
  AND day_row.result_date = '2026-11-01'
  AND day_row.reached_100 = b'0'
  AND day_row.eligible_count = 29
  AND day_row.completed_count = 28;

-- Later days of the same unbroken run were counted from zero; continue them from 58.
UPDATE end_of_day_achievement_results later_day
JOIN (
    SELECT COUNT(*) AS matches
    FROM end_of_day_achievement_results corrected_day
    WHERE corrected_day.actor_role = 'WORKER'
      AND corrected_day.actor_id = 6
      AND corrected_day.result_date = '2026-11-01'
      AND corrected_day.reached_100 = b'1'
      AND corrected_day.streak_days = 58
) corrected ON corrected.matches = 1
JOIN (
    SELECT COALESCE(MIN(break_day.result_date), DATE('9999-12-31')) AS first_break
    FROM end_of_day_achievement_results break_day
    WHERE break_day.actor_role = 'WORKER'
      AND break_day.actor_id = 6
      AND break_day.result_date > '2026-11-01'
      AND break_day.eligible_count > 0
      AND break_day.reached_100 = b'0'
) run_end
SET later_day.streak_days = later_day.streak_days + 58
WHERE later_day.actor_role = 'WORKER'
  AND later_day.actor_id = 6
  AND later_day.result_date > '2026-11-01'
  AND later_day.result_date < run_end.first_break
  AND later_day.streak_days < 58;
