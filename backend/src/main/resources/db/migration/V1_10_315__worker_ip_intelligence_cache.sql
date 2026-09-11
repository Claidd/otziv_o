-- Derived provider classification only. Authorization and per-attempt audit
-- remain live. A restart must not extend the original provider observation TTL.
CREATE TABLE worker_ip_intelligence_cache (
    cache_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    mobile BOOLEAN NOT NULL,
    risky BOOLEAN NOT NULL,
    organization VARCHAR(500) NOT NULL,
    source VARCHAR(32) NOT NULL,
    observed_epoch_ms BIGINT NOT NULL,
    expires_epoch_ms BIGINT NOT NULL,
    INDEX idx_worker_ip_cache_expiry (expires_epoch_ms)
) ENGINE=InnoDB;
