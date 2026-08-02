package com.hunt.otziv.r_review.capability;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Locks the archived counterpart of a review-check resource while a capability
 * is being managed. Live resources are locked through the canonical
 * {@code orders} row by {@link ReviewCheckCapabilityMutationService}.
 */
@Repository
@RequiredArgsConstructor
public class ReviewCheckCapabilityResourceRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public Optional<ArchivedResourceBinding> findArchivedByOrderDetailIdForUpdate(UUID orderDetailId) {
        if (orderDetailId == null) {
            return Optional.empty();
        }
        return jdbc.query("""
                SELECT
                    aod.order_detail_order AS order_id,
                    ao.order_manager AS manager_id,
                    ao.order_worker AS worker_id
                FROM archive_order_details aod
                JOIN archive_orders ao ON ao.order_id = aod.order_detail_order
                WHERE aod.order_detail_id = UUID_TO_BIN(:orderDetailId)
                  AND ao.restored_at IS NULL
                LIMIT 1
                FOR UPDATE
                """, new MapSqlParameterSource("orderDetailId", orderDetailId.toString()),
                (rs, rowNumber) -> new ArchivedResourceBinding(
                        nullableLong(rs, "order_id"),
                        nullableLong(rs, "manager_id"),
                        nullableLong(rs, "worker_id")
                )).stream().findFirst();
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    public record ArchivedResourceBinding(Long orderId, Long managerId, Long workerId) {
    }
}
