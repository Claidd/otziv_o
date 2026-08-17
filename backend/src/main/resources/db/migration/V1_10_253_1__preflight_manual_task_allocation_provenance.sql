-- Fail before V254 performs any auto-committing ALTER TABLE statements.
-- The intended task is derived from immutable live source bindings because
-- contractor_payment_allocations.manual_payment_task_id does not exist yet.
DELIMITER $$

DROP PROCEDURE IF EXISTS preflight_manual_task_allocation_provenance $$
CREATE PROCEDURE preflight_manual_task_allocation_provenance()
BEGIN
    IF EXISTS (
        SELECT 1
        FROM contractor_payment_allocations allocation
        JOIN contractor_actual_payment_attributions attribution
          ON allocation.id = attribution.original_allocation_id
        JOIN manual_payment_tasks actual_task
          ON actual_task.id = attribution.actual_manual_payment_task_id
        LEFT JOIN payment_links link
          ON allocation.source_type = 'PAYMENT_LINK'
         AND allocation.source_id = link.id
         AND allocation.id = link.contractor_allocation_id
        LEFT JOIN common_invoices invoice
          ON allocation.source_type = 'COMMON_INVOICE'
         AND allocation.source_id = invoice.invoice_id
         AND allocation.id = invoice.contractor_allocation_id
        WHERE attribution.actual_cash_destination_kind = 'MANUAL_PAYMENT_TASK'
          AND attribution.actual_manual_payment_task_target_kind
                IN ('SPECIALIST', 'MANAGER')
          AND actual_task.accounting_target_kind
                = attribution.actual_manual_payment_task_target_kind
          AND actual_task.accounting_target_profile_id
                = attribution.actual_recipient_profile_id
          AND allocation.recipient_profile_id
                = actual_task.accounting_target_profile_id
          AND allocation.mode = attribution.accounting_mode
          AND COALESCE(link.manual_task_id,
                       invoice.payment_route_manual_task_id) IS NOT NULL
          AND COALESCE(link.manual_task_id,
                       invoice.payment_route_manual_task_id) <> actual_task.id
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V254 conflicting manual task allocation provenance';
    END IF;
END $$

DELIMITER ;

CALL preflight_manual_task_allocation_provenance();
DROP PROCEDURE preflight_manual_task_allocation_provenance;
