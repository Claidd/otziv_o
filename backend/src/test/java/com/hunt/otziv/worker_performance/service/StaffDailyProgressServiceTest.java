package com.hunt.otziv.worker_performance.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.worker_performance.dto.DailyWorkProgressResponse;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class StaffDailyProgressServiceTest {

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final StaffDailyProgressService service = new StaffDailyProgressService(
            jdbc,
            mock(AppSettingService.class)
    );

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
}
