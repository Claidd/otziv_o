package com.hunt.otziv.payments.repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PaymentLinkReturnOutboxRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public void enqueue(Long paymentLinkId, long sourceVersion, String observedStatus) {
        jdbc.update("""
                INSERT INTO payment_link_return_reconciliation_outbox (
                    payment_link_id, source_version, observed_status
                ) VALUES (:paymentLinkId, :sourceVersion, :observedStatus)
                ON DUPLICATE KEY UPDATE outbox_id = outbox_id
                """, new MapSqlParameterSource()
                .addValue("paymentLinkId", paymentLinkId)
                .addValue("sourceVersion", sourceVersion)
                .addValue("observedStatus", observedStatus));
    }

    /** Inserts the manual follow-up generation once; every exact replay is a full-row no-op. */
    public void requeue(Long paymentLinkId, long sourceVersion, String observedStatus) {
        jdbc.update("""
                INSERT IGNORE INTO payment_link_return_reconciliation_outbox (
                    payment_link_id, source_version, observed_status
                ) VALUES (:paymentLinkId, :sourceVersion, :observedStatus)
                """, new MapSqlParameterSource()
                .addValue("paymentLinkId", paymentLinkId)
                .addValue("sourceVersion", sourceVersion)
                .addValue("observedStatus", observedStatus));
    }

    public Optional<Long> lockNextDueId() {
        List<Long> ids = jdbc.query("""
                SELECT outbox_id
                FROM payment_link_return_reconciliation_outbox
                WHERE (
                    status = 'PENDING' AND next_attempt_at <= CURRENT_TIMESTAMP(6)
                ) OR (
                    status = 'PROCESSING' AND lease_until < CURRENT_TIMESTAMP(6)
                )
                ORDER BY outbox_id
                LIMIT 1
                FOR UPDATE SKIP LOCKED
                """, Map.of(), (rs, rowNum) -> rs.getLong("outbox_id"));
        return ids.stream().findFirst();
    }

    public Optional<Claim> claim(Long outboxId, String token) {
        int updated = jdbc.update("""
                UPDATE payment_link_return_reconciliation_outbox
                SET status = 'PROCESSING',
                    claim_token = :token,
                    lease_until = DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 120 SECOND),
                    attempt_count = attempt_count + 1,
                    last_error = NULL
                WHERE outbox_id = :outboxId
                  AND (
                    (status = 'PENDING' AND next_attempt_at <= CURRENT_TIMESTAMP(6))
                    OR (status = 'PROCESSING' AND lease_until < CURRENT_TIMESTAMP(6))
                  )
                """, new MapSqlParameterSource()
                .addValue("outboxId", outboxId)
                .addValue("token", token));
        if (updated != 1) {
            return Optional.empty();
        }
        List<Claim> claims = jdbc.query("""
                SELECT outbox_id, payment_link_id, source_version,
                       observed_status, claim_token, attempt_count
                FROM payment_link_return_reconciliation_outbox
                WHERE outbox_id = :outboxId AND claim_token = :token
                """, new MapSqlParameterSource()
                .addValue("outboxId", outboxId)
                .addValue("token", token),
                (rs, rowNum) -> new Claim(
                        rs.getLong("outbox_id"),
                        rs.getLong("payment_link_id"),
                        rs.getLong("source_version"),
                        rs.getString("observed_status"),
                        rs.getString("claim_token"),
                        rs.getInt("attempt_count")));
        return claims.stream().findFirst();
    }

    public boolean markSucceeded(Claim claim) {
        return jdbc.update("""
                UPDATE payment_link_return_reconciliation_outbox
                SET status = 'SUCCEEDED', claim_token = NULL, lease_until = NULL,
                    processed_at = CURRENT_TIMESTAMP(6), last_error = NULL
                WHERE outbox_id = :outboxId
                  AND status = 'PROCESSING'
                  AND claim_token = :token
                """, new MapSqlParameterSource()
                .addValue("outboxId", claim.outboxId())
                .addValue("token", claim.claimToken())) == 1;
    }

    public boolean markRetry(Claim claim, LocalDateTime nextAttemptAt, String error) {
        return jdbc.update("""
                UPDATE payment_link_return_reconciliation_outbox
                SET status = 'PENDING', claim_token = NULL, lease_until = NULL,
                    next_attempt_at = :nextAttemptAt, last_error = :error
                WHERE outbox_id = :outboxId
                  AND status = 'PROCESSING'
                  AND claim_token = :token
                """, new MapSqlParameterSource()
                .addValue("outboxId", claim.outboxId())
                .addValue("token", claim.claimToken())
                .addValue("nextAttemptAt", Timestamp.valueOf(nextAttemptAt))
                .addValue("error", error)) == 1;
    }

    public record Claim(
            long outboxId,
            long paymentLinkId,
            long sourceVersion,
            String observedStatus,
            String claimToken,
            int attemptCount
    ) {
    }
}
