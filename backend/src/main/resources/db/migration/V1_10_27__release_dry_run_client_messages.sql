UPDATE scheduled_client_message_state state
JOIN (
    SELECT state_id, MAX(attempted_at) AS latest_attempted_at
    FROM scheduled_client_message_attempts
    GROUP BY state_id
) latest_attempt ON latest_attempt.state_id = state.state_id
JOIN scheduled_client_message_attempts attempt
  ON attempt.state_id = state.state_id
 AND attempt.attempted_at = latest_attempt.latest_attempted_at
SET state.next_attempt_at = CURRENT_TIMESTAMP(6),
    state.locked_until = NULL,
    state.updated_at = CURRENT_TIMESTAMP(6)
WHERE state.state_status = 'ACTIVE'
  AND state.next_attempt_at IS NOT NULL
  AND state.next_attempt_at > CURRENT_TIMESTAMP(6)
  AND attempt.attempt_status = 'SKIPPED'
  AND attempt.error_code = 'client_messages_dry_run';
