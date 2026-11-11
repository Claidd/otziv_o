-- A transfer of 11 November 2026 named its paper invoice with Cyrillic look-alike letters
-- (the payer typed the two letters of «PB-2-...» on the Russian keyboard layout). The statement
-- sweep read the transfer, did not recognise the number and moved on; nothing was stored for it,
-- so the invoice stayed unpaid although the money had arrived.
--
-- From this version such numbers are recognised (PaperBillingSettlement.invoiceNumbers).
-- Rewind the sweep so that the bank statement is read again from 9 November. Receipts that were
-- already stored are skipped by their bank payment id (INSERT IGNORE), so nothing is applied
-- twice; the transfer that was not recognised now closes its invoice through the ordinary
-- settlement: payer, amount and composition are checked as for any other receipt.
UPDATE paper_invoice_statement_cursor
SET next_date = '2026-11-09', statement_id = NULL, window_end = NULL, error_text = NULL
WHERE next_date > '2026-11-09';
