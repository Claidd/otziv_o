package com.hunt.otziv.p_products.service;

import com.hunt.otziv.p_products.api.OrderDeliveryControlContext;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OrderDeliveryControlContextService implements OrderDeliveryControlContext {
    private final OrderRepository orders;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Override @Transactional(readOnly = true)
    public Optional<Snapshot> read(Long orderId) { return orders.findById(orderId).map(this::snapshot); }
    @Override @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Snapshot> lock(Long orderId) {
        // A current locking read must bypass an Order already cached before receipt-recovery I/O.
        return jdbc.query("""
                SELECT o.order_id,o.order_manager,s.order_status_title,o.client_message_generation,
                  COALESCE(o.order_status_changed_at,CAST(o.order_changed AS DATETIME),CAST(o.order_created AS DATETIME)) cycle_at,
                  o.order_waiting_for_client,o.order_waiting_for_client_changed_at
                FROM orders o LEFT JOIN order_statuses s ON s.order_status_id=o.order_status
                WHERE o.order_id=? FOR UPDATE
                """, (rs, n) -> new Snapshot(rs.getLong(1), rs.getObject(2, Long.class),
                        rs.getString(3) == null ? "" : rs.getString(3), rs.getLong(4),
                        rs.getTimestamp(5) == null ? LocalDateTime.MIN : rs.getTimestamp(5).toLocalDateTime(),
                        rs.getBoolean(6), rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toLocalDateTime()), orderId)
                .stream().findFirst();
    }
    private Snapshot snapshot(Order order) {
        LocalDateTime cycle = order.getStatusChangedAt() != null ? order.getStatusChangedAt()
                : order.getChanged() != null ? order.getChanged().atStartOfDay()
                : order.getCreated() != null ? order.getCreated().atStartOfDay() : LocalDateTime.MIN;
        return new Snapshot(order.getId(), order.notificationManagerId(),
                order.getStatus() == null ? "" : order.getStatus().getTitle(), order.getClientMessageGeneration(),
                cycle, order.isWaitingForClient(), order.getWaitingForClientChangedAt());
    }
}
