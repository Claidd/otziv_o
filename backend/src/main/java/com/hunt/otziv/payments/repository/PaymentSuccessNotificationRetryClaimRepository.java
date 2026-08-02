package com.hunt.otziv.payments.repository;

import java.time.Duration;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC persistence for the short-lived payment-success notification lease.
 * Every state transition is fenced by the opaque token installed at claim
 * time. Transaction boundaries are owned by the corresponding service.
 */
@Repository
@RequiredArgsConstructor
public class PaymentSuccessNotificationRetryClaimRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public boolean tryAcquire(
            long paymentLinkId,
            String processingToken,
            String processingOwner,
            Duration leaseDuration
    ) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("paymentLinkId", paymentLinkId)
                .addValue("processingToken", processingToken)
                .addValue("processingOwner", processingOwner)
                .addValue("leaseMicros", leaseDuration.toNanos() / 1_000L);
        jdbc.update("""
                INSERT INTO payment_success_notification_retry_claims (
                    payment_link_id,
                    processing_token,
                    processing_owner,
                    processing_started_at,
                    processing_lease_until
                ) VALUES (
                    :paymentLinkId,
                    :processingToken,
                    :processingOwner,
                    CURRENT_TIMESTAMP(6),
                    TIMESTAMPADD(MICROSECOND, :leaseMicros, CURRENT_TIMESTAMP(6))
                )
                ON DUPLICATE KEY UPDATE
                    processing_token = IF(
                        processing_lease_until <= CURRENT_TIMESTAMP(6),
                        :processingToken,
                        processing_token
                    ),
                    processing_owner = IF(
                        processing_lease_until <= CURRENT_TIMESTAMP(6),
                        :processingOwner,
                        processing_owner
                    ),
                    processing_started_at = IF(
                        processing_lease_until <= CURRENT_TIMESTAMP(6),
                        CURRENT_TIMESTAMP(6),
                        processing_started_at
                    ),
                    processing_lease_until = IF(
                        processing_lease_until <= CURRENT_TIMESTAMP(6),
                        TIMESTAMPADD(MICROSECOND, :leaseMicros, CURRENT_TIMESTAMP(6)),
                        processing_lease_until
                    )
                """, parameters);
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM payment_success_notification_retry_claims
                    WHERE payment_link_id = :paymentLinkId
                      AND processing_token = :processingToken
                      AND processing_lease_until > CURRENT_TIMESTAMP(6)
                )
                """, parameters, Boolean.class));
    }

    public boolean lockRetryEligiblePaymentLink(long paymentLinkId) {
        return !jdbc.query("""
                SELECT id
                FROM payment_links
                WHERE id = :paymentLinkId
                  AND status = 'CONFIRMED'
                  AND payment_success_notified_at IS NULL
                  AND payment_success_notification_retry_eligible = 1
                FOR UPDATE SKIP LOCKED
                """, Map.of("paymentLinkId", paymentLinkId), (resultSet, rowNum) ->
                resultSet.getLong("id")).isEmpty();
    }

    public boolean lockPaymentLinkForFinalization(long paymentLinkId) {
        return !jdbc.query("""
                SELECT id
                FROM payment_links
                WHERE id = :paymentLinkId
                FOR UPDATE
                """, Map.of("paymentLinkId", paymentLinkId), (resultSet, rowNum) ->
                resultSet.getLong("id")).isEmpty();
    }

    public boolean lockOwnedClaim(long paymentLinkId, String processingToken) {
        return !jdbc.query("""
                SELECT payment_link_id
                FROM payment_success_notification_retry_claims
                WHERE payment_link_id = :paymentLinkId
                  AND processing_token = :processingToken
                  AND processing_lease_until > CURRENT_TIMESTAMP(6)
                FOR UPDATE
                """, claimFence(paymentLinkId, processingToken), (resultSet, rowNum) ->
                resultSet.getLong("payment_link_id")).isEmpty();
    }

    public boolean markSucceeded(long paymentLinkId) {
        return jdbc.update("""
                UPDATE payment_links
                SET payment_success_notified_at = CURRENT_TIMESTAMP(6),
                    payment_success_notification_error = NULL,
                    payment_success_notification_retry_eligible = 0,
                    row_version = row_version + 1,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE id = :paymentLinkId
                  AND status = 'CONFIRMED'
                  AND payment_success_notified_at IS NULL
                  AND payment_success_notification_retry_eligible = 1
                """, Map.of("paymentLinkId", paymentLinkId)) == 1;
    }

    public boolean markFailed(long paymentLinkId, String error) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("paymentLinkId", paymentLinkId)
                .addValue("error", error);
        return jdbc.update("""
                UPDATE payment_links
                SET payment_success_notification_error = :error,
                    payment_success_notification_retry_eligible = 1,
                    row_version = row_version + 1,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE id = :paymentLinkId
                  AND status = 'CONFIRMED'
                  AND payment_success_notified_at IS NULL
                  AND payment_success_notification_retry_eligible = 1
                """, parameters) == 1;
    }

    public boolean release(long paymentLinkId, String processingToken) {
        return jdbc.update("""
                DELETE FROM payment_success_notification_retry_claims
                WHERE payment_link_id = :paymentLinkId
                  AND processing_token = :processingToken
                """, claimFence(paymentLinkId, processingToken)) == 1;
    }

    private Map<String, Object> claimFence(long paymentLinkId, String processingToken) {
        return Map.of(
                "paymentLinkId", paymentLinkId,
                "processingToken", processingToken
        );
    }
}
