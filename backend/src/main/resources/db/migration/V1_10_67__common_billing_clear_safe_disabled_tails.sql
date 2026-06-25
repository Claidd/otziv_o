UPDATE common_invoices invoice
SET invoice.last_error = NULL,
    invoice.next_reminder_at = NULL
WHERE invoice.status = 'DISABLED'
  AND invoice.last_error IS NOT NULL
  AND (
      LOWER(invoice.last_error) LIKE 'disabled:%'
      OR LOWER(invoice.last_error) LIKE 'empty:%'
      OR LOWER(invoice.last_error) LIKE 'merged_into:%'
  )
  AND NOT EXISTS (
      SELECT 1
      FROM common_invoice_orders item
      WHERE item.invoice_id = invoice.invoice_id
        AND item.paid = 0
  );
