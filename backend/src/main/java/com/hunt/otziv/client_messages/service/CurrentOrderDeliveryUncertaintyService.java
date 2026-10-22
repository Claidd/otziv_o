package com.hunt.otziv.client_messages.service;

import com.hunt.otziv.client_messages.api.CurrentOrderDeliveryUncertainty;
import com.hunt.otziv.client_messages.model.ScheduledMessageStateStatus;
import com.hunt.otziv.client_messages.repository.ScheduledClientMessageStateRepository;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CurrentOrderDeliveryUncertaintyService implements CurrentOrderDeliveryUncertainty {
    private final ScheduledClientMessageStateRepository states;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    public boolean hasUncertainCurrentDelivery(long orderId, String orderTarget, String waitingTarget) {
        return states.findByOrderIdIn(List.of(orderId)).stream()
                .filter(s -> s.getStatus() == ScheduledMessageStateStatus.ACTIVE)
                .filter(s -> Objects.equals(orderTarget, s.getTargetKey())
                        || (waitingTarget != null && Objects.equals(waitingTarget, s.getTargetKey())))
                .anyMatch(ClientMessageStateSafety::blocksAutomaticRearm);
    }
    public boolean requiresReconciliation(long stateId) {
        return states.findById(stateId).map(ClientMessageStateSafety::blocksAutomaticRearm).orElse(false);
    }
    public boolean hasRecentCurrentDelivery(long orderId, String orderTarget, String waitingTarget, java.time.LocalDateTime since) {
        return recent(orderId, orderTarget, waitingTarget, since, false);
    }
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public boolean hasRecentCurrentDeliveryForUpdate(long orderId, String orderTarget, String waitingTarget, java.time.LocalDateTime since) {
        return recent(orderId, orderTarget, waitingTarget, since, true);
    }
    private boolean recent(long orderId, String orderTarget, String waitingTarget, java.time.LocalDateTime since, boolean lock) {
        return !jdbc.query("""
                SELECT state_id FROM scheduled_client_message_state
                WHERE order_id=? AND (target_key=? OR (? IS NOT NULL AND target_key=?))
                  AND sent_count>0 AND last_success_at>?
                ORDER BY state_id
                """ + (lock ? " FOR UPDATE" : ""), (rs, n) -> rs.getLong(1), orderId, orderTarget, waitingTarget, waitingTarget, since).isEmpty();
    }
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public boolean hasUncertainCurrentDeliveryForUpdate(long orderId, String orderTarget, String waitingTarget, boolean includeClaimed) {
        // Do not reuse an ORM entity or a repeatable-read snapshot taken before waiting for the order lock.
        return !jdbc.query("""
                SELECT state_id FROM scheduled_client_message_state
                WHERE order_id=? AND state_status='ACTIVE'
                  AND (target_key=? OR (? IS NOT NULL AND target_key=?))
                  AND (UPPER(TRIM(COALESCE(delivery_status,''))) IN ('PREPARED','UNKNOWN')
                    OR LOWER(TRIM(COALESCE(last_error_code,''))) IN
                      ('state_transaction_outcome_uncertain','legacy_operation_unverified')
                    OR (LOWER(TRIM(COALESCE(last_error_code,'')))='state_transaction_in_progress'
                      AND (? OR UPPER(TRIM(COALESCE(delivery_status,'')))<>'CLAIMED')))
                ORDER BY state_id FOR UPDATE
                """, (rs, n) -> rs.getLong(1), orderId, orderTarget, waitingTarget, waitingTarget, includeClaimed).isEmpty();
    }
}
