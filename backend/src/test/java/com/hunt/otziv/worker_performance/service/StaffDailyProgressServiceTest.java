package com.hunt.otziv.worker_performance.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.worker_performance.dto.DailyWorkProgressResponse;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class StaffDailyProgressServiceTest {

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final StaffDailyProgressService service = new StaffDailyProgressService(
            jdbc,
            mock(AppSettingService.class)
    );

    @Test
    void averageDailyActivityIncludesCalendarDaysWithoutActions() {
        NamedParameterJdbcTemplate localJdbc = mock(NamedParameterJdbcTemplate.class);
        AppSettingService settings = mock(AppSettingService.class);
        when(settings.getBoolean(AppSettingService.WORKER_PROGRESS_ENABLED, true)).thenReturn(true);
        when(localJdbc.queryForList(anyString(), any(MapSqlParameterSource.class))).thenReturn(List.of(
                Map.of("worker_id", 7L, "active_work_seconds", 7_200L)
        ));
        StaffDailyProgressService localService = new StaffDailyProgressService(localJdbc, settings);

        Map<Long, Long> averages = localService.averageDailyActiveWorkSecondsByWorkerIds(
                List.of(7L),
                LocalDate.of(2026, 7, 4)
        );

        assertEquals(1_800L, averages.get(7L));
        ArgumentCaptor<MapSqlParameterSource> params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(localJdbc).queryForList(anyString(), params.capture());
        assertEquals(LocalDate.of(2026, 7, 1), params.getValue().getValue("from"));
        assertEquals(LocalDate.of(2026, 7, 5), params.getValue().getValue("to"));
    }

    @Test
    void endOfDayGraceHourUsesActualQueueAppearanceTime() {
        LocalDate date = LocalDate.of(2026, 7, 17);
        LocalDateTime cutoff = date.atTime(23, 0);

        assertTrue(StaffDailyProgressService.includedInEndOfDay(date.atTime(22, 59, 59), cutoff));
        assertFalse(StaffDailyProgressService.includedInEndOfDay(date.atTime(23, 0), cutoff));
        assertFalse(StaffDailyProgressService.includedInEndOfDay(date.atTime(23, 58), cutoff));
    }

    @Test
    void endOfDayProgressExcludesCompletedTasksAddedAfterCutoff() {
        NamedParameterJdbcTemplate localJdbc = mock(NamedParameterJdbcTemplate.class);
        AppSettingService settings = mock(AppSettingService.class);
        when(settings.getBoolean(AppSettingService.WORKER_PROGRESS_ENABLED, true)).thenReturn(true);
        when(localJdbc.queryForList(anyString(), any(MapSqlParameterSource.class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (!sql.contains("completed_items")) {
                return List.of();
            }
            LocalDate date = LocalDate.of(2026, 7, 17);
            return List.of(
                    completedItem(7L, 1L, date.atTime(22, 50), date.atTime(22, 50), date.atTime(23, 30)),
                    completedItem(7L, 2L, date.atTime(23, 20), date.atTime(23, 20), date.atTime(23, 40))
            );
        });
        StaffDailyProgressService localService = new StaffDailyProgressService(localJdbc, settings);
        Worker worker = Worker.builder()
                .id(7L)
                .user(User.builder().id(70L).fio("Анна").build())
                .build();
        LocalDate date = LocalDate.of(2026, 7, 17);

        DailyWorkProgressResponse progress = localService.workerEndOfDayProgressByWorkers(
                List.of(worker),
                date,
                date.atTime(23, 0)
        ).get(7L);

        assertEquals(1, progress.completed());
        assertEquals(1, progress.total());
        assertEquals(100, progress.percent());
    }

    @Test
    void individualAchievementDoesNotBecomeTeamAchievement() {
        DailyWorkProgressResponse worker = progress(4, 1, true);

        DailyWorkProgressResponse team = service.aggregateProgressResponses(
                List.of(worker),
                LocalDate.of(2026, 7, 15),
                "WORKER_TEAM"
        );

        assertFalse(team.reached100());
        assertNull(team.firstReached100At());
        assertNull(team.lastReached100At());
    }

    @Test
    void teamAchievementIsRecordedOnlyWhenWholeQueueWasEmptyAtOnce() {
        LocalDate date = LocalDate.of(2026, 7, 14);
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class))).thenReturn(List.of(
                lifecycle(date.atTime(9, 0), date.atTime(11, 0)),
                lifecycle(date.atTime(9, 30), date.atTime(10, 0)),
                lifecycle(date.atTime(12, 0), null)
        ));

        DailyWorkProgressResponse team = service.aggregateTeamProgressResponses(
                List.of(progress(2, 1, false)),
                List.of(1L, 2L),
                date,
                "WORKER_TEAM"
        );

        assertTrue(team.reached100());
        assertEquals(date.atTime(11, 0), team.firstReached100At());
        assertEquals(date.atTime(11, 0), team.lastReached100At());
    }

    @Test
    void overlappingOpenWorkDoesNotProduceFalseTeamAchievement() {
        LocalDate date = LocalDate.of(2026, 7, 14);
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class))).thenReturn(List.of(
                lifecycle(date.atTime(9, 0), date.atTime(11, 0)),
                lifecycle(date.atTime(10, 0), null)
        ));

        DailyWorkProgressResponse team = service.aggregateTeamProgressResponses(
                List.of(progress(1, 1, true)),
                List.of(1L, 2L),
                date,
                "WORKER_TEAM"
        );

        assertFalse(team.reached100());
        assertNull(team.firstReached100At());
    }

    @Test
    void waitingForClientOrdersAreExcludedFromRealtimeProgressAndLifecycle() {
        NamedParameterJdbcTemplate localJdbc = mock(NamedParameterJdbcTemplate.class);
        AppSettingService settings = mock(AppSettingService.class);
        when(settings.getBoolean(AppSettingService.WORKER_PROGRESS_ENABLED, true)).thenReturn(true);
        when(localJdbc.queryForList(anyString(), any(MapSqlParameterSource.class))).thenReturn(List.of());
        StaffDailyProgressService localService = new StaffDailyProgressService(localJdbc, settings);

        localService.workerProgressBySubjects(
                List.of(new StaffDailyProgressService.WorkerProgressSubject(7L, 70L, "worker")),
                LocalDate.of(2026, 7, 15)
        );

        ArgumentCaptor<String> querySql = ArgumentCaptor.forClass(String.class);
        verify(localJdbc, atLeastOnce()).queryForList(querySql.capture(), any(MapSqlParameterSource.class));
        assertTrue(querySql.getAllValues().stream().anyMatch(sql ->
                sql.contains("o.order_waiting_for_client = FALSE")
        ));
        assertTrue(querySql.getAllValues().stream().anyMatch(sql ->
                sql.contains("COALESCE(lifecycle.available_at, lifecycle.opened_at")
        ));

        ArgumentCaptor<String> updateSql = ArgumentCaptor.forClass(String.class);
        verify(localJdbc, atLeastOnce()).update(updateSql.capture(), any(MapSqlParameterSource.class));
        assertTrue(updateSql.getAllValues().stream().anyMatch(sql ->
                sql.contains("exclusion_reason = 'waiting_for_client'")
        ));
    }

    @Test
    void openMonthUsesPrimarySourcesForPublicationRates() {
        NamedParameterJdbcTemplate localJdbc = mock(NamedParameterJdbcTemplate.class);
        AppSettingService settings = mock(AppSettingService.class);
        when(settings.getBoolean(AppSettingService.WORKER_PROGRESS_MONTHLY_AGGREGATE_ENABLED, true)).thenReturn(true);
        StaffDailyProgressService localService = new StaffDailyProgressService(localJdbc, settings);

        localService.rebuildMonthlyAggregates(LocalDate.of(2026, 7, 1), false);

        ArgumentCaptor<String> updateSql = ArgumentCaptor.forClass(String.class);
        verify(localJdbc).update(updateSql.capture(), any(MapSqlParameterSource.class));
        String sql = updateSql.getValue();
        assertTrue(sql.contains("FROM reviews monthly_review"));
        assertTrue(sql.contains("FROM review_recovery_tasks monthly_recovery"));
        assertTrue(sql.contains("monthly_block.action = 'REVIEW_BOT_DEACTIVATE'"));
        assertTrue(sql.matches("(?s).*SELECT COUNT\\(\\*\\)\\s+FROM reviews monthly_review.*"));
        assertFalse(sql.contains("SUBSTRING_INDEX(monthly_block.details"));
        assertFalse(sql.contains("monthly_block.action = 'BAD_TASK_BOT_DEACTIVATE'"));
    }

    private DailyWorkProgressResponse progress(long completed, long active, boolean reached100) {
        DailyWorkProgressResponse response = mock(DailyWorkProgressResponse.class);
        when(response.visible()).thenReturn(true);
        when(response.completed()).thenReturn(completed);
        when(response.active()).thenReturn(active);
        when(response.total()).thenReturn(completed + active);
        when(response.reached100()).thenReturn(reached100);
        return response;
    }

    private Map<String, Object> lifecycle(LocalDateTime openedAt, LocalDateTime closedAt) {
        Map<String, Object> row = new HashMap<>();
        row.put("opened_at", Timestamp.valueOf(openedAt));
        row.put("closed_at", closedAt == null ? null : Timestamp.valueOf(closedAt));
        return row;
    }

    private Map<String, Object> completedItem(
            Long workerId,
            Long itemId,
            LocalDateTime openedAt,
            LocalDateTime addedAt,
            LocalDateTime doneAt
    ) {
        Map<String, Object> row = new HashMap<>();
        row.put("worker_id", workerId);
        row.put("item_type", "order");
        row.put("item_id", itemId);
        row.put("opened_at", Timestamp.valueOf(openedAt));
        row.put("added_at", Timestamp.valueOf(addedAt));
        row.put("done_at", Timestamp.valueOf(doneAt));
        return row;
    }
}
