CREATE TABLE association_orphan_quarantine (
    association_table VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    left_column VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    left_id BIGINT NOT NULL,
    right_column VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    right_id BIGINT NOT NULL,
    orphan_side VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    detected_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (association_table, left_id, right_id),
    INDEX idx_association_orphan_detected (detected_at, association_table),
    CONSTRAINT ck_association_orphan_side
        CHECK (orphan_side IN ('LEFT', 'RIGHT', 'BOTH'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
