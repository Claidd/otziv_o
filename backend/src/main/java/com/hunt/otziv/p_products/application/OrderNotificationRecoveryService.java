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
        return eligibleOrder(orderId).map(candidate -> candidate.order()).map(order -> new LegacyCycle(order.getId(),
                order.getStatus() == null ? null : order.getStatus().getTitle(), order.getStatusChangedAt()));
    }

    @Override
    public boolean beginLegacyCycle(long orderId, LocalDateTime expectedStartedAt) {
        var candidate = eligibleOrder(orderId).orElse(null);
        if (candidate == null || !Objects.equals(candidate.order().getStatusChangedAt(), expectedStartedAt)) return false;
        var order = candidate.order();
        order.setClientMessageGeneration(candidate.nextGeneration());
        orders.save(order);
        return true;
    }

    private Optional<Candidate> eligibleOrder(long orderId) {
        var order = orders.findByIdForMutation(orderId).orElse(null);
        if (order == null || order.getClientMessageGeneration() != 0 || order.getStatusChangedAt() == null)
            return Optional.empty();
        var cutovers = jdbc.query("SELECT installed_on FROM flyway_schema_history WHERE version='1.10.305' AND success=TRUE",
                (rs, row) -> rs.getTimestamp(1).toLocalDateTime());
        if (cutovers.size() != 1 || !order.getStatusChangedAt().isAfter(cutovers.getFirst())) return Optional.empty();
        // A previous confirmed cycle is not evidence that this new action was sent.
        // Retain every old identity and advance beyond it; any unresolved or current-cycle history still fences recovery.
        var history = jdbc.query("SELECT business_generation,confirmed,updated_at FROM order_client_message_occurrences WHERE order_id=?",
                (rs, row) -> new PreviousOccurrence(rs.getLong(1), rs.getBoolean(2),
                        rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toLocalDateTime()), orderId);
        long previousGeneration = 0;
        for (var previous : history) {
            if (!previous.confirmed() || previous.generation() <= 0 || previous.generation() == Long.MAX_VALUE
                    || previous.updatedAt() == null || !previous.updatedAt().isBefore(order.getStatusChangedAt()))
                return Optional.empty();
            previousGeneration = Math.max(previousGeneration, previous.generation());
        }
        return Optional.of(new Candidate(order, previousGeneration + 1));
    }

    private record Candidate(Order order, long nextGeneration) {}
    private record PreviousOccurrence(long generation, boolean confirmed, LocalDateTime updatedAt) {}
}
