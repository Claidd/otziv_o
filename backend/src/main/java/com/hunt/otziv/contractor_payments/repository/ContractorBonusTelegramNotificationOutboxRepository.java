package com.hunt.otziv.contractor_payments.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Transactional bonus delivery intents and fenced claims for Telegram. */
@Repository
@RequiredArgsConstructor
public class ContractorBonusTelegramNotificationOutboxRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public boolean enqueue(long adjustmentId) {
        return jdbc.update("""
                INSERT INTO contractor_bonus_telegram_notification_outbox (
                    adjustment_id, recipient_user_id
                )
                SELECT adjustment.id, profile.user_id
                FROM contractor_accrual_adjustments adjustment
                JOIN contractor_payment_profiles profile ON profile.id = adjustment.profile_id
                WHERE adjustment.id = :adjustmentId
                  AND adjustment.kind = 'BONUS'
                """, Map.of("adjustmentId", adjustmentId)) == 1;
    }

    public List<Long> findDueAdjustmentIds(int limit) {
        return jdbc.query("""
                SELECT adjustment_id
                FROM contractor_bonus_telegram_notification_outbox
                WHERE completed_at IS NULL
                  AND next_attempt_at <= CURRENT_TIMESTAMP(6)
                  AND (processing_lease_until IS NULL
                       OR processing_lease_until <= CURRENT_TIMESTAMP(6))
                ORDER BY next_attempt_at, adjustment_id
                LIMIT :limit
                """, Map.of("limit", limit), (resultSet, rowNum) ->
                resultSet.getLong("adjustment_id"));
    }

    public Optional<Delivery> tryAcquire(
            long adjustmentId,
            String processingToken,
            String processingOwner,
            Duration leaseDuration
    ) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("adjustmentId", adjustmentId)
                .addValue("processingToken", processingToken)
                .addValue("processingOwner", processingOwner)
                .addValue("leaseMicros", leaseDuration.toNanos() / 1_000L);
        int updated = jdbc.update("""
                UPDATE contractor_bonus_telegram_notification_outbox
                SET processing_token = :processingToken,
                    processing_owner = :processingOwner,
                    processing_started_at = CURRENT_TIMESTAMP(6),
                    processing_lease_until = TIMESTAMPADD(
                        MICROSECOND, :leaseMicros, CURRENT_TIMESTAMP(6)
                    ),
                    attempt_count = attempt_count + 1,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE adjustment_id = :adjustmentId
                  AND completed_at IS NULL
                  AND next_attempt_at <= CURRENT_TIMESTAMP(6)
                  AND (processing_lease_until IS NULL
                       OR processing_lease_until <= CURRENT_TIMESTAMP(6))
                """, parameters);
        if (updated != 1) {
            return Optional.empty();
        }
        return jdbc.query("""
                SELECT adjustment_id, attempt_count, processing_token
                FROM contractor_bonus_telegram_notification_outbox
                WHERE adjustment_id = :adjustmentId
                  AND processing_token = :processingToken
                  AND processing_lease_until > CURRENT_TIMESTAMP(6)
                """, parameters, (resultSet, rowNum) -> new Delivery(
                resultSet.getLong("adjustment_id"),
                resultSet.getInt("attempt_count"),
                resultSet.getString("processing_token")
        )).stream().findFirst();
    }

    /** Read the current employee chat and reversal state immediately before delivery. */
    public Optional<Bonus> findBonus(long adjustmentId) {
        return jdbc.query("""
                SELECT adjustment.id AS adjustment_id,
                       adjustment.kind AS kind,
                       adjustment.signed_amount_kopecks AS amount_kopecks,
                       adjustment.effective_on AS effective_on,
                       adjustment.reference_month AS reference_month,
                       adjustment.reason AS reason,
                       employee.id AS user_id,
                       employee.active AS active,
                       employee.telegram_chat_id AS telegram_chat_id,
                       EXISTS (
                           SELECT 1
                           FROM contractor_accrual_adjustments reversal
                           WHERE reversal.reversal_of_id = adjustment.id
                       ) AS reversed
                FROM contractor_accrual_adjustments adjustment
                JOIN contractor_bonus_telegram_notification_outbox notification
                  ON notification.adjustment_id = adjustment.id
                JOIN users employee ON employee.id = notification.recipient_user_id
                WHERE adjustment.id = :adjustmentId
                """, Map.of("adjustmentId", adjustmentId),
                (resultSet, rowNum) -> mapBonus(resultSet)).stream().findFirst();
    }

    public boolean markSent(Delivery delivery) {
        return jdbc.update("""
                UPDATE contractor_bonus_telegram_notification_outbox
                SET completed_at = CURRENT_TIMESTAMP(6),
                    delivery_state = 'SENT',
                    processing_token = NULL,
                    processing_owner = NULL,
                    processing_started_at = NULL,
                    processing_lease_until = NULL,
                    last_error = NULL,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE adjustment_id = :adjustmentId
                  AND processing_token = :processingToken
                  AND processing_lease_until > CURRENT_TIMESTAMP(6)
                  AND completed_at IS NULL
                """, fence(delivery)) == 1;
    }

    public boolean markSkipped(Delivery delivery, String reason) {
        MapSqlParameterSource parameters = new MapSqlParameterSource(fence(delivery))
                .addValue("reason", reason);
        return jdbc.update("""
                UPDATE contractor_bonus_telegram_notification_outbox
                SET completed_at = CURRENT_TIMESTAMP(6),
                    delivery_state = 'SKIPPED',
                    processing_token = NULL,
                    processing_owner = NULL,
                    processing_started_at = NULL,
                    processing_lease_until = NULL,
                    last_error = :reason,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE adjustment_id = :adjustmentId
                  AND processing_token = :processingToken
                  AND processing_lease_until > CURRENT_TIMESTAMP(6)
                  AND completed_at IS NULL
                """, parameters) == 1;
    }

    public boolean markFailed(Delivery delivery, String error, Duration retryDelay) {
        MapSqlParameterSource parameters = new MapSqlParameterSource(fence(delivery))
                .addValue("error", error)
                .addValue("retryMicros", retryDelay.toNanos() / 1_000L);
        return jdbc.update("""
                UPDATE contractor_bonus_telegram_notification_outbox
                SET next_attempt_at = TIMESTAMPADD(
                        MICROSECOND, :retryMicros, CURRENT_TIMESTAMP(6)
                    ),
                    processing_token = NULL,
                    processing_owner = NULL,
                    processing_started_at = NULL,
                    processing_lease_until = NULL,
                    last_error = :error,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE adjustment_id = :adjustmentId
                  AND processing_token = :processingToken
                  AND processing_lease_until > CURRENT_TIMESTAMP(6)
                  AND completed_at IS NULL
                """, parameters) == 1;
    }

    private Bonus mapBonus(ResultSet resultSet) throws SQLException {
        long chatId = resultSet.getLong("telegram_chat_id");
        Long nullableChatId = resultSet.wasNull() ? null : chatId;
        return new Bonus(
                resultSet.getLong("adjustment_id"),
                resultSet.getString("kind"),
                resultSet.getLong("amount_kopecks"),
                resultSet.getObject("effective_on", LocalDate.class),
                resultSet.getObject("reference_month", LocalDate.class),
                resultSet.getString("reason"),
                resultSet.getLong("user_id"),
                resultSet.getBoolean("active"),
                nullableChatId,
                resultSet.getBoolean("reversed")
        );
    }

    private Map<String, Object> fence(Delivery delivery) {
        return Map.of(
                "adjustmentId", delivery.adjustmentId(),
                "processingToken", delivery.processingToken()
        );
    }

    public record Delivery(long adjustmentId, int attemptCount, String processingToken) {
    }

    public record Bonus(
            long adjustmentId,
            String kind,
            long amountKopecks,
            LocalDate effectiveOn,
            LocalDate referenceMonth,
            String reason,
            long userId,
            boolean active,
            Long telegramChatId,
            boolean reversed
    ) {
    }
}
