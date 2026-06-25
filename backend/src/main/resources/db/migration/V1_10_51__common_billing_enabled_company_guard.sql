CREATE TABLE IF NOT EXISTS common_billing_enabled_company_conflicts (
    conflict_id BIGINT NOT NULL AUTO_INCREMENT,
    company_id BIGINT NOT NULL,
    account_company_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    account_name VARCHAR(160) NOT NULL,
    kept_enabled BOOLEAN NOT NULL,
    detected_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (conflict_id),
    UNIQUE KEY uk_common_billing_company_conflict_link (company_id, account_company_id),
    INDEX idx_common_billing_company_conflicts_company (company_id, detected_at)
);

INSERT IGNORE INTO common_billing_enabled_company_conflicts (
    company_id,
    account_company_id,
    account_id,
    account_name,
    kept_enabled
)
SELECT
    link.company_id,
    link.account_company_id,
    account.account_id,
    account.account_name,
    account.enabled = TRUE
        AND link.account_company_id = duplicate_companies.kept_account_company_id
FROM common_billing_account_companies link
JOIN common_billing_accounts account ON account.account_id = link.account_id
JOIN (
    SELECT
        ranked.company_id,
        MIN(CASE WHEN ranked.duplicate_rank = 1 THEN ranked.account_company_id END) AS kept_account_company_id
    FROM (
        SELECT
            link.company_id,
            link.account_company_id,
            ROW_NUMBER() OVER (
                PARTITION BY link.company_id
                ORDER BY account.enabled DESC, link.account_company_id ASC
            ) AS duplicate_rank
        FROM common_billing_account_companies link
        JOIN common_billing_accounts account ON account.account_id = link.account_id
        WHERE link.enabled = TRUE
    ) ranked
    GROUP BY ranked.company_id
    HAVING COUNT(*) > 1
) duplicate_companies ON duplicate_companies.company_id = link.company_id
WHERE link.enabled = TRUE;

UPDATE common_billing_account_companies link
JOIN common_billing_accounts account ON account.account_id = link.account_id
SET link.enabled = FALSE
WHERE link.enabled = TRUE
  AND account.enabled = FALSE;

UPDATE common_billing_account_companies link
JOIN (
    SELECT
        ranked.account_company_id,
        ROW_NUMBER() OVER (
            PARTITION BY ranked.company_id
            ORDER BY ranked.account_enabled DESC, ranked.account_company_id ASC
        ) AS duplicate_rank
    FROM (
        SELECT
            link.company_id,
            link.account_company_id,
            account.enabled AS account_enabled
        FROM common_billing_account_companies link
        JOIN common_billing_accounts account ON account.account_id = link.account_id
        WHERE link.enabled = TRUE
    ) ranked
) ranked_links ON ranked_links.account_company_id = link.account_company_id
SET link.enabled = FALSE
WHERE ranked_links.duplicate_rank > 1;

ALTER TABLE common_billing_account_companies
    ADD COLUMN enabled_company_id BIGINT
        GENERATED ALWAYS AS (CASE WHEN enabled THEN company_id ELSE NULL END) STORED,
    ADD UNIQUE KEY uk_common_billing_enabled_company (enabled_company_id);
