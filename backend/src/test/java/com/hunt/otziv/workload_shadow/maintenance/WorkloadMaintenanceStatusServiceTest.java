package com.hunt.otziv.workload_shadow.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hunt.otziv.workload_shadow.repository.WorkloadMaintenanceStatusRepository;
import com.hunt.otziv.workload_shadow.repository.WorkloadMaintenanceStatusRepository.StatusProjection;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkloadMaintenanceStatusServiceTest {

    private static final LocalDateTime NOW =
            LocalDateTime.of(2026, 7, 30, 18, 0);

    private final WorkloadMaintenanceStatusRepository repository =
            mock(WorkloadMaintenanceStatusRepository.class);
    private final WorkloadMaintenanceStatusService service =
            new WorkloadMaintenanceStatusService(repository);

    @Test
    void failedRepairMakesMaintenanceUnhealthyWhileNewRetentionHasGracePeriod() {
        StatusProjection repair = status(
                "REPAIR",
                NOW.minusMinutes(2),
                NOW.minusMinutes(10),
                NOW.minusMinutes(1),
                NOW.minusDays(1),
                1
        );
        StatusProjection retention = status(
                "RETENTION",
                null,
                null,
                null,
                NOW.minusHours(1),
                0
        );
        when(repository.findRuntimeStatuses()).thenReturn(List.of(repair, retention));

        var health = service.health(NOW);

        assertThat(health.healthy()).isFalse();
        assertThat(health.repairStatus()).isEqualTo("FAILED");
        assertThat(health.retentionStatus()).isEqualTo("INITIALIZING");
        assertThat(health.lastErrorCode()).isEqualTo("DataAccessException");
    }

    @Test
    void freshSuccessfulRunsAreHealthy() {
        StatusProjection repair = status(
                "REPAIR",
                NOW.minusMinutes(1),
                NOW.minusMinutes(1),
                null,
                NOW.minusDays(2),
                0
        );
        StatusProjection retention = status(
                "RETENTION",
                NOW.minusHours(2),
                NOW.minusHours(2),
                null,
                NOW.minusDays(2),
                0
        );
        when(repository.findRuntimeStatuses()).thenReturn(List.of(repair, retention));

        var health = service.health(NOW);

        assertThat(health.healthy()).isTrue();
        assertThat(health.repairStatus()).isEqualTo("UP");
        assertThat(health.retentionStatus()).isEqualTo("UP");
    }

    private StatusProjection status(
            String task,
            LocalDateTime startedAt,
            LocalDateTime succeededAt,
            LocalDateTime failedAt,
            LocalDateTime createdAt,
            int failures
    ) {
        StatusProjection value = mock(StatusProjection.class);
        when(value.getTask()).thenReturn(task);
        when(value.getLastStartedAt()).thenReturn(startedAt);
        when(value.getLastSucceededAt()).thenReturn(succeededAt);
        when(value.getLastFailedAt()).thenReturn(failedAt);
        when(value.getCreatedAt()).thenReturn(createdAt);
        when(value.getConsecutiveFailures()).thenReturn(failures);
        when(value.getLastErrorCode()).thenReturn(
                failedAt == null ? null : "DataAccessException"
        );
        when(value.getLastErrorMessage()).thenReturn(
                failedAt == null ? null : "No active transaction"
        );
        return value;
    }
}
