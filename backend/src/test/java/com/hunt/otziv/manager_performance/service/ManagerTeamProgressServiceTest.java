package com.hunt.otziv.manager_performance.service;

import com.hunt.otziv.worker_performance.dto.DailyWorkProgressResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManagerTeamProgressServiceTest {

    @Mock
    private NamedParameterJdbcTemplate jdbc;

    @InjectMocks
    private ManagerTeamProgressService service;

    @Test
    void snapshotRequiresEveryAssignedWorkerToBeAt100() {
        LocalDate date = LocalDate.of(2026, 7, 17);
        DailyWorkProgressResponse completed = progress(date, 10, 0, 10, 100);
        DailyWorkProgressResponse incomplete = progress(date, 99, 1, 100, 99);

        service.saveEndOfDaySnapshot(date, 7L, 70L, 2, List.of(completed, incomplete));

        ArgumentCaptor<SqlParameterSource> captor = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).update(anyString(), captor.capture());
        MapSqlParameterSource params = (MapSqlParameterSource) captor.getValue();
        assertEquals(2, params.getValue("workerCount"));
        assertEquals(1, params.getValue("workersAt100"));
        assertEquals("99.50", params.getValue("progressPercent").toString());
        assertFalse((Boolean) params.getValue("reached100"));
    }

    @Test
    void snapshotExcludesAssignedWorkersWithoutTasks() {
        LocalDate date = LocalDate.of(2026, 7, 17);
        DailyWorkProgressResponse completed = progress(date, 10, 0, 10, 100);
        DailyWorkProgressResponse withoutTasks = progress(date, 0, 0, 0, 100);

        service.saveEndOfDaySnapshot(date, 7L, 70L, 2, List.of(completed, withoutTasks));

        ArgumentCaptor<SqlParameterSource> captor = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).update(anyString(), captor.capture());
        MapSqlParameterSource params = (MapSqlParameterSource) captor.getValue();
        assertEquals(1, params.getValue("workerCount"));
        assertEquals(1, params.getValue("workersAt100"));
        assertEquals("100.00", params.getValue("progressPercent").toString());
        assertTrue((Boolean) params.getValue("reached100"));
    }

    @Test
    void snapshotKeepsPreservedAchievementAt100AfterLaterWorkArrives() {
        LocalDate date = LocalDate.of(2026, 7, 17);
        DailyWorkProgressResponse reachedEarlier = progress(date, 30, 5, 35, 86, true);

        service.saveEndOfDaySnapshot(date, 7L, 70L, 1, List.of(reachedEarlier));

        ArgumentCaptor<SqlParameterSource> captor = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).update(anyString(), captor.capture());
        MapSqlParameterSource params = (MapSqlParameterSource) captor.getValue();
        assertEquals(1, params.getValue("workerCount"));
        assertEquals(1, params.getValue("workersAt100"));
        assertEquals(30L, params.getValue("completed"));
        assertEquals(35L, params.getValue("total"));
        assertEquals("100.00", params.getValue("progressPercent").toString());
        assertTrue((Boolean) params.getValue("reached100"));
    }

    @Test
    void snapshotIsRemovedWhenNoAssignedWorkerHadTasks() {
        LocalDate date = LocalDate.of(2026, 7, 17);

        service.saveEndOfDaySnapshot(
                date,
                7L,
                70L,
                1,
                List.of(progress(date, 0, 0, 0, 100))
        );

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).update(sql.capture(), params.capture());
        assertTrue(sql.getValue().contains("DELETE FROM manager_team_daily_progress"));
        assertEquals(date, params.getValue().getValue("date"));
        assertEquals(7L, params.getValue().getValue("managerId"));
    }

    @Test
    void monthlyScoreCombinesFullyCompletedDaysAndAverageProgress() {
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class))).thenReturn(List.of(Map.of(
                "manager_id", 7L,
                "eligible_days", 10L,
                "reached_100_days", 9L,
                "average_progress_percent", 99.0,
                "missed_worker_days", 1L
        )));

        ManagerTeamProgressService.TeamProgressStats stats = service.statisticsByManagerIds(
                List.of(7L),
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 17)
        ).get(7L);

        assertEquals(10, stats.eligibleDays());
        assertEquals(9, stats.reached100Days());
        assertEquals(90.0, stats.reached100Rate());
        assertEquals(99.0, stats.averageProgressPercent());
        assertEquals(93, stats.score());
    }

    private DailyWorkProgressResponse progress(LocalDate date, long completed, long active, long total, int percent) {
        return progress(date, completed, active, total, percent, total > 0 && percent >= 100 && active <= 0);
    }

    private DailyWorkProgressResponse progress(
            LocalDate date,
            long completed,
            long active,
            long total,
            int percent,
            boolean reached100
    ) {
        DailyWorkProgressResponse response = new DailyWorkProgressResponse(
                true, "WORKER", date, completed, active, total, percent, false,
                null, null, 0, 0, 0, null, null, 0, 0, 0, 0, 0
        );
        return response.withWorkloadProgress(
                completed,
                total,
                percent,
                reached100,
                reached100 ? date.atTime(20, 0) : null,
                reached100 ? date.atTime(20, 0) : null
        );
    }
}
