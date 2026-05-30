package com.hunt.otziv.client_messages.repository;

import com.hunt.otziv.client_messages.dto.ArchiveCompanyCandidateDiagnostics;
import com.hunt.otziv.client_messages.dto.ArchiveCompanyMessageCandidate;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ArchiveCompanyMessageCandidateRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public List<ArchiveCompanyMessageCandidate> findCandidates(
            LocalDateTime cutoff,
            int limit,
            String archiveCompanyStatus,
            Collection<String> inactiveOrderStatuses,
            Collection<String> openNextOrderStatuses
    ) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("cutoff", Timestamp.valueOf(cutoff))
                .addValue("limit", Math.max(1, limit))
                .addValue("archiveCompanyStatus", archiveCompanyStatus)
                .addValue("inactiveStatuses", inactiveOrderStatuses)
                .addValue("openNextOrderStatuses", openNextOrderStatuses);

        return jdbc.query("""
                SELECT
                    c.company_id AS company_id,
                    CAST(SUBSTRING_INDEX(
                        GROUP_CONCAT(ao.order_id ORDER BY ao.archived_at DESC, ao.order_id DESC),
                        ',',
                        1
                    ) AS UNSIGNED) AS archive_order_id,
                    c.company_status_changed_at AS status_changed_at
                FROM companies c
                LEFT JOIN company_status cs ON cs.company_status_id = c.company_status
                LEFT JOIN archive_orders ao ON ao.order_company = c.company_id
                  AND ao.archived_at IS NOT NULL
                  AND ao.restored_at IS NULL
                WHERE c.company_status_changed_at IS NOT NULL
                  AND c.company_status_changed_at <= :cutoff
                  AND COALESCE(cs.status_title, '') = :archiveCompanyStatus
                  AND c.company_active = 1
                  AND c.company_url_chat IS NOT NULL
                  AND TRIM(c.company_url_chat) <> ''
                  AND NOT EXISTS (
                      SELECT 1
                      FROM orders live_order
                      JOIN order_statuses live_status ON live_status.order_status_id = live_order.order_status
                      WHERE live_order.order_company = c.company_id
                        AND COALESCE(live_status.order_status_title, '') NOT IN (:inactiveStatuses)
                  )
                  AND NOT EXISTS (
                      SELECT 1
                      FROM next_order_requests request
                      WHERE request.company_id = c.company_id
                        AND request.request_status IN (:openNextOrderStatuses)
                  )
                GROUP BY c.company_id, c.company_status_changed_at
                ORDER BY c.company_status_changed_at ASC, c.company_id ASC
                LIMIT :limit
                """, params, (rs, rowNum) -> {
            Long archiveOrderId = rs.getLong("archive_order_id");
            if (rs.wasNull()) {
                archiveOrderId = null;
            }
            return new ArchiveCompanyMessageCandidate(
                    rs.getLong("company_id"),
                    archiveOrderId,
                    rs.getTimestamp("status_changed_at").toLocalDateTime()
            );
        });
    }

    public boolean hasArchiveReorderBlocker(
            Long companyId,
            Collection<String> inactiveOrderStatuses,
            Collection<String> openNextOrderStatuses
    ) {
        if (companyId == null) {
            return true;
        }

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("companyId", companyId)
                .addValue("inactiveStatuses", inactiveOrderStatuses)
                .addValue("openNextOrderStatuses", openNextOrderStatuses);

        Boolean blocked = jdbc.queryForObject("""
                SELECT CASE WHEN
                    EXISTS (
                        SELECT 1
                        FROM orders live_order
                        JOIN order_statuses live_status ON live_status.order_status_id = live_order.order_status
                        WHERE live_order.order_company = :companyId
                          AND COALESCE(live_status.order_status_title, '') NOT IN (:inactiveStatuses)
                    )
                    OR EXISTS (
                        SELECT 1
                        FROM next_order_requests request
                        WHERE request.company_id = :companyId
                          AND request.request_status IN (:openNextOrderStatuses)
                    )
                THEN 1 ELSE 0 END
                """, params, Boolean.class);
        return Boolean.TRUE.equals(blocked);
    }

    public ArchiveCompanyCandidateDiagnostics diagnostics(
            LocalDateTime cutoff,
            String archiveCompanyStatus,
            Collection<String> inactiveOrderStatuses,
            Collection<String> openNextOrderStatuses
    ) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("cutoff", Timestamp.valueOf(cutoff))
                .addValue("archiveCompanyStatus", archiveCompanyStatus)
                .addValue("inactiveStatuses", inactiveOrderStatuses)
                .addValue("openNextOrderStatuses", openNextOrderStatuses);

        return jdbc.queryForObject("""
                SELECT
                    COUNT(*) AS total_in_status,
                    SUM(CASE
                        WHEN c.company_status_changed_at IS NOT NULL
                         AND c.company_status_changed_at <= :cutoff
                         AND c.company_url_chat IS NOT NULL
                         AND TRIM(c.company_url_chat) <> ''
                         AND NOT EXISTS (
                            SELECT 1
                            FROM orders live_order
                            JOIN order_statuses live_status ON live_status.order_status_id = live_order.order_status
                            WHERE live_order.order_company = c.company_id
                              AND COALESCE(live_status.order_status_title, '') NOT IN (:inactiveStatuses)
                         )
                         AND NOT EXISTS (
                            SELECT 1
                            FROM next_order_requests request
                            WHERE request.company_id = c.company_id
                              AND request.request_status IN (:openNextOrderStatuses)
                         )
                        THEN 1 ELSE 0 END
                    ) AS ready,
                    SUM(CASE
                        WHEN c.company_status_changed_at IS NULL OR c.company_status_changed_at > :cutoff
                        THEN 1 ELSE 0 END
                    ) AS too_fresh,
                    SUM(CASE
                        WHEN c.company_url_chat IS NULL OR TRIM(c.company_url_chat) = ''
                        THEN 1 ELSE 0 END
                    ) AS without_chat,
                    SUM(CASE
                        WHEN EXISTS (
                            SELECT 1
                            FROM orders live_order
                            JOIN order_statuses live_status ON live_status.order_status_id = live_order.order_status
                            WHERE live_order.order_company = c.company_id
                              AND COALESCE(live_status.order_status_title, '') NOT IN (:inactiveStatuses)
                        )
                        THEN 1 ELSE 0 END
                    ) AS blocked_by_active_order,
                    SUM(CASE
                        WHEN EXISTS (
                            SELECT 1
                            FROM next_order_requests request
                            WHERE request.company_id = c.company_id
                              AND request.request_status IN (:openNextOrderStatuses)
                        )
                        THEN 1 ELSE 0 END
                    ) AS blocked_by_open_request
                FROM companies c
                LEFT JOIN company_status cs ON cs.company_status_id = c.company_status
                WHERE COALESCE(cs.status_title, '') = :archiveCompanyStatus
                  AND c.company_active = 1
                """, params, (rs, rowNum) -> new ArchiveCompanyCandidateDiagnostics(
                archiveCompanyStatus,
                rs.getLong("total_in_status"),
                rs.getLong("ready"),
                rs.getLong("too_fresh"),
                rs.getLong("without_chat"),
                rs.getLong("blocked_by_active_order"),
                rs.getLong("blocked_by_open_request")
        ));
    }
}
