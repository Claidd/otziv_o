-- Expand only. Do not invent versions/identities for historical queue rows.
-- Stop old lead writers/senders before activation. Preserve this source UUID on restore;
-- only one restored/original database carrying that identity may produce live commands.
CREATE TABLE lead_command_source (
    singleton_id TINYINT NOT NULL PRIMARY KEY,
    source_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    CONSTRAINT ck_lead_command_source_singleton CHECK (singleton_id = 1)
) ENGINE=InnoDB;
INSERT INTO lead_command_source(singleton_id, source_id) VALUES(1, LOWER(UUID()));

CREATE TABLE lead_command_stream (
    lead_id BIGINT NOT NULL PRIMARY KEY,
    last_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_lead_stream_version CHECK (last_version >= 0)
) ENGINE=InnoDB;

ALTER TABLE lead_command_queue
    ADD COLUMN source_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN entity_version BIGINT NULL,
    ADD COLUMN payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD UNIQUE KEY uk_lead_command_entity_version(source_id, lead_id, entity_version);

CREATE TABLE lead_command_manual_requests (
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    lead_id BIGINT NOT NULL,
    command_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB;

-- Receipt is retained with the source stream, including STALE and already applied commands.
-- There is no TTL deletion: retry protection must survive the full retained replay horizon.
CREATE TABLE lead_inbound_receipts (
    source_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    entity_id BIGINT NOT NULL,
    entity_version BIGINT NOT NULL,
    command_kind VARCHAR(16) NOT NULL,
    payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    applied_version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY(source_id, operation_id),
    UNIQUE KEY uk_lead_inbound_version(source_id, entity_id, entity_version)
) ENGINE=InnoDB;

CREATE TABLE lead_inbound_entities (
    source_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    entity_id BIGINT NOT NULL,
    last_version BIGINT NOT NULL DEFAULT 0,
    telephone_key VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_lead_id BIGINT NULL,
    PRIMARY KEY(source_id, entity_id)
) ENGINE=InnoDB;

-- Both legacy and versioned writes take this lock; a bound target never permits an
-- unversioned overwrite, even while unrelated legacy senders remain in compatibility mode.
CREATE TABLE lead_inbound_targets (
    telephone_key VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    source_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    entity_id BIGINT NULL
) ENGINE=InnoDB;
