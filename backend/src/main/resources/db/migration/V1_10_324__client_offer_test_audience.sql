ALTER TABLE client_offer_campaign
    ADD COLUMN test_only BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE client_offer_recipient
    MODIFY COLUMN company_id BIGINT NULL,
    ADD COLUMN user_id BIGINT NULL;
