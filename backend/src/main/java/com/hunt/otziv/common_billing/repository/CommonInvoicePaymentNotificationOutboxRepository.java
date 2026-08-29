package com.hunt.otziv.common_billing.repository;

import com.hunt.otziv.contractor_payments.model.ContractorRecipientType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC outbox for common-invoice client and actual-recipient notifications. */
@Repository
@RequiredArgsConstructor
public class CommonInvoicePaymentNotificationOutboxRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public boolean enqueueClient(long invoiceId) {
        return jdbc.update("""
                INSERT IGNORE INTO common_invoice_payment_notification_outbox (
                    invoice_id,
                    notification_kind,
                    notification_key
                ) VALUES (
                    :invoiceId,
                    'CLIENT',
                    'CLIENT'
                )
                """, Map.of("invoiceId", invoiceId)) == 1;
    }

    public int enqueueRecipients(long invoiceId) {
        return jdbc.update("""
                INSERT IGNORE INTO common_invoice_payment_notification_outbox (
                    invoice_id,
                    notification_kind,
                    notification_key,
                    attribution_id,
                    recipient_type,
                    recipient_user_id,
                    amount_kopecks,
                    invoice_title,
                    order_count,
                    actor,
                    confirmed_at
                )
                SELECT attribution.common_invoice_id,
                       'RECIPIENT',
                       CONCAT('ATTRIBUTION:', attribution.id),
                       attribution.id,
                       attribution.actual_recipient_type,
                       attribution.actual_recipient_user_id,
                       attribution.amount_kopecks,
                       invoice.title,
                       (
                           SELECT COUNT(*)
                           FROM common_invoice_orders invoice_order
                           WHERE invoice_order.invoice_id = invoice.invoice_id
                             AND invoice_order.paid = 1
                       ),
                       attribution.actor,
                       attribution.effective_at
                FROM contractor_actual_payment_attributions attribution
                JOIN common_invoices invoice
                  ON invoice.invoice_id = attribution.common_invoice_id
                WHERE attribution.source_kind = 'COMMON_INVOICE'
                  AND attribution.accounting_mode = 'LIVE'
                  AND attribution.common_invoice_id = :invoiceId
                  AND attribution.actual_recipient_type IN ('OWNER', 'MANAGER', 'SPECIALIST')
                  AND attribution.actual_recipient_user_id IS NOT NULL
                  AND attribution.amount_kopecks > 0
                  AND attribution.correction_of_id IS NULL
                """, Map.of("invoiceId", invoiceId));
    }

    public List<Long> findDueDeliveryIds(int limit) {
        return jdbc.query("""
                SELECT delivery_id
                FROM common_invoice_payment_notification_outbox
                WHERE sent_at IS NULL
                  AND next_attempt_at <= CURRENT_TIMESTAMP(6)
                  AND (
                      processing_lease_until IS NULL
                      OR processing_lease_until <= CURRENT_TIMESTAMP(6)
                  )
                ORDER BY next_attempt_at, delivery_id
                LIMIT :limit
                """, Map.of("limit", limit), (resultSet, rowNum) ->
                resultSet.getLong("delivery_id"));
    }

    public Optional<Delivery> tryAcquire(
            long deliveryId,
            String processingToken,
            String processingOwner,
            Duration leaseDuration
    ) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("deliveryId", deliveryId)
                .addValue("processingToken", processingToken)
                .addValue("processingOwner", processingOwner)
                .addValue("leaseMicros", leaseDuration.toNanos() / 1_000L);
        int updated = jdbc.update("""
                UPDATE common_invoice_payment_notification_outbox
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
                WHERE delivery_id = :deliveryId
                  AND sent_at IS NULL
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
                SELECT delivery_id,
                       invoice_id,
                       notification_kind,
                       attribution_id,
                       recipient_type,
                       recipient_user_id,
                       amount_kopecks,
                       invoice_title,
                       order_count,
                       actor,
                       confirmed_at,
                       attempt_count,
                       processing_token
                FROM common_invoice_payment_notification_outbox
                WHERE delivery_id = :deliveryId
                  AND processing_token = :processingToken
                  AND processing_lease_until > CURRENT_TIMESTAMP(6)
                """, parameters, (resultSet, rowNum) -> mapDelivery(resultSet))
                .stream()
                .findFirst();
    }

    public boolean markSent(long deliveryId, String processingToken) {
        return jdbc.update("""
                UPDATE common_invoice_payment_notification_outbox
                SET sent_at = CURRENT_TIMESTAMP(6),
                    processing_token = NULL,
                    processing_owner = NULL,
                    processing_started_at = NULL,
                    processing_lease_until = NULL,
                    last_error = NULL,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE delivery_id = :deliveryId
                  AND processing_token = :processingToken
                  AND processing_lease_until > CURRENT_TIMESTAMP(6)
                  AND sent_at IS NULL
                """, fence(deliveryId, processingToken)) == 1;
    }

    public boolean markSkipped(long deliveryId, String processingToken, String reason) {
        MapSqlParameterSource parameters = new MapSqlParameterSource(fence(deliveryId, processingToken))
                .addValue("reason", reason);
        return jdbc.update("""
                UPDATE common_invoice_payment_notification_outbox
                SET sent_at = CURRENT_TIMESTAMP(6),
                    processing_token = NULL,
                    processing_owner = NULL,
                    processing_started_at = NULL,
                    processing_lease_until = NULL,
                    last_error = :reason,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE delivery_id = :deliveryId
                  AND processing_token = :processingToken
                  AND processing_lease_until > CURRENT_TIMESTAMP(6)
                  AND sent_at IS NULL
                """, parameters) == 1;
    }

    public boolean markFailed(
            long deliveryId,
            String processingToken,
            String error,
            Duration retryDelay
    ) {
        MapSqlParameterSource parameters = new MapSqlParameterSource(fence(deliveryId, processingToken))
                .addValue("error", error)
                .addValue("retryMicros", retryDelay.toNanos() / 1_000L);
        return jdbc.update("""
                UPDATE common_invoice_payment_notification_outbox
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
                WHERE delivery_id = :deliveryId
                  AND processing_token = :processingToken
                  AND processing_lease_until > CURRENT_TIMESTAMP(6)
                  AND sent_at IS NULL
                """, parameters) == 1;
    }

    public boolean markClientInvoiceNotified(long invoiceId) {
        return jdbc.update("""
                UPDATE common_invoices
                SET payment_success_notified_at = COALESCE(
                        payment_success_notified_at,
                        CURRENT_TIMESTAMP(6)
                    ),
                    payment_success_notification_error = NULL,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE invoice_id = :invoiceId
                  AND status = 'PAID'
                """, Map.of("invoiceId", invoiceId)) == 1;
    }

    public boolean markClientInvoiceFailed(long invoiceId, String error) {
        return jdbc.update("""
                UPDATE common_invoices
                SET payment_success_notification_error = :error,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE invoice_id = :invoiceId
                  AND status = 'PAID'
                  AND payment_success_notified_at IS NULL
                """, Map.of("invoiceId", invoiceId, "error", error)) == 1;
    }

    private Delivery mapDelivery(ResultSet resultSet) throws SQLException {
        String rawRecipientType = resultSet.getString("recipient_type");
        return new Delivery(
                resultSet.getLong("delivery_id"),
                resultSet.getLong("invoice_id"),
                NotificationKind.valueOf(resultSet.getString("notification_kind")),
                nullableLong(resultSet, "attribution_id"),
                rawRecipientType == null ? null : ContractorRecipientType.valueOf(rawRecipientType),
                nullableLong(resultSet, "recipient_user_id"),
                nullableLong(resultSet, "amount_kopecks"),
                resultSet.getString("invoice_title"),
                nullableInteger(resultSet, "order_count"),
                resultSet.getString("actor"),
                resultSet.getObject("confirmed_at", LocalDateTime.class),
                resultSet.getInt("attempt_count"),
                resultSet.getString("processing_token")
        );
    }

    private Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private Integer nullableInteger(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    private Map<String, Object> fence(long deliveryId, String processingToken) {
        return Map.of(
                "deliveryId", deliveryId,
                "processingToken", processingToken
        );
    }

    public enum NotificationKind {
        CLIENT,
        RECIPIENT
    }

    public record Delivery(
            long deliveryId,
            long invoiceId,
            NotificationKind kind,
            Long attributionId,
            ContractorRecipientType recipientType,
            Long recipientUserId,
            Long amountKopecks,
            String invoiceTitle,
            Integer orderCount,
            String actor,
            LocalDateTime confirmedAt,
            int attemptCount,
            String processingToken
    ) {
    }
}
