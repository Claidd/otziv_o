package com.hunt.otziv.manager_daily_summary.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Current owners of audit sources. Like the control access rule (a source belongs to its
 * current manager), an order belongs to its manager, or to its company's manager when the
 * order has none.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ManagerAuditSourceOwners {

    private final NamedParameterJdbcTemplate jdbc;

    /** Current manager of each order; orders that are unknown or unavailable are absent. */
    public Map<Long, Long> currentOrderManagers(Collection<Long> orderIds) {
        List<Long> ids = orderIds == null ? List.of()
                : orderIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        try {
            Map<Long, Long> result = new HashMap<>();
            jdbc.query("""
                    SELECT o.order_id, COALESCE(o.order_manager, c.company_manager) AS manager_id
                    FROM orders o
                    LEFT JOIN companies c ON c.company_id = o.order_company
                    WHERE o.order_id IN (:ids)
                    """, new MapSqlParameterSource("ids", ids), row -> {
                long managerId = row.getLong("manager_id");
                if (!row.wasNull()) result.put(row.getLong("order_id"), managerId);
            });
            return Map.copyOf(result);
        } catch (DataAccessException unavailable) {
            log.warn("Current order owners are unavailable for {} orders: {}", ids.size(), unavailable.getMessage());
            return Map.of();
        }
    }

    /** The user account of a manager, or null when unknown. */
    public Long managerUserId(Long managerId) {
        if (managerId == null) return null;
        try {
            List<Long> rows = jdbc.queryForList("SELECT user_id FROM managers WHERE manager_id = :id",
                    new MapSqlParameterSource("id", managerId), Long.class);
            return rows.isEmpty() ? null : rows.getFirst();
        } catch (DataAccessException unavailable) {
            log.warn("Manager {} account is unavailable: {}", managerId, unavailable.getMessage());
            return null;
        }
    }
}
