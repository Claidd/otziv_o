package com.hunt.otziv.payments.repository;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Durable and lease-fenced ordinary-order payment-route notifications. */
@Repository
@RequiredArgsConstructor
public class PaymentRouteChangeNotificationOutboxRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public boolean enqueue(
            long orderId,
            long paymentLinkId
    ) {
        return jdbc.update("""
                INSERT IGNORE INTO payment_route_change_notification_outbox (
                    payment_link_id,
                    order_id
                ) VALUES (
                    :paymentLinkId,
                    :orderId
                )
                """, new MapSqlParameterSource()
                .addValue("paymentLinkId", paymentLinkId)
                .addValue("orderId", orderId)) == 1;
    }

    public List<Long> findDuePaymentLinkIds(int limit) {
        return jdbc.query("""
                SELECT payment_link_id
                FROM payment_route_change_notification_outbox
                WHERE sent_at IS NULL
                  AND skipped_at IS NULL
                  AND next_attempt_at <= CURRENT_TIMESTAMP(6)
                  AND (
                      processing_lease_until IS NULL
                      OR processing_lease_until <= CURRENT_TIMESTAMP(6)
                  )
                ORDER BY next_attempt_at, payment_link_id
                LIMIT :limit
                """, Map.of("limit", limit), (resultSet, rowNum) ->
                resultSet.getLong("payment_link_id"));
    }

    public Optional<Delivery> tryAcquire(
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
        int updated = jdbc.update("""
                UPDATE payment_route_change_notification_outbox
                SET processing_token = :processingToken,
                    processing_owner = :processingOwner,
                    processing_started_at = CURRENT_TIMESTAMP(6),
                    processing_lease_until = TIMESTAMPADD(
                        MICROSECOND,
                        :leaseMicros,
                        CURRENT_TIMESTAMP(6)
                    ),
                    attempt_count = attempt_count + 1,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE payment_link_id = :paymentLinkId
                  AND sent_at IS NULL
                  AND skipped_at IS NULL
                  AND next_attempt_at <= CURRENT_TIMESTAMP(6)
                  AND (
                      processing_lease_until IS NULL
                      OR processing_lease_until <= CURRENT_TIMESTAMP(6)
                  )
                """, parameters);
        if (updated != 1) {
            return Optional.empty();
        }
        return jdbc.query("""
                SELECT payment_link_id,
                       order_id,
                       attempt_count,
                       processing_token
                FROM payment_route_change_notification_outbox
                WHERE payment_link_id = :paymentLinkId
                  AND processing_token = :processingToken
                  AND processing_lease_until > CURRENT_TIMESTAMP(6)
                  AND sent_at IS NULL
                  AND skipped_at IS NULL
                """, parameters, (resultSet, rowNum) -> new Delivery(
                        resultSet.getLong("payment_link_id"),
                        resultSet.getLong("order_id"),
                        resultSet.getInt("attempt_count"),
                        resultSet.getString("processing_token")
                )).stream().findFirst();
    }

    /** Must be called while the matching order row is locked for update. */
    public boolean isCurrentReplacement(long orderId, long paymentLinkId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM payment_links candidate
                    WHERE candidate.id = :paymentLinkId
                      AND candidate.order_id = :orderId
                      AND candidate.status IN (
                          'CREATED',
                          'INITIATED',
                          'AUTHORIZED',
                          'WAITING_MANUAL_PAYMENT',
                          'MANUAL_REPORTED',
                          'NEEDS_RECONCILIATION'
                      )
                      AND NOT EXISTS (
                          SELECT 1
                          FROM payment_links newer
                          WHERE newer.order_id = candidate.order_id
                            AND newer.status IN (
                                'CREATED',
                                'INITIATED',
                                'AUTHORIZED',
                                'WAITING_MANUAL_PAYMENT',
                                'MANUAL_REPORTED',
                                'NEEDS_RECONCILIATION'
                            )
                            AND (
                                newer.created_at > candidate.created_at
                                OR (
                                    newer.created_at = candidate.created_at
                                    AND newer.id > candidate.id
                                )
                            )
                      )
                )
                """, Map.of(
                        "orderId", orderId,
                        "paymentLinkId", paymentLinkId
                ), Boolean.class));
    }

    public boolean markSent(Delivery delivery) {
        return jdbc.update("""
                UPDATE payment_route_change_notification_outbox
                SET sent_at = CURRENT_TIMESTAMP(6),
                    processing_token = NULL,
                    processing_owner = NULL,
                    processing_started_at = NULL,
                    processing_lease_until = NULL,
                    last_error = NULL,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE payment_link_id = :paymentLinkId
                  AND processing_token = :processingToken
                  AND processing_lease_until > CURRENT_TIMESTAMP(6)
                  AND sent_at IS NULL
                  AND skipped_at IS NULL
                """, fence(delivery)) == 1;
    }

    public boolean markSkipped(Delivery delivery, String reason) {
        MapSqlParameterSource parameters = fence(delivery).addValue("reason", reason);
        return jdbc.update("""
                UPDATE payment_route_change_notification_outbox
                SET skipped_at = CURRENT_TIMESTAMP(6),
                    processing_token = NULL,
                    processing_owner = NULL,
                    processing_started_at = NULL,
                    processing_lease_until = NULL,
                    last_error = :reason,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE payment_link_id = :paymentLinkId
                  AND processing_token = :processingToken
                  AND processing_lease_until > CURRENT_TIMESTAMP(6)
                  AND sent_at IS NULL
                  AND skipped_at IS NULL
                """, parameters) == 1;
    }

    public boolean markFailed(Delivery delivery, String error, Duration retryDelay) {
        MapSqlParameterSource parameters = fence(delivery)
                .addValue("error", error)
                .addValue("retryMicros", retryDelay.toNanos() / 1_000L);
        return jdbc.update("""
                UPDATE payment_route_change_notification_outbox
                SET next_attempt_at = TIMESTAMPADD(
                        MICROSECOND,
                        :retryMicros,
                        CURRENT_TIMESTAMP(6)
                    ),
                    processing_token = NULL,
                    processing_owner = NULL,
                    processing_started_at = NULL,
                    processing_lease_until = NULL,
                    last_error = :error,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE payment_link_id = :paymentLinkId
                  AND processing_token = :processingToken
                  AND processing_lease_until > CURRENT_TIMESTAMP(6)
                  AND sent_at IS NULL
                  AND skipped_at IS NULL
                """, parameters) == 1;
    }

    private MapSqlParameterSource fence(Delivery delivery) {
        return new MapSqlParameterSource()
                .addValue("paymentLinkId", delivery.paymentLinkId())
                .addValue("processingToken", delivery.processingToken());
    }

    public record Delivery(
            long paymentLinkId,
            long orderId,
            int attemptCount,
            String processingToken
    ) {
    }
}
