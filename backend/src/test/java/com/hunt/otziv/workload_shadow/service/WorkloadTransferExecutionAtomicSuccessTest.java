package com.hunt.otziv.workload_shadow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.workload_shadow.dto.WorkloadLiveSettingsResponse;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferExecutionRepository;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferExecutionRepository.ExecutionContextProjection;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferExecutionRepository.WorkerManagerAssignmentProjection;
import com.hunt.otziv.workload_shadow.transfer.WorkloadTransferCompanyGraph;
import com.hunt.otziv.workload_shadow.transfer.WorkloadTransferCompanyGraph.BadTaskNode;
import com.hunt.otziv.workload_shadow.transfer.WorkloadTransferCompanyGraph.OrderNode;
import com.hunt.otziv.workload_shadow.transfer.WorkloadTransferCompanyGraph.RecoveryTaskNode;
import com.hunt.otziv.workload_shadow.transfer.WorkloadTransferCompanyGraph.ReviewNode;
import com.hunt.otziv.workload_shadow.transfer.WorkloadTransferGraphQueryService;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkloadTransferExecutionAtomicSuccessTest {

    private static final long WORKFLOW_ID = 51L;
    private static final long EXECUTION_ID = 61L;
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
        when(shadowSettingsService.zone(null))
                .thenReturn(ZoneId.of("Asia/Irkutsk"));
    }

    @Test
    void appliesEveryActiveStageAndClosesJournalsOnlyAfterExactRowCounts() {
        WorkloadLiveSettingsResponse live = liveSettings();
        when(liveSettingsService.current()).thenReturn(live);
        when(liveSettingsService.applicationAllowed(live)).thenReturn(true);
        when(liveSettingsService.managerAllowed(live, MANAGER_ID)).thenReturn(true);
        when(repository.claimWorkflow(eq(WORKFLOW_ID), eq(3L), any()))
                .thenReturn(1);
        ExecutionContextProjection context = context();
        when(repository.findClaimedContext(WORKFLOW_ID))
                .thenReturn(Optional.of(context));
        List<WorkerManagerAssignmentProjection> managerAssignments = List.of(
                assignment(SOURCE_WORKER_ID),
                assignment(TARGET_WORKER_ID)
        );
        when(repository.lockWorkerManagerAssignments(
                List.of(SOURCE_WORKER_ID, TARGET_WORKER_ID)
        )).thenReturn(managerAssignments);
        when(repository.lockCompanyForTransfer(COMPANY_ID))
                .thenReturn(Optional.of(COMPANY_ID));
        when(repository.lockActiveSourceOrderIds(SOURCE_WORKER_ID, COMPANY_ID))
                .thenReturn(List.of(101L));
        when(repository.countFinanciallyUnsafeOrders(SOURCE_WORKER_ID, COMPANY_ID))
                .thenReturn(0L);

        WorkloadTransferCompanyGraph graph = graph();
        when(graphQueryService.findActiveGraphs(
                List.of(SOURCE_WORKER_ID),
                context.getDecisionDate()
        )).thenReturn(Map.of(SOURCE_WORKER_ID, List.of(graph)));
        when(graphSnapshotService.snapshot(graph))
                .thenReturn(new WorkloadTransferGraphSnapshotService.Snapshot(
                        "{}",
                        "same-fingerprint"
                ));
        when(graphSnapshotService.json(any())).thenReturn("{}");

        when(repository.insertExecution(
                eq(WORKFLOW_ID),
                anyString(),
                eq("{}"),
                any(),
                any()
        )).thenReturn(1);
        when(repository.findExecutionIdByIdempotencyKey(anyString()))
                .thenReturn(Optional.of(EXECUTION_ID));
        when(repository.auditOrders(
                eq(EXECUTION_ID),
                eq(List.of(101L)),
                eq(SOURCE_WORKER_ID),
                eq(TARGET_WORKER_ID),
                eq(COMPANY_ID),
                any()
        )).thenReturn(1);
        when(repository.auditReviews(
                eq(EXECUTION_ID),
                eq(List.of(201L, 202L)),
                eq(SOURCE_WORKER_ID),
                eq(TARGET_WORKER_ID),
                eq(COMPANY_ID),
                any()
        )).thenReturn(2);
        when(repository.auditBadTasks(
                eq(EXECUTION_ID),
                eq(List.of(301L)),
                eq(SOURCE_WORKER_ID),
                eq(TARGET_WORKER_ID),
                eq(COMPANY_ID),
                any()
        )).thenReturn(1);
        when(repository.auditRecoveryTasks(
                eq(EXECUTION_ID),
                eq(List.of(401L)),
                eq(SOURCE_WORKER_ID),
                eq(TARGET_WORKER_ID),
                eq(COMPANY_ID),
                any()
        )).thenReturn(1);
        when(repository.ensureTargetCompanyLink(COMPANY_ID, TARGET_WORKER_ID))
                .thenReturn(1);
        when(repository.auditAddedCompanyLink(
                eq(EXECUTION_ID),
                eq(COMPANY_ID),
                eq(TARGET_WORKER_ID),
                any()
        )).thenReturn(1);
        when(repository.clearCredentialPreparations(List.of(201L, 202L)))
                .thenReturn(2);
        when(repository.transferReviews(
                List.of(201L, 202L),
                SOURCE_WORKER_ID,
                TARGET_WORKER_ID
        )).thenReturn(2);
        when(repository.transferBadTasks(
                List.of(301L),
                SOURCE_WORKER_ID,
                TARGET_WORKER_ID
        )).thenReturn(1);
        when(repository.transferRecoveryTasks(
                eq(List.of(401L)),
                eq(SOURCE_WORKER_ID),
                eq(TARGET_WORKER_ID),
                any()
        )).thenReturn(1);
        when(repository.transferOrders(
                List.of(101L),
                SOURCE_WORKER_ID,
                TARGET_WORKER_ID,
                COMPANY_ID
        )).thenReturn(1);
        when(repository.markExecutionApplied(
                eq(EXECUTION_ID),
                eq(1),
                eq(2),
                eq(1),
                eq(1),
                eq("{}"),
                any()
        )).thenReturn(1);
        when(repository.markWorkflowApplied(eq(WORKFLOW_ID), any()))
                .thenReturn(1);

        var result = service.apply(WORKFLOW_ID, 3L);

        assertThat(result.status()).isEqualTo("APPLIED");
        assertThat(result.executionId()).isEqualTo(EXECUTION_ID);
        InOrder atomicOrder = inOrder(repository);
        atomicOrder.verify(repository).lockWorkerManagerAssignments(
                List.of(SOURCE_WORKER_ID, TARGET_WORKER_ID)
        );
        atomicOrder.verify(repository).lockCompanyForTransfer(COMPANY_ID);
        atomicOrder.verify(repository)
                .lockActiveSourceOrderIds(SOURCE_WORKER_ID, COMPANY_ID);
        atomicOrder.verify(repository).auditOrders(
                eq(EXECUTION_ID),
                eq(List.of(101L)),
                eq(SOURCE_WORKER_ID),
                eq(TARGET_WORKER_ID),
                eq(COMPANY_ID),
                any()
        );
        atomicOrder.verify(repository).transferReviews(
                List.of(201L, 202L),
                SOURCE_WORKER_ID,
                TARGET_WORKER_ID
        );
        atomicOrder.verify(repository).transferOrders(
                List.of(101L),
                SOURCE_WORKER_ID,
                TARGET_WORKER_ID,
                COMPANY_ID
        );
        atomicOrder.verify(repository).markExecutionApplied(
                eq(EXECUTION_ID),
                eq(1),
                eq(2),
                eq(1),
                eq(1),
                eq("{}"),
                any()
        );
        atomicOrder.verify(repository).markWorkflowApplied(eq(WORKFLOW_ID), any());
        verify(repository).removeSourceCompanyLinkIfUnused(
                COMPANY_ID,
                SOURCE_WORKER_ID
        );
    }

    private ExecutionContextProjection context() {
        ExecutionContextProjection value = mock(ExecutionContextProjection.class);
        when(value.getManagerId()).thenReturn(MANAGER_ID);
        when(value.getSourceWorkerId()).thenReturn(SOURCE_WORKER_ID);
        when(value.getTargetWorkerId()).thenReturn(TARGET_WORKER_ID);
        when(value.getCompanyId()).thenReturn(COMPANY_ID);
        when(value.getDecisionDate()).thenReturn(
                LocalDate.now(ZoneId.of("Asia/Irkutsk"))
        );
        when(value.getTargetEligible()).thenReturn(1L);
        when(value.getGraphFingerprint()).thenReturn("same-fingerprint");
        return value;
    }

    private WorkerManagerAssignmentProjection assignment(long workerId) {
        WorkerManagerAssignmentProjection value =
                mock(WorkerManagerAssignmentProjection.class);
        when(value.getWorkerId()).thenReturn(workerId);
        when(value.getManagerId()).thenReturn(MANAGER_ID);
        return value;
    }

    private WorkloadTransferCompanyGraph graph() {
        ReviewNode firstReview = mock(ReviewNode.class);
        ReviewNode secondReview = mock(ReviewNode.class);
        when(firstReview.reviewId()).thenReturn(201L);
        when(secondReview.reviewId()).thenReturn(202L);
        when(firstReview.warnings()).thenReturn(List.of());
        when(secondReview.warnings()).thenReturn(List.of());

        BadTaskNode badTask = mock(BadTaskNode.class);
        when(badTask.taskId()).thenReturn(301L);
        when(badTask.warnings()).thenReturn(List.of());
        RecoveryTaskNode recovery = mock(RecoveryTaskNode.class);
        when(recovery.taskId()).thenReturn(401L);
        when(recovery.warnings()).thenReturn(List.of());

        OrderNode order = mock(OrderNode.class);
        when(order.orderId()).thenReturn(101L);
        when(order.reviews()).thenReturn(List.of(firstReview, secondReview));
        when(order.badTasks()).thenReturn(List.of(badTask));
        when(order.recoveryTasks()).thenReturn(List.of(recovery));
        when(order.warnings()).thenReturn(List.of());

        WorkloadTransferCompanyGraph graph =
                mock(WorkloadTransferCompanyGraph.class);
        when(graph.companyId()).thenReturn(COMPANY_ID);
        when(graph.orders()).thenReturn(List.of(order));
        when(graph.detachedReviews()).thenReturn(List.of());
        when(graph.detachedBadTasks()).thenReturn(List.of());
        when(graph.detachedRecoveryTasks()).thenReturn(List.of());
        when(graph.warnings()).thenReturn(List.of());
        return graph;
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
                true,
                1
        );
    }
}
