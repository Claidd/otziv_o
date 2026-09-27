package com.hunt.otziv.b_bots.repository;

import java.time.LocalDate;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Global availability; company-specific history is checked again during assignment. */
@Repository
@RequiredArgsConstructor
public class ReviewAccountPoolRepository {
    private final NamedParameterJdbcTemplate jdbc;

    private static final String SNAPSHOT_SQL = """
        WITH free_pool AS (
            SELECT b.bot_counter,
                   LOWER(TRIM(b.bot_fio)) IN ('впиши имя фамилию', 'впишите имя фамилию',
                                             'впишите фамилию имя') AS template_name
            FROM bots b
            JOIN bots_status s ON s.bot_status_id = b.bot_status
            WHERE b.bot_city_id = 325 AND b.bot_id <> 1 AND b.bot_active = 1
              AND TRIM(s.bot_status_title) = 'Новый'
              AND TRIM(COALESCE(b.bot_login, '')) <> ''
              AND TRIM(COALESCE(b.bot_password, '')) <> ''
              AND TRIM(COALESCE(b.bot_fio, '')) <> ''
              AND LOWER(TRIM(b.bot_fio)) <> 'нет доступных аккаунтов'
              AND (b.bot_cooldown_until IS NULL OR b.bot_cooldown_until <= :today)
              AND NOT EXISTS (SELECT 1 FROM reviews r
                              WHERE r.review_bot = b.bot_id AND COALESCE(r.review_publish, 0) = 0)
              AND NOT EXISTS (SELECT 1 FROM bad_review_tasks t
                              WHERE t.bad_review_task_bot = b.bot_id AND t.bad_review_task_status = 'NEW')
              AND NOT EXISTS (SELECT 1 FROM review_recovery_tasks t
                              WHERE t.review_recovery_task_bot = b.bot_id AND t.review_recovery_task_status = 'PLANNED')
        ), supply AS (
            SELECT COUNT(CASE WHEN bot_counter BETWEEN 0 AND 1
                                   AND (template_name OR bot_counter < :walkedThreshold) THEN 1 END) AS walking,
                   COUNT(CASE WHEN NOT template_name AND bot_counter >= :publicationMin THEN 1 END) AS publication,
                   COUNT(CASE WHEN template_name AND bot_counter BETWEEN 0 AND 1 THEN 1 END) AS templates
            FROM free_pool
        ), demand AS (
            SELECT COUNT(CASE WHEN COALESCE(r.review_vigul, 0) = 0 THEN 1 END) AS walking_required,
                   COUNT(CASE WHEN r.review_vigul = 1 THEN 1 END) AS publication_required
            FROM reviews r
            LEFT JOIN order_details d ON d.order_detail_id = r.review_order_details
            LEFT JOIN orders o ON o.order_id = d.order_detail_order
            JOIN filial f ON f.filial_id = COALESCE(r.review_filial, o.order_filial)
            WHERE r.review_bot = 1 AND COALESCE(r.review_publish, 0) = 0
              AND f.city_id NOT IN (0, 320, 325, 326)
        )
        SELECT supply.*, demand.* FROM supply CROSS JOIN demand
        """;

    public Snapshot snapshot(LocalDate today, int walkedThreshold) {
        return jdbc.queryForObject(SNAPSHOT_SQL, Map.of("today", today,
                "walkedThreshold", walkedThreshold, "publicationMin", Math.max(2, walkedThreshold)),
                (rs, row) -> new Snapshot(rs.getInt("walking"), rs.getInt("publication"),
                        rs.getInt("templates"), rs.getInt("walking_required"), rs.getInt("publication_required")));
    }

    public record Snapshot(int walking, int publication, int templates, int walkingRequired, int publicationRequired) {
        public int remaining() { return walking + publication; }
        public int required() { return walkingRequired + publicationRequired; }
        public int walkingDeficit() { return Math.max(0, walkingRequired - walking); }
        public int publicationDeficit() { return Math.max(0, publicationRequired - publication); }
        public int deficit() { return walkingDeficit() + publicationDeficit(); }
        public int coverage() { return required() == 0 ? 100 : (int) Math.round((required() - deficit()) * 100.0 / required()); }
    }
}
