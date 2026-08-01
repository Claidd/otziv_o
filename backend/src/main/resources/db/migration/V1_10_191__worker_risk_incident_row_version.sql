ALTER TABLE worker_risk_incidents
    ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0;
