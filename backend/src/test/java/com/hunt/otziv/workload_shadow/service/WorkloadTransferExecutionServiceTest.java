package com.hunt.otziv.workload_shadow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hunt.otziv.workload_shadow.dto.WorkloadLiveSettingsResponse;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferExecutionRepository;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferExecutionRepository.ReadyWorkflowProjection;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class WorkloadTransferExecutionServiceTest {

    @Mock private WorkloadTransferExecutionRepository repository;
    @Mock private WorkloadTransferExecutionTransactionService transactionService;
    @Mock private WorkloadTransferExecutionFailureService failureService;
    @Mock private WorkloadLiveSettingsService liveSettingsService;
    @Mock private WorkloadShadowSettingsService shadowSettingsService;

    private WorkloadTransferExecutionService service;

    @BeforeEach
    void setUp() {
        service = new WorkloadTransferExecutionService(
                repository,
                transactionService,
                failureService,
                liveSettingsService,
                shadowSettingsService
        );
    }

    @Test
    void disabledContourCannotApplyAcceptedWorkflows() {
        WorkloadLiveSettingsResponse settings = settings("SHADOW", false);
        when(liveSettingsService.current()).thenReturn(settings);
        when(liveSettingsService.applicationAllowed(settings)).thenReturn(false);

        assertThat(service.applyAcceptedWorkflows()).isEmpty();
        verifyNoInteractions(repository, transactionService, failureService);
    }

    @Test
    void executionFailureIsConvertedToABlockedResultAfterTransactionRollback() {
        WorkloadLiveSettingsResponse settings = settings("LIVE", true);
        when(liveSettingsService.current()).thenReturn(settings);
        when(liveSettingsService.applicationAllowed(settings)).thenReturn(true);
        ReadyWorkflowProjection ready = mock(ReadyWorkflowProjection.class);
        when(ready.getWorkflowId()).thenReturn(41L);
        when(ready.getWorkflowVersion()).thenReturn(7L);
        when(repository.findReadyWorkflows(10)).thenReturn(List.of(ready));
        when(transactionService.apply(41L, 7L))
                .thenThrow(new IllegalStateException(
                        "outer",
                        new IllegalArgumentException("row count changed")
                ));

        var results = service.applyAcceptedWorkflows();

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.workflowId()).isEqualTo(41L);
            assertThat(result.status()).isEqualTo("BLOCKED_EXECUTION");
            assertThat(result.message()).contains("row count changed");
        });
        verify(failureService).block(41L, "EXECUTION_FAILED", "row count changed");
    }

    @Test
    void successfulExecutionKeepsTheAtomicServiceResult() {
        WorkloadLiveSettingsResponse settings = settings("CANARY", true);
        when(liveSettingsService.current()).thenReturn(settings);
        when(liveSettingsService.applicationAllowed(settings)).thenReturn(true);
        ReadyWorkflowProjection ready = mock(ReadyWorkflowProjection.class);
        when(ready.getWorkflowId()).thenReturn(41L);
        when(ready.getWorkflowVersion()).thenReturn(7L);
        when(repository.findReadyWorkflows(10)).thenReturn(List.of(ready));
        var applied = new WorkloadTransferExecutionTransactionService.ApplyResult(
                41L,
                81L,
                "APPLIED",
                "ok"
        );
        when(transactionService.apply(41L, 7L)).thenReturn(applied);

        assertThat(service.applyAcceptedWorkflows()).containsExactly(applied);
        verifyNoInteractions(failureService);
    }

    @Test
    void ownerConfirmationRejectsInvalidOrChangedWorkflow() {
        assertThatThrownBy(() -> service.confirmByOwner(0))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Workflow не указан");
        verify(repository, never()).confirmByOwner(anyLong(), any());

        when(shadowSettingsService.current()).thenReturn(null);
        when(shadowSettingsService.zone(null)).thenReturn(ZoneId.of("Asia/Irkutsk"));
        when(repository.confirmByOwner(eq(41L), any())).thenReturn(0);

        assertThatThrownBy(() -> service.confirmByOwner(41L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Workflow уже подтверждён");
    }

    private WorkloadLiveSettingsResponse settings(String mode, boolean applyEnabled) {
        return new WorkloadLiveSettingsResponse(
                mode,
                applyEnabled,
                "2026-08-01",
                14,
                168,
                1,
                List.of(),
                30,
                "00:00",
                "23:59",
                1,
                3,
                30,
                5,
                false,
                1
        );
    }
}
