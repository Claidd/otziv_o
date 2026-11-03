-- Large immutable files must not be repeated in FULL row-image binlogs when a
-- delivery status changes. Verify every byte before removing the old columns.
-- SELECT preserves the actual source column charset/collation. Production's ID
-- collation can differ from the current database default, so a fresh CHAR(36)
-- definition is not sufficient for a compatible foreign key.
CREATE TABLE paper_invoice_files AS
SELECT id AS document_id, invoice_pdf, act_pdf, invoice_original_pdf, act_original_pdf
FROM paper_invoice_document WHERE 1=0;
ALTER TABLE paper_invoice_files
    ADD PRIMARY KEY(document_id),
    ADD CONSTRAINT fk_paper_files_document FOREIGN KEY (document_id) REFERENCES paper_invoice_document(id);

INSERT INTO paper_invoice_files
SELECT id, invoice_pdf, act_pdf, invoice_original_pdf, act_original_pdf
FROM paper_invoice_document
WHERE invoice_pdf IS NOT NULL OR act_pdf IS NOT NULL
   OR invoice_original_pdf IS NOT NULL OR act_original_pdf IS NOT NULL;

CREATE TEMPORARY TABLE paper_files_copy_guard (ok TINYINT NOT NULL);
INSERT INTO paper_files_copy_guard
SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM paper_invoice_document d
    LEFT JOIN paper_invoice_files f ON f.document_id=d.id
    WHERE NOT (d.invoice_pdf <=> f.invoice_pdf) OR NOT (d.act_pdf <=> f.act_pdf)
       OR NOT (d.invoice_original_pdf <=> f.invoice_original_pdf)
       OR NOT (d.act_original_pdf <=> f.act_original_pdf)
) THEN 1 ELSE NULL END;
DROP TEMPORARY TABLE paper_files_copy_guard;

ALTER TABLE paper_invoice_document
    DROP COLUMN invoice_pdf, DROP COLUMN act_pdf,
    DROP COLUMN invoice_original_pdf, DROP COLUMN act_original_pdf,
    ADD COLUMN work_next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    ADD COLUMN work_failures INT NOT NULL DEFAULT 0,
    ADD COLUMN delivery_attempted_at DATETIME(6) NULL;

-- Existing in-flight requests remain ambiguous and must never be resent.
UPDATE paper_invoice_document SET delivery_attempted_at=updated_at
WHERE message_delivery='SENDING' OR invoice_delivery='SENDING' OR act_delivery='SENDING';
