-- Payment-return reminders used to create a second PAYMENT_REMINDER state with
-- a payment-return:* target key next to the normal order:* payment reminder.
-- That can send duplicate standalone payment messages for the same reopened
-- order. Keep the canonical order:* cycle and close/normalize the legacy
-- payment-return states.

UPDATE scheduled_client_message_state payment_return_state
JOIN scheduled_client_message_state canonical_state
  ON canonical_state.scenario = payment_return_state.scenario
 AND canonical_state.target_key = REPLACE(
        payment_return_state.target_key,
        CONCAT('payment-return:', payment_return_state.order_id, ':'),
        CONCAT('order:', payment_return_state.order_id, ':')
     )
SET payment_return_state.state_status = 'DONE',
    payment_return_state.next_attempt_at = NULL,
    payment_return_state.locked_until = NULL,
    payment_return_state.last_error_code = 'payment_return_duplicate_merged',
    payment_return_state.last_error_message = 'Дублирующее напоминание после возврата закрыто; используется обычный цикл оплаты',
    payment_return_state.updated_at = CURRENT_TIMESTAMP(6)
WHERE payment_return_state.scenario = 'PAYMENT_REMINDER'
  AND payment_return_state.state_status = 'ACTIVE'
  AND payment_return_state.target_key LIKE 'payment-return:%';

UPDATE scheduled_client_message_state payment_return_state
LEFT JOIN scheduled_client_message_state canonical_state
  ON canonical_state.scenario = payment_return_state.scenario
 AND canonical_state.target_key = REPLACE(
        payment_return_state.target_key,
        CONCAT('payment-return:', payment_return_state.order_id, ':'),
        CONCAT('order:', payment_return_state.order_id, ':')
     )
SET payment_return_state.target_key = REPLACE(
        payment_return_state.target_key,
        CONCAT('payment-return:', payment_return_state.order_id, ':'),
        CONCAT('order:', payment_return_state.order_id, ':')
    ),
    payment_return_state.last_error_code = 'payment_return_reopened',
    payment_return_state.last_error_message = 'Заказ снова ожидает оплату после возврата платежа',
    payment_return_state.updated_at = CURRENT_TIMESTAMP(6)
WHERE payment_return_state.scenario = 'PAYMENT_REMINDER'
  AND payment_return_state.state_status = 'ACTIVE'
  AND payment_return_state.target_key LIKE 'payment-return:%'
  AND canonical_state.state_id IS NULL;
