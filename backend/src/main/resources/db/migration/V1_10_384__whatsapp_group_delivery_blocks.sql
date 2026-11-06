-- WhatsApp refused a group message of this account (ACK -1 settled after the send): the account is
-- most likely no longer a member of the group, for example after the client removed it and reset the
-- invite link. New messages to that group from this account are held before any provider call, so the
-- automation does not create more undelivered messages. A message from a client in that group proves
-- membership again and removes the row; the manager's repair removes it for one new attempt.
CREATE TABLE IF NOT EXISTS whatsapp_group_delivery_blocks (
    client_id VARCHAR(64) NOT NULL,
    group_id VARCHAR(160) NOT NULL,
    operation_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    rejected_at DATETIME(6) NOT NULL,
    PRIMARY KEY (client_id, group_id),
    KEY idx_whatsapp_group_delivery_blocks_group (group_id)
) ENGINE=InnoDB;
