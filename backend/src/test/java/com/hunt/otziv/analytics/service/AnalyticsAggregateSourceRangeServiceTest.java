package com.hunt.otziv.analytics.service;

import com.hunt.otziv.analytics.service.AnalyticsAggregateSourceRangeService.AnalyticsSourceRange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Date;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalyticsAggregateSourceRangeServiceTest {

    @Mock
    private NamedParameterJdbcTemplate jdbc;

    @Mock
    private ResultSet resultSet;

    @Test
    void detectsFirstAndLastSourceMonths() throws Exception {
        when(resultSet.getObject("first_date")).thenReturn(Date.valueOf("2023-11-20"));
        when(resultSet.getObject("last_date")).thenReturn(Date.valueOf("2026-05-09"));
        stubQueryWithMappedRow();

        Optional<AnalyticsSourceRange> result = new AnalyticsAggregateSourceRangeService(jdbc).findSourceRange();

        assertTrue(result.isPresent());
        assertEquals(LocalDate.of(2023, 11, 20), result.get().firstDate());
        assertEquals(LocalDate.of(2026, 5, 9), result.get().lastDate());
        assertEquals(LocalDate.of(2023, 11, 1), result.get().firstMonth());
        assertEquals(LocalDate.of(2026, 5, 1), result.get().lastMonth());

        verify(jdbc).query(
                anyString(),
                ArgumentMatchers.<Map<String, ?>>argThat(params ->
                        LocalDate.of(2023, 1, 1).equals(params.get("minimumSourceDate"))
                                && LocalDate.of(2027, 12, 31).equals(params.get("maximumSourceDate"))
                ),
                ArgumentMatchers.<RowMapper<AnalyticsSourceRange>>any()
        );
    }

    @Test
    void returnsEmptyWhenSourcesHaveNoDates() throws Exception {
        when(resultSet.getObject("first_date")).thenReturn(null);
        when(resultSet.getObject("last_date")).thenReturn(null);
        stubQueryWithMappedRow();

        Optional<AnalyticsSourceRange> result = new AnalyticsAggregateSourceRangeService(jdbc).findSourceRange();

        assertTrue(result.isEmpty());
    }

    private void stubQueryWithMappedRow() {
        when(jdbc.query(
                anyString(),
                ArgumentMatchers.<Map<String, ?>>any(),
                ArgumentMatchers.<RowMapper<AnalyticsSourceRange>>any()
        )).thenAnswer(invocation -> {
            RowMapper<AnalyticsSourceRange> mapper = invocation.getArgument(2);
            List<AnalyticsSourceRange> rows = new ArrayList<>();
            rows.add(mapper.mapRow(resultSet, 0));
            return rows;
        });
    }
}
