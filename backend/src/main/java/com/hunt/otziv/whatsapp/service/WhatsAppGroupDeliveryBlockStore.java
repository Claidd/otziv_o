package com.hunt.otziv.whatsapp.service;

import com.hunt.otziv.whatsapp.api.WhatsAppGroupDeliveryBlocks;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Rows of whatsapp_group_delivery_blocks, keyed by account and group. */
@Component
@RequiredArgsConstructor
@Slf4j
class WhatsAppGroupDeliveryBlockStore implements WhatsAppGroupDeliveryBlocks {

    private final JdbcTemplate jdbc;

    @Override
    public void recordRejected(String clientId, String groupId, String operationId) {
        if (!hasText(clientId) || !hasText(groupId) || !hasText(operationId)) {
            throw new IllegalArgumentException("Refused WhatsApp delivery needs account, group and operation");
        }
        jdbc.update("INSERT INTO whatsapp_group_delivery_blocks (client_id, group_id, operation_id, rejected_at)"
                        + " VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE operation_id = VALUES(operation_id),"
                        + " rejected_at = VALUES(rejected_at)",
                clientId.trim(), groupId.trim(), operationId.trim(), Timestamp.valueOf(LocalDateTime.now()));
        log.warn("WhatsApp refused a group message; new messages to the group are held: clientId={}, groupId={}",
                clientId, groupId);
    }

    @Override
    public boolean isBlocked(String clientId, String groupId) {
        if (!hasText(clientId) || !hasText(groupId)) {
            return false;
        }
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM whatsapp_group_delivery_blocks"
                + " WHERE client_id = ? AND group_id = ?", Integer.class, clientId.trim(), groupId.trim());
        return rows != null && rows > 0;
    }

    @Override
    public void clear(String clientId, String groupId) {
        if (!hasText(clientId) || !hasText(groupId)) {
            return;
        }
        if (jdbc.update("DELETE FROM whatsapp_group_delivery_blocks WHERE client_id = ? AND group_id = ?",
                clientId.trim(), groupId.trim()) > 0) {
            log.info("WhatsApp group delivery hold removed by a client message: clientId={}, groupId={}", clientId, groupId);
        }
    }

    @Override
    public void clearGroup(String groupId) {
        if (!hasText(groupId)) {
            return;
        }
        if (jdbc.update("DELETE FROM whatsapp_group_delivery_blocks WHERE group_id = ?", groupId.trim()) > 0) {
            log.info("WhatsApp group delivery hold removed by the manager's repair: groupId={}", groupId);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
