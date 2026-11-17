-- A rolled-back claim has no active sender. Leave the envelope and attempts intact for reconciliation.
UPDATE scheduled_client_message_state
SET delivery_status = 'UNKNOWN'
WHERE state_status = 'ACTIVE' AND delivery_status = 'CLAIMED'
  AND locked_until IS NULL AND next_attempt_at IS NULL
  AND last_error_code = 'state_transaction_outcome_uncertain'
  AND COALESCE(last_error_message, '') NOT LIKE '%legacy_operation_unverified';
