ALTER TABLE client_offer_campaign
    ADD COLUMN include_lead_in_work BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN include_lead_other BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN lead_fallback_client_id VARCHAR(128) NULL;

ALTER TABLE client_offer_recipient
    ADD COLUMN lead_id BIGINT NULL,
    ADD COLUMN phone VARCHAR(20) NULL;
