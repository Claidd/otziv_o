package com.hunt.otziv.client_messages.service;

import com.hunt.otziv.client_messages.model.*;
import com.hunt.otziv.client_messages.repository.ScheduledClientMessageAttemptRepository;
import com.hunt.otziv.client_messages.repository.ScheduledClientMessageStateRepository;
import com.hunt.otziv.p_products.api.OrderNotificationRecovery;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Repairs only a provably unprepared action belonging to a post-cutover business cycle. */
@Service
@RequiredArgsConstructor
public class LegacyOrderMessagePreparationRecovery {
    private final JdbcTemplate jdbc;
    private final OrderNotificationRecovery orders;
    private final ScheduledClientMessageStateRepository states;
    private final ScheduledClientMessageAttemptRepository attempts;
    private final ClientMessageTransactionRunner transactions;

    public void recoverDue(LocalDateTime now) {
        var ids = jdbc.query("""
                SELECT state_id FROM scheduled_client_message_state
                WHERE state_status='ACTIVE' AND scenario IN ('REVIEW_CHECK_DELIVERY_RETRY','PAYMENT_INVOICE_RETRY')
                    AND (last_error_code='legacy_operation_unverified' OR
                        (last_error_code='state_transaction_outcome_uncertain'
                            AND last_error_message LIKE '%Причина: legacy_operation_unverified'))
                    AND (locked_until IS NULL OR locked_until<=?)
                    AND (delivery_recovery_checked_at IS NULL OR delivery_recovery_checked_at<TIMESTAMPADD(MINUTE,-10,?))
                ORDER BY delivery_recovery_checked_at,state_id LIMIT 20
                """, (rs, row) -> rs.getLong(1), now, now);
        for (long id : ids) recover(id, now);
    }

    public boolean recover(long stateId, LocalDateTime now) {
        var snapshot = states.findById(stateId).orElse(null);
        if (snapshot == null || snapshot.getOrderId() == null) return false;
        long orderId = snapshot.getOrderId();
        return Boolean.TRUE.equals(transactions.callInNewTransaction(() -> {
            // Same canonical lock order as the normal worker and order transitions.
            // Read State only after acquiring Order, in a fresh persistence context.
            var cycle = orders.lockLegacyCycle(orderId).orElse(null);
            var state = states.findByIdForUpdate(stateId).orElse(null);
            if (state == null || state.getStatus() != ScheduledMessageStateStatus.ACTIVE
                    || !Objects.equals(state.getOrderId(), orderId)
                    || !ClientMessageStateSafety.isLegacyPreparationFailure(state)
                    || (state.getLockedUntil() != null && state.getLockedUntil().isAfter(now))) return false;
            state.setDeliveryRecoveryCheckedAt(now);
            if (!establishCurrentCycle(cycle, state)) {
                states.save(state);
                return false;
            }
            attempts.save(ScheduledClientMessageAttempt.builder().stateId(stateId).scenario(state.getScenario())
                    .targetType(state.getTargetType()).targetKey(state.getTargetKey()).companyId(state.getCompanyId())
                    .orderId(state.getOrderId()).status(ScheduledMessageAttemptStatus.SKIPPED).channel("system")
                    .errorCode("legacy_preparation_recovered")
                    .errorMessage("Подтвержден новый цикл заказа после миграции; операция и отправка ранее не создавались. Задача возвращена в очередь.")
                    .durationMs(0L).attemptedAt(now).build());
            state.setDeliveryStatus(null);
            state.setLastErrorCode(null);
            state.setLastErrorMessage(null);
            state.setLockedUntil(null);
            state.setNextAttemptAt(now);
            states.save(state);
            return true;
        }));
    }

    /** Caller holds Order then State; no provider calls or operation allocations happen here. */
    @Transactional(propagation=Propagation.MANDATORY)
    public boolean establishCurrentCycle(long orderId, ScheduledClientMessageState state) {
        return establishCurrentCycle(orders.lockLegacyCycle(orderId).orElse(null), state);
    }

    private boolean establishCurrentCycle(OrderNotificationRecovery.LegacyCycle cycle, ScheduledClientMessageState state) {
        if (cycle == null || state == null
                || state.getId() == null || state.getId() <= 0 || state.getStatus() != ScheduledMessageStateStatus.ACTIVE
                || !Objects.equals(cycle.orderId(), state.getOrderId()) || !hasNoDeliveryEvidence(state)
                || !isCurrentCycle(cycle, state)) return false;
        if (state.getCreatedAt() == null || state.getCreatedAt().isBefore(cycle.startedAt())) return false;
        // A missing operation alone is insufficient: retained attempts must prove preparation failed before a channel call.
        Integer otherAttempts = jdbc.queryForObject("""
                SELECT COUNT(*) FROM scheduled_client_message_attempts WHERE state_id=? AND NOT COALESCE((
                    attempt_status='FAILED' AND channel IS NULL AND (
                        error_code='legacy_operation_unverified' OR
                        (error_code='state_transaction_outcome_uncertain'
                            AND error_message LIKE '%Причина: legacy_operation_unverified'))), FALSE)
                """, Integer.class, state.getId());
        if (otherAttempts == null || otherAttempts != 0) return false;
        if (ClientMessageStateSafety.isLegacyPreparationFailure(state)) {
            Integer retained = jdbc.queryForObject("SELECT COUNT(*) FROM scheduled_client_message_attempts WHERE state_id=?",
                    Integer.class, state.getId());
            if (retained == null || retained == 0) return false;
        }
        return orders.beginLegacyCycle(cycle.orderId(), cycle.startedAt());
    }

    static boolean hasNoDeliveryEvidence(ScheduledClientMessageState state) {
        return state.getSentCount() == 0 && state.getLastSuccessAt() == null
                && (state.getDeliveryStatus() == null || "CLAIMED".equals(state.getDeliveryStatus()))
                && state.getDeliveryToken() == null && state.getDeliveryMessage() == null
                && state.getDeliveryEnvelope() == null && state.getDeliveryChannel() == null
                && state.getDeliveryTaskId() == null && state.getDeliveryPreparedAt() == null;
    }

    private static boolean isCurrentCycle(OrderNotificationRecovery.LegacyCycle cycle, ScheduledClientMessageState state) {
        String expectedStatus = state.getScenario() == ClientMessageScenario.REVIEW_CHECK_DELIVERY_RETRY ? "В проверку"
                : state.getScenario() == ClientMessageScenario.PAYMENT_INVOICE_RETRY ? "Опубликовано" : null;
        return expectedStatus != null && expectedStatus.equals(cycle.status())
                && cycle.startedAt() != null && state.getTargetType() == ClientMessageTargetType.ORDER
                && ("order:" + cycle.orderId() + ":" + cycle.startedAt().withNano(0)).equals(state.getTargetKey());
    }
}
