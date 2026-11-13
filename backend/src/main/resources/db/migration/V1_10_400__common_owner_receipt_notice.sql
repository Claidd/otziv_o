CREATE TABLE common_owner_receipt_chat_route (
    owner_login VARCHAR(160) NOT NULL PRIMARY KEY,
    group_manager_login VARCHAR(160) NOT NULL,
    copy_admin_login VARCHAR(160) NOT NULL
) ENGINE=InnoDB;

INSERT INTO common_owner_receipt_chat_route (owner_login, group_manager_login, copy_admin_login)
VALUES ('mia', 'vika', 'alex'), ('hunt', 'lika', 'alex');

CREATE TABLE common_owner_receipt_notice (
    report_id BIGINT NOT NULL,
    owner_id BIGINT NOT NULL,
    chat_id BIGINT NOT NULL,
    callback_token CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(16) NOT NULL,
    attempted_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (report_id, chat_id),
    UNIQUE KEY uk_common_owner_notice_token (callback_token),
    CONSTRAINT fk_common_owner_notice_report FOREIGN KEY (report_id) REFERENCES common_owner_receipt_approval(id) ON DELETE CASCADE
) ENGINE=InnoDB;
