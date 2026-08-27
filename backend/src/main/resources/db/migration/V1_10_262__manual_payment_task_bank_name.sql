ALTER TABLE manual_payment_tasks
    ADD COLUMN manual_bank_name varchar(120) NULL;

ALTER TABLE manual_payment_task_ledger_entries
    ADD COLUMN manual_bank_name_snapshot text NULL;