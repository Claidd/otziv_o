package com.hunt.otziv.whatsapp.service;

import com.hunt.otziv.whatsapp.dto.WhatsAppGroupReplyDTO;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** The receipt and all database effects commit together, before a successful ACK. */
@Component
public class WhatsAppGroupWebhookDeduplicator {
    public enum Result { APPLIED, DUPLICATE, IN_PROGRESS }
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public WhatsAppGroupWebhookDeduplicator(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Result execute(WhatsAppGroupReplyDTO reply, Runnable businessEffect) {
        String key = key(reply);
        if (!inFlight.add(key)) return Result.IN_PROGRESS;
        try {
            return transaction.execute(status -> {
                // InnoDB unique-key serialization works across backend instances too.
                // A competing rollback releases the key; a committed row proves completion.
                jdbc.update("INSERT INTO whatsapp_inbound_receipts(receipt_key) VALUES (?) "
                        + "ON DUPLICATE KEY UPDATE receipt_key=receipt_key", key);
                Boolean completed = jdbc.queryForObject(
                        "SELECT completed_at IS NOT NULL FROM whatsapp_inbound_receipts WHERE receipt_key=? FOR UPDATE",
                        Boolean.class, key);
                if (Boolean.TRUE.equals(completed)) return Result.DUPLICATE;
                businessEffect.run();
                jdbc.update("UPDATE whatsapp_inbound_receipts SET completed_at=CURRENT_TIMESTAMP(6) WHERE receipt_key=?", key);
                return Result.APPLIED;
            });
        } finally {
            // TransactionTemplate returns only after commit (including commit failures).
            inFlight.remove(key);
        }
    }

    static String key(WhatsAppGroupReplyDTO reply) {
        if (reply == null) throw new IllegalArgumentException("inbound_identity_required");
        String[] parts = { "whatsapp", "/webhook/whatsapp-group-reply", reply.getClientId(), reply.getGroupId(), reply.getMessageId() };
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : parts) {
                if (value == null || value.isBlank() || value.length() > 512) throw new IllegalArgumentException("inbound_identity_required");
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
