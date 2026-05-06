CREATE TABLE IF NOT EXISTS promo_text_assignments (
    id BIGINT NOT NULL AUTO_INCREMENT,
    manager_id BIGINT NOT NULL,
    section_code VARCHAR(40) NOT NULL,
    button_key VARCHAR(40) NOT NULL,
    promo_text_id BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_promo_assignment_manager_slot UNIQUE (manager_id, section_code, button_key),
    INDEX idx_promo_assignment_manager (manager_id),
    INDEX idx_promo_assignment_text (promo_text_id),
    CONSTRAINT fk_promo_assignment_manager
        FOREIGN KEY (manager_id) REFERENCES managers (manager_id)
        ON DELETE CASCADE
        ON UPDATE CASCADE,
    CONSTRAINT fk_promo_assignment_text
        FOREIGN KEY (promo_text_id) REFERENCES text_promo (id)
        ON DELETE RESTRICT
        ON UPDATE CASCADE
) ENGINE = InnoDB;
