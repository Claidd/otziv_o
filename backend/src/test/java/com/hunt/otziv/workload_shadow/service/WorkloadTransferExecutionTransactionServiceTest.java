package com.hunt.otziv.workload_shadow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hunt.otziv.workload_shadow.dto.WorkloadLiveSettingsResponse;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferExecutionRepository;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferExecutionRepository.ExecutionContextProjection;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferExecutionRepository.WorkerManagerAssignmentProjection;
import com.hunt.otziv.workload_shadow.transfer.WorkloadTransferGraphQueryService;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkloadTransferExecutionTransactionServiceTest {

    private static final long WORKFLOW_ID = 51L;
    private static final long SOURCE_WORKER_ID = 11L;
    private static final long TARGET_WORKER_ID = 12L;
    private static final long MANAGER_ID = 21L;
    private static final long COMPANY_ID = 31L;

    @Mock private WorkloadTransferExecutionRepository repository;
    @Mock private WorkloadTransferGraphQueryService graphQueryService;
    @Mock private WorkloadTransferGraphSnapshotService graphSnapshotService;
    @Mock private WorkloadLiveSettingsService liveSettingsService;
    @Mock private WorkloadShadowSettingsService shadowSettingsService;

    private WorkloadTransferExecutionTransactionService service;

    @BeforeEach
    void setUp() {
        service = new WorkloadTransferExecutionTransactionService(
                repository,
                graphQueryService,
                graphSnapshotService,
                liveSettingsService,
                shadowSettingsService
        );
        when(shadowSettingsService.current()).thenReturn(null);
        when(shadowSettingsService.zone(null)).thenReturn(ZoneId.of("Asia/Irkutsk"));
        lenient().when(repository.closeAcceptedCandidateForBlockedWorkflow(
                eq(WORKFLOW_ID),
                anyString(),
                any()
        )).thenReturn(1);
        lenient().when(repository.closeAcceptedOfferForBlockedWorkflow(
                eq(WORKFLOW_ID),
                anyString(),
                anyString(),
                any()
        )).thenReturn(1);
    }

    @Test
    void missingCompanyStopsBeforeGraphOrAssignmentMutation() {
        prepareClaimedContext();
        when(repository.lockCompanyForTransfer(COMPANY_ID)).thenReturn(Optional.empty());
        when(repository.blockWorkflow(
                eq(WORKFLOW_ID),
                eq("BLOCKED_COMPANY_MISSING"),
                eq("BLOCKED_COMPANY_MISSING"),
                anyString(),
                any()
        )).thenReturn(1);

        var result = service.apply(WORKFLOW_ID, 3L);

        assertThat(result.status()).isEqualTo("BLOCKED_COMPANY_MISSING");
        verifyNoInteractions(graphQueryService, graphSnapshotService);
    }

    @Test
    void settlementBoundaryIsCheckedOnlyAfterCompanyAndOrdersAreLocked() {
        prepareClaimedContext();
        when(repository.lockCompanyForTransfer(COMPANY_ID))
                .thenReturn(Optional.of(COMPANY_ID));
        when(repository.lockActiveSourceOrderIds(SOURCE_WORKER_ID, COMPANY_ID))
                .thenReturn(List.of(101L, 102L));
        when(repository.countFinanciallyUnsafeOrders(SOURCE_WORKER_ID, COMPANY_ID))
                .thenReturn(1L);
        when(repository.blockWorkflow(
                eq(WORKFLOW_ID),
                eq("BLOCKED_FINANCIAL"),
                eq("BLOCKED_FINANCIAL"),
                anyString(),
                any()
        )).thenReturn(1);

        var result = service.apply(WORKFLOW_ID, 3L);

        assertThat(result.status()).isEqualTo("BLOCKED_FINANCIAL");
        assertThat(result.message()).contains("финансов");
        InOrder locksThenCheck = inOrder(repository);
        locksThenCheck.verify(repository).lockCompanyForTransfer(COMPANY_ID);
        locksThenCheck.verify(repository)
                .lockActiveSourceOrderIds(SOURCE_WORKER_ID, COMPANY_ID);
        locksThenCheck.verify(repository)
                .countFinanciallyUnsafeOrders(SOURCE_WORKER_ID, COMPANY_ID);
        verifyNoInteractions(graphQueryService, graphSnapshotService);
    }

    @Test
    void changedSourceManagerStopsBeforeCompanyLockOrGraphMutation() {
        List<WorkerManagerAssignmentProjection> changedAssignments = List.of(
                assignment(SOURCE_WORKER_ID, 999L),
                assignment(TARGET_WORKER_ID, MANAGER_ID)
        );
        prepareClaimedContext(changedAssignments, false);
        when(repository.blockWorkflow(
                eq(WORKFLOW_ID),
                eq("BLOCKED_MANAGER_CHANGED"),
                eq("BLOCKED_MANAGER_CHANGED"),
                anyString(),
                any()
        )).thenReturn(1);

        var result = service.apply(WORKFLOW_ID, 3L);

        assertThat(result.status()).isEqualTo("BLOCKED_MANAGER_CHANGED");
        assertThat(result.message()).contains("менеджер");
        verifyNoInteractions(graphQueryService, graphSnapshotService);
    }

    @Test
    void expiredWorkflowClosesAcceptedCandidateAndOfferBeforeWorkflow() {
        prepareExpiredContext();
        when(repository.blockWorkflow(
                eq(WORKFLOW_ID),
                eq("BLOCKED_EXPIRED"),
                eq("BLOCKED_EXPIRED"),
                anyString(),
                any()
        )).thenReturn(1);

        var result = service.apply(WORKFLOW_ID, 3L);

        assertThat(result.status()).isEqualTo("BLOCKED_EXPIRED");
        InOrder terminalOrder = inOrder(repository);
        terminalOrder.verify(repository)
                .closeAcceptedCandidateForBlockedWorkflow(
                        eq(WORKFLOW_ID),
                        anyString(),
                        any()
                );
        terminalOrder.verify(repository)
                .closeAcceptedOfferForBlockedWorkflow(
                        eq(WORKFLOW_ID),
                        eq("BLOCKED_EXPIRED"),
                        anyString(),
                        any()
                );
        terminalOrder.verify(repository).blockWorkflow(
                eq(WORKFLOW_ID),
                eq("BLOCKED_EXPIRED"),
                eq("BLOCKED_EXPIRED"),
                anyString(),
                any()
        );
        verifyNoInteractions(graphQueryService, graphSnapshotService);
    }

    private void prepareExpiredContext() {
        WorkloadLiveSettingsResponse settings = liveSettings();
        when(liveSettingsService.current()).thenReturn(settings);
        when(liveSettingsService.applicationAllowed(settings)).thenReturn(true);
        when(repository.claimWorkflow(eq(WORKFLOW_ID), eq(3L), any())).thenReturn(1);

        ExecutionContextProjection context = mock(ExecutionContextProjection.class);
        when(context.getWorkflowId()).thenReturn(WORKFLOW_ID);
        when(context.getManagerId()).thenReturn(MANAGER_ID);
        when(context.getDecisionDate()).thenReturn(
                LocalDate.now(ZoneId.of("Asia/Irkutsk")).minusDays(1)
        );
        when(context.getTargetEligible()).thenReturn(1L);
        when(repository.findClaimedContext(WORKFLOW_ID)).thenReturn(Optional.of(context));
        when(liveSettingsService.managerAllowed(settings, MANAGER_ID)).thenReturn(true);
    }

    private ExecutionContextProjection prepareClaimedContext() {
        List<WorkerManagerAssignmentProjection> assignments = List.of(
                assignment(SOURCE_WORKER_ID, MANAGER_ID),
                assignment(TARGET_WORKER_ID, MANAGER_ID)
        );
        return prepareClaimedContext(assignments, true);
    }

    private ExecutionContextProjection prepareClaimedContext(
            List<WorkerManagerAssignmentProjection> assignments,
            boolean includeCompany
    ) {
        return prepareClaimedContext(
                assignments,
                includeCompany,
                LocalDate.now(ZoneId.of("Asia/Irkutsk"))
        );
    }

    private ExecutionContextProjection prepareClaimedContext(
            List<WorkerManagerAssignmentProjection> assignments,
            boolean includeCompany,
            LocalDate decisionDate
    ) {
        WorkloadLiveSettingsResponse settings = liveSettings();
        when(liveSettingsService.current()).thenReturn(settings);
        when(liveSettingsService.applicationAllowed(settings)).thenReturn(true);
        when(repository.claimWorkflow(eq(WORKFLOW_ID), eq(3L), any())).thenReturn(1);

        ExecutionContextProjection context = mock(ExecutionContextProjection.class);
        when(context.getWorkflowId()).thenReturn(WORKFLOW_ID);
        when(context.getManagerId()).thenReturn(MANAGER_ID);
        when(context.getSourceWorkerId()).thenReturn(SOURCE_WORKER_ID);
        when(context.getTargetWorkerId()).thenReturn(TARGET_WORKER_ID);
        if (includeCompany) {
            when(context.getCompanyId()).thenReturn(COMPANY_ID);
        }
        when(context.getDecisionDate()).thenReturn(decisionDate);
        when(context.getTargetEligible()).thenReturn(1L);
        when(repository.findClaimedContext(WORKFLOW_ID)).thenReturn(Optional.of(context));
        when(liveSettingsService.managerAllowed(settings, MANAGER_ID)).thenReturn(true);
        when(repository.lockWorkerManagerAssignments(
                List.of(SOURCE_WORKER_ID, TARGET_WORKER_ID)
        )).thenReturn(assignments);
        return context;
    }

    private WorkerManagerAssignmentProjection assignment(long workerId, long managerId) {
        WorkerManagerAssignmentProjection assignment =
                mock(WorkerManagerAssignmentProjection.class);
        when(assignment.getWorkerId()).thenReturn(workerId);
        when(assignment.getManagerId()).thenReturn(managerId);
        return assignment;
    }

    private WorkloadLiveSettingsResponse liveSettings() {
        return new WorkloadLiveSettingsResponse(
                "LIVE",
                true,
                "2026-07-01",
                14,
                168,
                1,
                List.of(MANAGER_ID),
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
