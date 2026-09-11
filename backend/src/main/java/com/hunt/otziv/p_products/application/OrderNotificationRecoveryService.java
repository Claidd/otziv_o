package com.hunt.otziv.p_products.application;

import com.hunt.otziv.p_products.api.OrderNotificationRecovery;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class OrderNotificationRecoveryService implements OrderNotificationRecovery {
    private final OrderRepository orders;
    private final JdbcTemplate jdbc;

    @Override
    public Optional<LegacyCycle> lockLegacyCycle(long orderId) {
        return eligibleOrder(orderId).map(order -> new LegacyCycle(order.getId(),
                order.getStatus() == null ? null : order.getStatus().getTitle(), order.getStatusChangedAt()));
    }

    @Override
    public boolean beginLegacyCycle(long orderId, LocalDateTime expectedStartedAt) {
        var order = eligibleOrder(orderId).orElse(null);
        if (order == null || !Objects.equals(order.getStatusChangedAt(), expectedStartedAt)) return false;
        order.setClientMessageGeneration(1);
        orders.save(order);
        return true;
    }

    private Optional<Order> eligibleOrder(long orderId) {
        var order = orders.findByIdForMutation(orderId).orElse(null);
        if (order == null || order.getClientMessageGeneration() != 0 || order.getStatusChangedAt() == null)
            return Optional.empty();
        var cutovers = jdbc.query("SELECT installed_on FROM flyway_schema_history WHERE version='1.10.305' AND success=TRUE",
                (rs, row) -> rs.getTimestamp(1).toLocalDateTime());
        if (cutovers.size() != 1 || !order.getStatusChangedAt().isAfter(cutovers.getFirst())) return Optional.empty();
        Integer occurrences = jdbc.queryForObject("SELECT COUNT(*) FROM order_client_message_occurrences WHERE order_id=?",
                Integer.class, orderId);
        return occurrences != null && occurrences == 0 ? Optional.of(order) : Optional.empty();
    }
}
