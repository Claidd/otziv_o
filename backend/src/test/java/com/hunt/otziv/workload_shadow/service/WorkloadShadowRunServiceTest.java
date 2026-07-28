package com.hunt.otziv.workload_shadow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.workload_shadow.repository.WorkloadShadowRunRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkloadShadowRunServiceTest {

    @Mock private WorkloadShadowRunRepository runRepository;

    private WorkloadShadowRunService service;

    @BeforeEach
    void setUp() {
        service = new WorkloadShadowRunService(runRepository);
    }

    @Test
    void startUsesExplicitIdentityQueriesAndReturnsGeneratedId() {
        when(runRepository.startRun(
                "MANUAL-TRIGGER-THAT-IS-FAR-TOO-L",
                LocalDateTime.of(2026, 7, 27, 12, 0),
                "instance"
        )).thenReturn(1);
        when(runRepository.lastInsertedRunId()).thenReturn(42L);
        LocalDateTime startedAt = LocalDateTime.of(2026, 7, 27, 12, 0);

        long id = service.start(
                " manual-trigger-that-is-far-too-long-for-the-column ",
                "instance",
                startedAt
        );

        assertThat(id).isEqualTo(42L);
        verify(runRepository).startRun(
                "MANUAL-TRIGGER-THAT-IS-FAR-TOO-L",
                startedAt,
                "instance"
        );
        verify(runRepository).lastInsertedRunId();
    }

    @Test
    void completeAndFailUseSingleModifyingQueryEach() {
        LocalDateTime startedAt = LocalDateTime.of(2026, 7, 27, 12, 0);
        LocalDateTime finishedAt = startedAt.plusSeconds(5);
        WorkloadShadowRunService.RunResult result =
                new WorkloadShadowRunService.RunResult(2, 10, 3, 4, 1);

        service.complete(7L, result, startedAt, finishedAt);
        service.fail(
                8L,
                new IllegalStateException("верхний", new RuntimeException("корень")),
                startedAt,
                finishedAt
        );

        verify(runRepository).complete(
                7L,
                finishedAt,
                5_000L,
                2,
                10,
                3,
                4,
                1
        );
        verify(runRepository).fail(
                8L,
                finishedAt,
                5_000L,
                "IllegalStateException",
                "корень"
        );
    }

    @Test
    void lastSuccessfulRunIsOneRepositoryScalarQuery() {
        LocalDateTime value = LocalDateTime.of(2026, 7, 27, 12, 0);
        when(runRepository.lastSuccessfulFinishedAt())
                .thenReturn(Optional.of(value));

        assertThat(service.lastSuccessfulFinishedAt()).isEqualTo(value);
        verify(runRepository).lastSuccessfulFinishedAt();
    }
}
