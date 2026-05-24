CREATE TABLE IF NOT EXISTS lead_import_telephone_pool (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    manager_id BIGINT NOT NULL,
    telephone_id BIGINT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    priority_order INT NOT NULL DEFAULT 0,
    CONSTRAINT uk_lead_import_pool_manager_telephone UNIQUE (manager_id, telephone_id),
    INDEX idx_lead_import_pool_manager_active (manager_id, active, priority_order),
    CONSTRAINT fk_lead_import_pool_manager
        FOREIGN KEY (manager_id) REFERENCES managers (manager_id),
    CONSTRAINT fk_lead_import_pool_telephone
        FOREIGN KEY (telephone_id) REFERENCES telephones (telephone_id)
);

INSERT IGNORE INTO lead_import_telephone_pool (manager_id, telephone_id, active, priority_order)
SELECT 2, t.telephone_id, TRUE, t.telephone_id
FROM telephones t
WHERE t.telephone_id BETWEEN 1 AND 15
  AND EXISTS (SELECT 1 FROM managers m WHERE m.manager_id = 2);

INSERT IGNORE INTO lead_import_telephone_pool (manager_id, telephone_id, active, priority_order)
SELECT 3, t.telephone_id, TRUE, t.telephone_id - 15
FROM telephones t
WHERE t.telephone_id BETWEEN 16 AND 32
  AND EXISTS (SELECT 1 FROM managers m WHERE m.manager_id = 3);
