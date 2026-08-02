package com.hunt.otziv.r_review.capability;

import com.hunt.otziv.p_products.review.OrderAggregateMutationLockService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Serializes mutations by review-check resource rather than by public token.
 *
 * <p>The caller must already own a transaction so the row lock remains held
 * through every review/status/archive write performed by the endpoint.</p>
 */
@Service
@RequiredArgsConstructor
public class ReviewCheckMutationLockService {

    private final NamedParameterJdbcTemplate jdbc;
    private final OrderAggregateMutationLockService orderAggregateMutationLockService;

    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(UUID orderDetailId) {
        if (orderDetailId == null) {
            throw notFound();
        }

        MapSqlParameterSource params = new MapSqlParameterSource(
                "orderDetailId",
                orderDetailId.toString()
        );

        // The conditional source prevents arbitrary public UUIDs from growing
        // the lock table. Different details of one order resolve to the same mutex.
        jdbc.update("""
                INSERT INTO review_check_mutation_locks (order_id, created_at)
                SELECT candidate.order_id, CURRENT_TIMESTAMP(6)
                FROM (
                    SELECT od.order_detail_order AS order_id
                    FROM order_details od
                    WHERE od.order_detail_id = UUID_TO_BIN(:orderDetailId)

                    UNION

                    SELECT aod.order_detail_order AS order_id
                    FROM archive_order_details aod
                    JOIN archive_orders ao ON ao.order_id = aod.order_detail_order
                    WHERE aod.order_detail_id = UUID_TO_BIN(:orderDetailId)
                      AND ao.restored_at IS NULL
                ) candidate
                WHERE candidate.order_id IS NOT NULL
                ORDER BY candidate.order_id
                ON DUPLICATE KEY UPDATE
                    order_id = VALUES(order_id)
                """, params);

        List<Long> locked = jdbc.queryForList("""
                SELECT mutex.order_id
                FROM review_check_mutation_locks mutex
                WHERE (
                    EXISTS (
                        SELECT 1
                        FROM order_details od
                        WHERE od.order_detail_id = UUID_TO_BIN(:orderDetailId)
                          AND od.order_detail_order = mutex.order_id
                    )
                    OR EXISTS (
                        SELECT 1
                        FROM archive_order_details aod
                        JOIN archive_orders ao ON ao.order_id = aod.order_detail_order
                        WHERE aod.order_detail_id = UUID_TO_BIN(:orderDetailId)
                          AND aod.order_detail_order = mutex.order_id
                          AND ao.restored_at IS NULL
                    )
                  )
                ORDER BY mutex.order_id
                FOR UPDATE
                """, params, Long.class);
        if (locked.isEmpty()) {
            throw notFound();
        }

        // A live review-check mutation participates in the same parent-row
        // lock protocol as worker/manager edits and publication approval. An
        // archived aggregate has no live orders row and remains serialized by
        // the review-check mutex above.
        orderAggregateMutationLockService.lockIfLive(locked.getFirst());
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Проверка отзывов не найдена");
    }
}
