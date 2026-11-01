-- Immutable profiles shared by company choices; editing a choice creates a new
-- profile snapshot and never changes another company or an issued document.
CREATE TABLE billing_payer_profile (
 id CHAR(36) PRIMARY KEY,
 fingerprint CHAR(64) NOT NULL UNIQUE,
 inn VARCHAR(12) NOT NULL,
 kpp VARCHAR(9) NOT NULL,
 payer_json TEXT NOT NULL,
 created_by VARCHAR(150) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
CREATE TABLE company_billing_payer_choice (
 id CHAR(36) PRIMARY KEY,
 company_id BIGINT NOT NULL,
 profile_id CHAR(36) NOT NULL,
 inn VARCHAR(12) NOT NULL,
 kpp VARCHAR(9) NOT NULL,
 is_default BOOLEAN NOT NULL DEFAULT FALSE,
 version BIGINT NOT NULL DEFAULT 0,
 deleted_at DATETIME(6) NULL,
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 last_used_at DATETIME(6) NULL,
 UNIQUE KEY uk_company_payer_identity(company_id,inn,kpp),
 KEY idx_company_payer_active(company_id,deleted_at,is_default),
 CONSTRAINT fk_payer_choice_profile FOREIGN KEY(profile_id) REFERENCES billing_payer_profile(id)
);
CREATE TABLE billing_payer_choice_audit (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 choice_id CHAR(36) NOT NULL,
 company_id BIGINT NOT NULL,
 action VARCHAR(24) NOT NULL,
 before_profile_id CHAR(36) NULL,
 after_profile_id CHAR(36) NULL,
 actor VARCHAR(150) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
-- Legacy saved defaults remain defaults. Historical documents stay unchanged.
INSERT IGNORE INTO billing_payer_profile(id,fingerprint,inn,kpp,payer_json,created_by)
 SELECT UUID(),SHA2(payer_json,256),JSON_UNQUOTE(JSON_EXTRACT(payer_json,'$.inn')),
 COALESCE(JSON_UNQUOTE(JSON_EXTRACT(payer_json,'$.kpp')),''),payer_json,updated_by
 FROM company_billing_payer WHERE JSON_VALID(payer_json)
 AND JSON_UNQUOTE(JSON_EXTRACT(IF(JSON_VALID(payer_json),payer_json,'{}'),'$.inn')) REGEXP '^([0-9]{10}|[0-9]{12})$'
 AND LENGTH(JSON_UNQUOTE(JSON_EXTRACT(IF(JSON_VALID(payer_json),payer_json,'{}'),'$.name')))>0;
INSERT INTO company_billing_payer_choice(id,company_id,profile_id,inn,kpp,is_default,updated_at)
 SELECT UUID(),c.company_id,p.id,p.inn,p.kpp,TRUE,c.updated_at
 FROM company_billing_payer c JOIN billing_payer_profile p ON p.fingerprint=SHA2(c.payer_json,256);

-- Import issued snapshots for the actual work companies, including members of legacy common invoices.
-- A delivery company is not automatically the payer for every order.
INSERT IGNORE INTO billing_payer_profile(id,fingerprint,inn,kpp,payer_json,created_by)
 SELECT UUID(),SHA2(payer_json,256),JSON_UNQUOTE(JSON_EXTRACT(payer_json,'$.inn')),
 COALESCE(JSON_UNQUOTE(JSON_EXTRACT(payer_json,'$.kpp')),''),payer_json,'paper-history'
 FROM paper_invoice_document WHERE bank_invoice_id IS NOT NULL AND cancelled_at IS NULL AND JSON_VALID(payer_json)
 AND JSON_UNQUOTE(JSON_EXTRACT(IF(JSON_VALID(payer_json),payer_json,'{}'),'$.inn')) REGEXP '^([0-9]{10}|[0-9]{12})$'
 AND LENGTH(JSON_UNQUOTE(JSON_EXTRACT(IF(JSON_VALID(payer_json),payer_json,'{}'),'$.name')))>0;
INSERT IGNORE INTO company_billing_payer_choice(id,company_id,profile_id,inn,kpp,is_default,last_used_at)
 SELECT UUID(),company_id,profile_id,inn,kpp,FALSE,created_at FROM (
   SELECT source.*,ROW_NUMBER() OVER(PARTITION BY company_id,inn,kpp ORDER BY created_at DESC,document_id DESC) AS position_number FROM (
     SELECT o.order_company AS company_id,p.id AS profile_id,p.inn,p.kpp,d.created_at,d.id AS document_id
     FROM paper_invoice_document d JOIN orders o ON d.target_kind='ORDER' AND o.order_id=d.target_id
     JOIN billing_payer_profile p ON p.fingerprint=SHA2(d.payer_json,256)
     WHERE d.bank_invoice_id IS NOT NULL AND d.cancelled_at IS NULL
     UNION ALL
     SELECT o.order_company,p.id,p.inn,p.kpp,d.created_at,d.id
     FROM paper_invoice_document d JOIN common_invoice_orders m ON d.target_kind='COMMON_INVOICE' AND m.invoice_id=d.target_id
     JOIN orders o ON o.order_id=m.order_id JOIN billing_payer_profile p ON p.fingerprint=SHA2(d.payer_json,256)
     WHERE d.bank_invoice_id IS NOT NULL AND d.cancelled_at IS NULL
   ) source
 ) ranked WHERE position_number=1;
UPDATE company_billing_payer_choice c JOIN (
 SELECT company_id,id,ROW_NUMBER() OVER(PARTITION BY company_id ORDER BY last_used_at DESC,id) AS position_number
 FROM company_billing_payer_choice WHERE company_id NOT IN (
   SELECT company_id FROM (SELECT company_id FROM company_billing_payer_choice WHERE is_default=TRUE) existing_defaults
 )
) picked ON picked.id=c.id AND picked.position_number=1 SET c.is_default=TRUE;
