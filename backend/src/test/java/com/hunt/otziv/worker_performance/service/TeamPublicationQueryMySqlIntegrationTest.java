package com.hunt.otziv.worker_performance.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import static org.assertj.core.api.Assertions.assertThat;

/** Execute the production read on MySQL: disjoint branches, date edges, scope and legacy fallback. */
@Testcontainers
class TeamPublicationQueryMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("team_publications").withUsername("root").withPassword("local-test");

    @Test void blockedAccountDailyCountsMatchFirstOccurrencePerWorkerAndBot() {
        var source = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE worker_activity_events(worker_user_id BIGINT,created_at DATETIME(6),action VARCHAR(64),details VARCHAR(500))");
        var from = LocalDateTime.of(2026, 7, 1, 0, 0);
        var to = from.plusMonths(1);
        for (long user : List.of(1L, 2L, 99L)) {
            for (String details : java.util.Arrays.asList("botId=7;source=a", "botId=7;source=b", "botId=8", "legacy", null)) {
                for (var at : List.of(from.minusNanos(1000), from, from.plusDays(2), to)) {
                    jdbc.update("INSERT INTO worker_activity_events VALUES (?,?,?,?)", user, at, "REVIEW_BOT_DEACTIVATE", details);
                    jdbc.update("INSERT INTO worker_activity_events VALUES (?,?,?,?)", user, at, "OTHER", details);
                }
            }
        }
        var named = new NamedParameterJdbcTemplate(source);
        var params = Map.of("userIds", List.of(1L, 2L), "from", from, "to", to);
        var oldRows = named.queryForList("""
                SELECT e.worker_user_id AS user_id, DATE(MIN(e.created_at)) AS metric_date,
                    SUBSTRING_INDEX(SUBSTRING_INDEX(e.details,'botId=',-1),';',1) AS bot_id
                FROM worker_activity_events e WHERE e.worker_user_id IN (:userIds)
                    AND e.created_at >= :from AND e.created_at < :to AND e.action='REVIEW_BOT_DEACTIVATE'
                GROUP BY e.worker_user_id, SUBSTRING_INDEX(SUBSTRING_INDEX(e.details,'botId=',-1),';',1)
                """, params);
        var expected = new java.util.HashMap<String, Long>();
        oldRows.forEach(row -> expected.merge(row.get("user_id") + "/" + row.get("metric_date"), 1L, Long::sum));
        var actual = new java.util.HashMap<String, Long>();
        named.queryForList(TeamPatternAnalysisService.BLOCKED_ACCOUNTS_SQL, params).forEach(row ->
                actual.put(row.get("user_id") + "/" + row.get("metric_date"), ((Number) row.get("metric_count")).longValue()));
        assertThat(actual).isEqualTo(expected).hasSize(2);
        assertThat(actual.values()).containsOnly(4L);
    }

    @Test void splitRangesMatchCanonicalPublicationTimestamp() {
        var source = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("""
                CREATE TABLE reviews (review_id BIGINT AUTO_INCREMENT PRIMARY KEY, review_worker BIGINT,
                    review_publish BOOLEAN, review_published_marked_at DATETIME(6), review_changed DATE)
                """);
        var from = LocalDateTime.of(2026, 7, 1, 0, 0);
        var to = from.plusMonths(1);
        for (Long worker : List.of(1L, 2L, 99L)) {
            for (boolean published : List.of(true, false)) {
                for (var changed : List.of(from.minusDays(1).toLocalDate(), from.toLocalDate(), to.toLocalDate())) {
                    jdbc.update("INSERT INTO reviews(review_worker,review_publish,review_changed) VALUES (?,?,?)", worker, published, changed);
                    for (var marked : List.of(from.minusNanos(1000), from, to.minusNanos(1000), to)) {
                        jdbc.update("INSERT INTO reviews(review_worker,review_publish,review_changed,review_published_marked_at) VALUES (?,?,?,?)",
                                worker, published, changed, marked);
                    }
                }
            }
        }
        var named = new NamedParameterJdbcTemplate(source);
        var params = Map.of("workerIds", List.of(1L, 2L), "from", from, "to", to);
        var actual = named.queryForList(TeamPatternAnalysisService.PUBLICATIONS_SQL, params);
        var expected = named.queryForList("""
                SELECT review_worker AS worker_id,
                    DATE(COALESCE(review_published_marked_at,TIMESTAMP(review_changed))) AS metric_date, COUNT(*) AS metric_count
                FROM reviews WHERE review_worker IN (:workerIds) AND review_publish=1
                    AND COALESCE(review_published_marked_at,TIMESTAMP(review_changed)) >= :from
                    AND COALESCE(review_published_marked_at,TIMESTAMP(review_changed)) < :to
                GROUP BY worker_id, metric_date
                """, params);
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(actual).hasSize(4).allSatisfy(row -> assertThat(((Number) row.get("worker_id")).longValue()).isIn(1L, 2L));
    }
}
