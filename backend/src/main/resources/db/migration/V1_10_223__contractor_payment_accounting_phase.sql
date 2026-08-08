CREATE TABLE contractor_payment_accounting_phase (
    id INT PRIMARY KEY,
    phase VARCHAR(16) NOT NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_by VARCHAR(150) NOT NULL DEFAULT 'MIGRATION',
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_contractor_payment_accounting_phase_id CHECK (id = 1),
    CONSTRAINT ck_contractor_payment_accounting_phase_value CHECK (phase IN ('SHADOW', 'LIVE'))
) ENGINE=InnoDB;

INSERT INTO contractor_payment_accounting_phase (id, phase)
SELECT 1,
       CASE
           WHEN EXISTS (
               SELECT 1
               FROM contractor_payment_allocations allocation
               WHERE allocation.mode = 'LIVE'
           ) THEN 'LIVE'
           ELSE 'SHADOW'
       END;
