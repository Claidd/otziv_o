package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.p_products.api.OrderDeliveryControlContext.Snapshot;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Carries an explicit receipt repair, never the continuously renewed time of ordinary automatic reminders. */
@Service
@RequiredArgsConstructor
class ManagerControlRepairContactCarry {
    static final String CONFIRMED_REPAIR = "Предыдущая отправка подтверждена по квитанции";
    private final JdbcTemplate jdbc;

    record Contact(LocalDateTime touchedAt, LocalDateTime followUpAt) { }

    Optional<Contact> latest(Snapshot order) {
        if (order == null || order.orderId() == null || order.managerId() == null)
            return Optional.empty();
        LocalDateTime cycle = order.cycleAt();
        if (cycle == null || LocalDateTime.MIN.equals(cycle)) return Optional.empty();
        return jdbc.query("""
                SELECT i.last_manual_touch_at,i.follow_up_at
                FROM manager_daily_control_concrete_items i
                JOIN manager_daily_controls c ON c.daily_control_id=i.control_id
                WHERE i.entity_type='ORDER' AND i.entity_id=? AND c.manager_id=?
                  AND i.item_status='ACTION_TAKEN' AND i.automatic_resolution=FALSE
                  AND i.last_manual_touch_at>=? AND i.follow_up_at>CURRENT_TIMESTAMP(6)
                  AND i.comment LIKE ?
                  AND NOT EXISTS (SELECT 1 FROM scheduled_client_message_state pending
                    WHERE pending.order_id=i.entity_id AND pending.target_key=? AND pending.state_status='ACTIVE'
                      AND pending.delivery_status IN ('UNKNOWN','PREPARED'))
                  AND EXISTS (
                    SELECT 1 FROM scheduled_client_message_state s
                    WHERE s.order_id=i.entity_id AND s.target_key=? AND s.state_status IN ('ACTIVE','DONE')
                      AND s.sent_count>0 AND s.last_success_at IS NOT NULL
                      AND (s.last_error_code IS NULL OR s.last_error_code='')
                      AND (s.delivery_status IS NULL OR s.delivery_status='SENT')
                      AND (s.last_success_at BETWEEN ? AND i.last_manual_touch_at OR EXISTS (
                        SELECT 1 FROM scheduled_client_message_attempts a WHERE a.state_id=s.state_id
                          AND a.attempt_status='SENT' AND a.attempted_at BETWEEN ? AND i.last_manual_touch_at)))
                ORDER BY i.last_manual_touch_at DESC,i.control_entity_id DESC LIMIT 1
                """, (rs, n) -> new Contact(rs.getTimestamp(1).toLocalDateTime(), rs.getTimestamp(2).toLocalDateTime()),
                order.orderId(), order.managerId(), cycle, CONFIRMED_REPAIR + "%",
                "order:" + order.orderId() + ":" + cycle.withNano(0),
                "order:" + order.orderId() + ":" + cycle.withNano(0), cycle, cycle).stream().findFirst();
    }
}
