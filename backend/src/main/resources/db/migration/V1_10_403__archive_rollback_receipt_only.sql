-- An interrupted archive operation may reconcile its original receipt, without starting a follow-up.
UPDATE scheduled_client_message_state
SET last_error_code = 'rollback_receipt_only'
WHERE scenario = 'ARCHIVE_REORDER_OFFER' AND state_status = 'ACTIVE' AND delivery_status = 'UNKNOWN'
  AND locked_until IS NULL AND next_attempt_at IS NULL AND last_error_code = 'state_transaction_outcome_uncertain'
  AND last_error_message LIKE 'Транзакция обработки откатилась.%';
