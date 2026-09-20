package com.hunt.otziv.admin.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class BotDuplicateReportRepository {
    private final NamedParameterJdbcTemplate jdbc;

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String id, String fileName, String reportText) {
        jdbc.update("""
                INSERT INTO bot_duplicate_report_delivery (report_id, file_name, report_text)
                VALUES (:id, :fileName, :text)
                """, Map.of("id", id, "fileName", fileName, "text", reportText));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Claim> claimNext() {
        String token = UUID.randomUUID().toString();
        List<Claim> claims = jdbc.query("""
                SELECT report_id, file_name, report_text, pending_chat_ids, attempt_count
                FROM bot_duplicate_report_delivery
                WHERE next_attempt_at <= CURRENT_TIMESTAMP(6)
                  AND (lease_until IS NULL OR lease_until <= CURRENT_TIMESTAMP(6))
                ORDER BY next_attempt_at, report_id
                LIMIT 1 FOR UPDATE SKIP LOCKED
                """, Map.of(), (rs, row) -> new Claim(rs.getString("report_id"), token,
                rs.getString("file_name"), rs.getString("report_text"),
                parseRecipients(rs.getString("pending_chat_ids")), rs.getInt("attempt_count") + 1));
        if (claims.isEmpty()) return Optional.empty();
        Claim claim = claims.getFirst();
        jdbc.update("""
                UPDATE bot_duplicate_report_delivery
                SET lease_token = :token, lease_until = DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 10 MINUTE),
                    attempt_count = attempt_count + 1
                WHERE report_id = :id
                """, ownership(claim));
        return Optional.of(claim);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean renewLease(Claim claim) {
        return jdbc.update("""
                UPDATE bot_duplicate_report_delivery
                SET lease_until = DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 10 MINUTE)
                WHERE report_id = :id AND lease_token = :token AND lease_until > CURRENT_TIMESTAMP(6)
                """, ownership(claim)) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean savePendingRecipients(Claim claim, List<Long> recipients) {
        // An empty persisted list means every recipient was confirmed, even if cleanup was interrupted.
        return jdbc.update("""
                UPDATE bot_duplicate_report_delivery SET pending_chat_ids = :recipients
                WHERE report_id = :id AND lease_token = :token AND lease_until > CURRENT_TIMESTAMP(6)
                """, Map.of("id", claim.id(), "token", claim.token(), "recipients",
                recipients.stream().map(String::valueOf).collect(Collectors.joining(",")))) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean deleteDelivered(Claim claim) {
        return jdbc.update("""
                DELETE FROM bot_duplicate_report_delivery
                WHERE report_id = :id AND lease_token = :token AND pending_chat_ids = ''
                  AND lease_until > CURRENT_TIMESTAMP(6)
                """, ownership(claim)) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void retryLater(Claim claim) {
        long delaySeconds = Math.min(3600L, 30L << Math.min(7, Math.max(0, claim.attempt() - 1)));
        jdbc.update("""
                UPDATE bot_duplicate_report_delivery
                SET next_attempt_at = TIMESTAMPADD(SECOND, :delay, CURRENT_TIMESTAMP(6)),
                    lease_token = NULL, lease_until = NULL
                WHERE report_id = :id AND lease_token = :token AND lease_until > CURRENT_TIMESTAMP(6)
                """, Map.of("id", claim.id(), "token", claim.token(), "delay", delaySeconds));
    }

    private Map<String, Object> ownership(Claim claim) {
        return Map.of("id", claim.id(), "token", claim.token());
    }

    private static List<Long> parseRecipients(String text) {
        if (text == null) return null;
        if (text.isEmpty()) return List.of();
        return Arrays.stream(text.split(",")).map(Long::valueOf).toList();
    }

    public record Claim(String id, String token, String fileName, String reportText,
                        List<Long> pendingRecipients, int attempt) {}
}
