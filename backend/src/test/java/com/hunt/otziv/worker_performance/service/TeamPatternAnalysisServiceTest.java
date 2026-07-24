package com.hunt.otziv.worker_performance.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hunt.otziv.worker_performance.dto.TeamPatternAnalysisResponse;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class TeamPatternAnalysisServiceTest {

    @Test
    void spearmanDetectsMatchingAndOppositeRanks() {
        assertEquals(1.0, TeamPatternAnalysisService.spearman(
                List.of(1.0, 2.0, 3.0, 4.0),
                List.of(10.0, 20.0, 30.0, 40.0)
        ), 0.0001);
        assertEquals(-1.0, TeamPatternAnalysisService.spearman(
                List.of(1.0, 2.0, 3.0, 4.0),
                List.of(40.0, 30.0, 20.0, 10.0)
        ), 0.0001);
    }

    @Test
    void analysisUsesNormalizedPrimaryEventsAndProducesNonCausalInsight() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        List<Map<String, Object>> publications = new ArrayList<>();
        List<Map<String, Object>> blocks = new ArrayList<>();
        List<Map<String, Object>> recoveries = new ArrayList<>();
        List<Map<String, Object>> violations = new ArrayList<>();
        List<TeamPatternAnalysisService.WorkerPatternSubject> subjects = new ArrayList<>();

        for (long index = 1; index <= 8; index++) {
            subjects.add(new TeamPatternAnalysisService.WorkerPatternSubject(index, 100 + index, "worker-" + index));
            publications.add(row(
                    "worker_id", index,
                    "metric_date", Date.valueOf("2026-07-18"),
                    "metric_count", 100L
            ));
            for (long bot = 1; bot <= index * 5; bot++) {
                blocks.add(row(
                        "user_id", 100 + index,
                        "metric_date", Date.valueOf("2026-07-18"),
                        "bot_id", index + "-" + bot
                ));
            }
            recoveries.add(row(
                    "worker_id", index,
                    "metric_date", Date.valueOf("2026-07-18"),
                    "metric_count", index * 2
            ));
            violations.add(row(
                    "user_id", 100 + index,
                    "metric_date", Date.valueOf("2026-07-18"),
                    "metric_count", index,
                    "attempt_count", index * 3
            ));
        }

        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("FROM flyway_schema_history")) {
                assertTrue(sql.contains("DATE_ADD(installed_on, INTERVAL 1 DAY)"));
                return List.of(row("observation_start", Date.valueOf("2026-07-16")));
            }
            if (sql.contains("FROM reviews r")) return publications;
            if (sql.contains("FROM worker_activity_events e")) return blocks;
            if (sql.contains("FROM review_recovery_tasks t")) return recoveries;
            if (sql.contains("FROM worker_network_violation_episodes v")) {
                assertTrue(sql.contains("v.reason_code IN ('NON_CELLULAR_NETWORK', 'VPN_PROXY_OR_DATACENTER')"));
                return violations;
            }
            return List.of();
        });

        TeamPatternAnalysisResponse response = new TeamPatternAnalysisService(jdbc).analyze(
                subjects,
                LocalDate.of(2026, 7, 1)
        );

        assertEquals(8, response.workerCount());
        assertEquals(800, response.publicationCount());
        assertEquals(LocalDate.of(2026, 7, 16), response.from());
        assertEquals("LIMITED", response.confidence());
        assertEquals(8, response.workers().size());
        assertTrue(response.insights().stream().anyMatch(insight ->
                "NETWORK_BLOCKS".equals(insight.code())
                        && "WARNING".equals(insight.tone())
                        && insight.message().contains("не доказательство причины")
        ));
        assertTrue(response.insights().stream().anyMatch(insight ->
                "NETWORK_RECOVERIES".equals(insight.code())
                        && insight.title().contains("задачи восстановления")
        ));
        assertEquals(40.0, response.workers().get(108L).blockRate(), 0.01);
    }

    @Test
    void detectsPersonalNetworkLinksWithBlocksAndRecoveriesAcrossAllObservedDays() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        List<TeamPatternAnalysisService.WorkerPatternSubject> subjects = new ArrayList<>();
        List<Map<String, Object>> publications = new ArrayList<>();
        for (long index = 1; index <= 8; index++) {
            subjects.add(new TeamPatternAnalysisService.WorkerPatternSubject(index, 100 + index, "worker-" + index));
            for (int day = 16; day <= 19; day++) {
                publications.add(row(
                        "worker_id", index,
                        "metric_date", Date.valueOf("2026-07-" + day),
                        "metric_count", 20L
                ));
            }
        }

        List<Map<String, Object>> blocks = new ArrayList<>();
        for (int index = 1; index <= 4; index++) {
            blocks.add(row(
                    "user_id", 101L,
                    "metric_date", Date.valueOf(index <= 2 ? "2026-07-17" : "2026-07-18"),
                    "bot_id", "bot-" + index
            ));
        }
        List<Map<String, Object>> recoveries = List.of(
                row("worker_id", 1L, "metric_date", Date.valueOf("2026-07-17"), "metric_count", 2L),
                row("worker_id", 1L, "metric_date", Date.valueOf("2026-07-18"), "metric_count", 2L)
        );
        List<Map<String, Object>> violations = List.of(
                row("user_id", 101L, "metric_date", Date.valueOf("2026-07-17"), "metric_count", 2L, "attempt_count", 4L),
                row("user_id", 101L, "metric_date", Date.valueOf("2026-07-18"), "metric_count", 2L, "attempt_count", 4L)
        );

        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("FROM flyway_schema_history")) {
                return List.of(row("observation_start", Date.valueOf("2026-07-16")));
            }
            if (sql.contains("FROM reviews r")) return publications;
            if (sql.contains("FROM worker_activity_events e")) return blocks;
            if (sql.contains("FROM review_recovery_tasks t")) return recoveries;
            if (sql.contains("FROM worker_network_violation_episodes v")) return violations;
            return List.of();
        });

        TeamPatternAnalysisResponse response = new TeamPatternAnalysisService(jdbc).analyze(
                subjects,
                LocalDate.of(2026, 7, 1)
        );

        TeamPatternAnalysisResponse.WorkerPattern worker = response.workers().get(101L);
        assertEquals(80, worker.publicationCount());
        assertTrue(worker.insights().stream().anyMatch(insight ->
                "WORKER_NETWORK_BLOCK_PATTERN".equals(insight.code())
                        && "WARNING".equals(insight.tone())
                        && insight.message().contains("не доказывает причину")
        ));
        assertTrue(worker.insights().stream().anyMatch(insight ->
                "WORKER_NETWORK_RECOVERY_PATTERN".equals(insight.code())
                        && "WARNING".equals(insight.tone())
        ));
    }

    @Test
    void smallSampleIsMarkedInsufficient() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class))).thenReturn(List.of());

        TeamPatternAnalysisResponse response = new TeamPatternAnalysisService(jdbc).analyze(
                List.of(new TeamPatternAnalysisService.WorkerPatternSubject(1L, 101L, "worker")),
                LocalDate.of(2026, 7, 1)
        );

        assertEquals("INSUFFICIENT", response.confidence());
        assertTrue(response.insights().stream().anyMatch(insight -> "NOT_ENOUGH_DATA".equals(insight.code())));
    }

    @Test
    void insufficientTeamSampleDoesNotCreateWorkerWarningsAgainstZeroMedian() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("FROM reviews r")) {
                return List.of(row(
                        "worker_id", 1L,
                        "metric_date", Date.valueOf("2026-07-18"),
                        "metric_count", 100L
                ));
            }
            if (sql.contains("FROM worker_activity_events e")) {
                List<Map<String, Object>> blocks = new ArrayList<>();
                for (long bot = 1; bot <= 50; bot++) {
                    blocks.add(row(
                            "user_id", 101L,
                            "metric_date", Date.valueOf("2026-07-18"),
                            "bot_id", bot
                    ));
                }
                return blocks;
            }
            return List.of();
        });

        TeamPatternAnalysisResponse response = new TeamPatternAnalysisService(jdbc).analyze(
                List.of(new TeamPatternAnalysisService.WorkerPatternSubject(1L, 101L, "worker")),
                LocalDate.of(2026, 7, 1)
        );

        assertEquals("INSUFFICIENT", response.confidence());
        assertEquals(50.0, response.workers().get(101L).blockRate(), 0.01);
        assertTrue(response.workers().get(101L).insights().stream().noneMatch(insight ->
                "WARNING".equals(insight.tone())
        ));
        assertTrue(response.workers().get(101L).insights().stream().anyMatch(insight ->
                "WORKER_TEAM_NOT_ENOUGH_DATA".equals(insight.code())
        ));
    }

    private Map<String, Object> row(Object... values) {
        Map<String, Object> row = new HashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            row.put(values[index].toString(), values[index + 1]);
        }
        return row;
    }
}
