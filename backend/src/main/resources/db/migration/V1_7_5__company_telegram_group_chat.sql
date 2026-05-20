ALTER TABLE companies
    ADD COLUMN company_telegram_group_chat_id BIGINT NULL;

CREATE INDEX idx_companies_telegram_group_chat_id
    ON companies (company_telegram_group_chat_id);
