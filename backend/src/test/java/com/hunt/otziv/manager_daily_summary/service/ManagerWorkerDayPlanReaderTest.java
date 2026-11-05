package com.hunt.otziv.manager_daily_summary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

class ManagerWorkerDayPlanReaderTest {

    @Test
    void textsOfOneOrderAreOneTaskAndOtherOrdersStaySeparate() {
        LocalDateTime evening = LocalDateTime.of(2026, 11, 1, 21, 53, 35);
        var tasks = ManagerWorkerDayPlanReader.tasks(List.of(
                unit("NEW", "NEW:28264:2026-11-01 21:53:00.000000", "NEW:206067", evening),
                unit("NEW", "NEW:28264:2026-11-01 21:53:00.000000", "NEW:206068", evening),
                unit("NEW", "NEW:28264:2026-11-01 21:53:00.000000", "NEW:206069", evening),
                unit("PUBLISH", "PUBLISH:28180:2026-11-01 21:28:00.000000", "PUBLISH:205990", evening.minusMinutes(25)),
                unit("NEW", "NEW:28264:2026-11-01 21:54:00.000000", "NEW:206070", evening.plusMinutes(1))));

        assertThat(tasks).containsExactly(
                new ManagerWorkerDayPlanReader.UnfinishedTask("PUBLISH", 28180L, 1, evening.minusMinutes(25)),
                new ManagerWorkerDayPlanReader.UnfinishedTask("NEW", 28264L, 4, evening));
    }

    @Test
    void unitsWithoutAnOrderRemainSeparateTasks() {
        LocalDateTime morning = LocalDateTime.of(2026, 11, 1, 10, 0);
        var tasks = ManagerWorkerDayPlanReader.tasks(List.of(
                unit("BAD", "BAD:BAD:1568:2026-11-01 10:00:00.000000", "BAD:1568", morning),
                unit("BAD", "BAD:BAD:1569:2026-11-01 10:00:00.000000", "BAD:1569", morning)));

        assertThat(tasks).hasSize(2).allMatch(task -> task.orderId() == null && task.units() == 1);
        assertThat(ManagerWorkerDayPlanReader.orderId("RECOVERY", "RECOVERY:27147:2026-11-06 10:00:00.000000"))
                .isEqualTo(27147L);
        assertThat(ManagerWorkerDayPlanReader.orderId("NEW", "PUBLISH:1:2026-11-06 10:00:00")).isNull();
    }

    @Test
    void unavailablePlanNeverInventsTasks() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        org.mockito.Mockito.doThrow(new DataAccessResourceFailureException("down"))
                .when(jdbc).query(anyString(), any(SqlParameterSource.class), any(RowCallbackHandler.class));

        assertThat(new ManagerWorkerDayPlanReader(jdbc).plans(List.of(13L), LocalDate.of(2026, 11, 1))).isEmpty();
    }

    private ManagerWorkerDayPlanReader.UnfinishedUnit unit(String section, String cohort, String batch, LocalDateTime at) {
        return new ManagerWorkerDayPlanReader.UnfinishedUnit(section, cohort, batch, 1, at);
    }
}
