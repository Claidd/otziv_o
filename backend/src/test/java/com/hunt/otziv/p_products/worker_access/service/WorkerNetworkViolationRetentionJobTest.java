package com.hunt.otziv.p_products.worker_access.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hunt.otziv.p_products.worker_access.config.WorkerCellularAccessProperties;
import com.hunt.otziv.p_products.worker_access.repository.WorkerNetworkViolationRepository;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkerNetworkViolationRetentionJobTest {

    @Mock
    private WorkerNetworkViolationRepository violationRepository;

    private WorkerCellularAccessProperties properties;
    private WorkerNetworkViolationRetentionJob job;

    @BeforeEach
    void setUp() {
        properties = new WorkerCellularAccessProperties();
        properties.setViolationStatisticsEnabled(true);
        job = new WorkerNetworkViolationRetentionJob(
                properties,
                violationRepository
        );
    }

    @Test
    void cleanupUsesMinimumThirtyDayRetention() {
        properties.setViolationRetentionDays(5);
        ZoneId workerZone = ZoneId.of("Asia/Irkutsk");
        LocalDateTime before = LocalDateTime.now(workerZone).minusDays(30);

        job.cleanup();

        ArgumentCaptor<LocalDateTime> cutoff =
                ArgumentCaptor.forClass(LocalDateTime.class);
        verify(violationRepository).deleteBefore(cutoff.capture());
        LocalDateTime after = LocalDateTime.now(workerZone).minusDays(30);
        assertTrue(!cutoff.getValue().isBefore(before));
        assertTrue(!cutoff.getValue().isAfter(after));
    }

    @Test
    void skipsCleanupWhenStatisticsAreDisabled() {
        properties.setViolationStatisticsEnabled(false);

        job.cleanup();

        verify(violationRepository, never()).deleteBefore(any());
    }

    @Test
    void repositoryFailureDoesNotBreakScheduledJob() {
        doThrow(new IllegalStateException("database unavailable"))
                .when(violationRepository)
                .deleteBefore(any(LocalDateTime.class));

        assertDoesNotThrow(job::cleanup);
    }
}
