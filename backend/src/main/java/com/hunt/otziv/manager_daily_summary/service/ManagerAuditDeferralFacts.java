package com.hunt.otziv.manager_daily_summary.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Facts for the «card deferred for more than a day» remark. The card's own
 * {@code deferred_at} cannot answer who deferred it (old rows were back-filled from
 * {@code updated_at}); the control journal records every deferral with its actor.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ManagerAuditDeferralFacts {

    private static final String CARD_PREFIX = "Карточка: ";

    private final NamedParameterJdbcTemplate jdbc;

    /** True only when the control's own manager deferred this card. Unknown authorship is not attributed. */
    public boolean deferredByControlManager(Long parentItemId, String cardTitle) {
        if (parentItemId == null || cardTitle == null || cardTitle.isBlank()) return false;
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT event.actor_user_id, event.comment, manager.user_id AS manager_user_id
                    FROM manager_daily_control_events event
                    JOIN manager_daily_controls control ON control.daily_control_id = event.control_id
                    JOIN managers manager ON manager.manager_id = control.manager_id
                    WHERE event.item_id = :parentItemId
                      AND event.action_type = 'DEFERRED'
                    ORDER BY event.created_at, event.control_event_id
                    """, new MapSqlParameterSource("parentItemId", parentItemId));
            String expected = CARD_PREFIX + cardTitle;
            return rows.stream()
                    .filter(row -> row.get("comment") != null && row.get("comment").toString().startsWith(expected))
                    .anyMatch(row -> row.get("actor_user_id") != null && row.get("manager_user_id") != null
                            && number(row.get("actor_user_id")) == number(row.get("manager_user_id")));
        } catch (DataAccessException unavailable) {
            log.warn("Deferral journal is unavailable for control item {}: {}", parentItemId, unavailable.getMessage());
            return false;
        }
    }

    /**
     * The daily control re-creates a card for every problem that still exists. When the
     * manager's latest control up to the report date has no unresolved card for the same
     * entity, the problem left the board and an old deferred copy is not a current remark.
     */
    public boolean stillOnBoard(Long managerId, String entityKey, LocalDate reportDate) {
        if (managerId == null || reportDate == null || entityKey == null || entityKey.isBlank()) return false;
        try {
            Long count = jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM manager_daily_control_concrete_items card
                    JOIN manager_daily_controls control ON control.daily_control_id = card.control_id
                    WHERE control.manager_id = :managerId
                      AND control.control_date = (
                          SELECT MAX(latest.control_date) FROM manager_daily_controls latest
                          WHERE latest.manager_id = :managerId AND latest.control_date <= :reportDate
                      )
                      AND card.item_status <> 'RESOLVED'
                      AND card.entity_key = :entityKey
                    """, new MapSqlParameterSource()
                    .addValue("managerId", managerId)
                    .addValue("reportDate", reportDate)
                    .addValue("entityKey", entityKey), Long.class);
            return count != null && count > 0;
        } catch (DataAccessException unavailable) {
            log.warn("Current control board is unavailable for manager {} on {}: {}", managerId, reportDate,
                    unavailable.getMessage());
            return false;
        }
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : Long.parseLong(value.toString());
    }
}
