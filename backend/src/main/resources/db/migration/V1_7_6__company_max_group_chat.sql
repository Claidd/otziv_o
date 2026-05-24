ALTER TABLE companies
    ADD COLUMN company_max_group_chat_id BIGINT NULL,
    ADD COLUMN company_max_link_user_id BIGINT NULL,
    ADD COLUMN company_max_link_requested_at DATETIME NULL;

CREATE INDEX idx_companies_max_group_chat_id
    ON companies (company_max_group_chat_id);

CREATE INDEX idx_companies_max_link_user_id
    ON companies (company_max_link_user_id);
