package com.hunt.otziv.workload_shadow.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hunt.otziv.workload_shadow.repository.WorkloadShadowProgressView;
import com.hunt.otziv.workload_shadow.repository.WorkloadShadowProjectionRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkloadShadowProgressReadServiceTest {

    @Test
    void mapsFinalizedProgressInOneBulkRead() {
        WorkloadShadowProjectionRepository repository =
                mock(WorkloadShadowProjectionRepository.class);
        WorkloadShadowProgressView row = mock(WorkloadShadowProgressView.class);
        LocalDate date = LocalDate.of(2026, 7, 28);
        when(row.getWorkerId()).thenReturn(1L);
        when(row.getCompletedUnits()).thenReturn(46L);
        when(row.getEligibleUnits()).thenReturn(46L);
        when(row.getLateExcludedUnits()).thenReturn(5L);
        when(row.getProgressPercent()).thenReturn(new BigDecimal("100.00"));
        when(row.getReached100()).thenReturn(1L);
        when(row.getReached100Once()).thenReturn(1L);
        when(row.getFirstReached100At()).thenReturn(date.atTime(18, 54));
        when(row.getLastReached100At()).thenReturn(date.atTime(18, 54));
        when(repository.findFinalizedWorkerProgress(List.of(1L), date))
                .thenReturn(List.of(row));
        WorkloadShadowProgressReadService service =
                new WorkloadShadowProgressReadService(repository);

        Map<Long, WorkloadShadowProgressReadService.Progress> result =
                service.findFinalizedProgress(List.of(1L), date);

        assertEquals(46, result.get(1L).completed());
        assertEquals(46, result.get(1L).eligible());
        assertEquals(5, result.get(1L).lateExcluded());
        assertEquals(100, result.get(1L).percent());
        assertTrue(result.get(1L).reached100());
        assertTrue(result.get(1L).reached100Once());
        assertEquals(date.atTime(18, 54), result.get(1L).firstReached100At());
        assertEquals(date.atTime(18, 54), result.get(1L).lastReached100At());
    }

    @Test
    void fallsBackToEmptyProgressWhenProjectionReadFails() {
        WorkloadShadowProjectionRepository repository =
                mock(WorkloadShadowProjectionRepository.class);
        LocalDate date = LocalDate.of(2026, 7, 28);
        doThrow(new IllegalStateException("database unavailable"))
                .when(repository)
                .findFinalizedWorkerProgress(List.of(1L), date);
        WorkloadShadowProgressReadService service =
                new WorkloadShadowProgressReadService(repository);

        Map<Long, WorkloadShadowProgressReadService.Progress> result =
                service.findFinalizedProgress(List.of(1L), date);

        assertTrue(result.isEmpty());
    }
}
