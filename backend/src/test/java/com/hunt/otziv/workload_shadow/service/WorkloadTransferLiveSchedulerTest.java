package com.hunt.otziv.workload_shadow.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkloadTransferLiveSchedulerTest {

    @Mock private WorkloadTransferWorkflowService workflowService;
    @Mock private WorkloadTransferOfferService offerService;
    @Mock private WorkloadTransferOfferDeliveryService deliveryService;
    @Mock private WorkloadTransferExecutionService executionService;
    @Mock private WorkloadEmergencyAssignmentService emergencyAssignmentService;
    @Mock
    private WorkloadEmergencyNotificationDeliveryService
            emergencyNotificationDeliveryService;

    private WorkloadTransferLiveScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new WorkloadTransferLiveScheduler(
                workflowService,
                offerService,
                deliveryService,
                executionService,
                emergencyAssignmentService,
                emergencyNotificationDeliveryService
        );
    }

    @Test
    void disabledLiveModeStopsBeforeOffersOrAnyMutation() {
        when(workflowService.stageEligibleRecommendations()).thenReturn(
                new WorkloadTransferWorkflowService.StageResult(
                        false,
                        0,
                        0,
                        0,
                        "disabled"
                )
        );

        scheduler.tick();

        verify(workflowService).stageEligibleRecommendations();
        verifyNoInteractions(
                offerService,
                deliveryService,
                executionService,
                emergencyAssignmentService,
                emergencyNotificationDeliveryService
        );
    }

    @Test
    void enabledTickRunsTheProtectedPipelineInFull() {
        when(workflowService.stageEligibleRecommendations()).thenReturn(
                new WorkloadTransferWorkflowService.StageResult(
                        true,
                        1,
                        0,
                        0,
                        "staged"
                )
        );
        when(offerService.stageNextOffers()).thenReturn(
                new WorkloadTransferOfferService.OfferStageResult(
                        true,
                        1,
                        0,
                        "staged"
                )
        );
        when(deliveryService.deliverDueOffers()).thenReturn(1);
        when(executionService.applyAcceptedWorkflows()).thenReturn(List.of(
                new WorkloadTransferExecutionTransactionService.ApplyResult(
                        51L,
                        61L,
                        "APPLIED",
                        "ok"
                )
        ));
        when(emergencyAssignmentService.applyStaffingFallbacks()).thenReturn(
                List.of(
                        new WorkloadEmergencyAssignmentTransactionService.ApplyResult(
                                71L,
                                "APPLIED",
                                "ok"
                        )
                )
        );
        when(emergencyNotificationDeliveryService.deliverDue()).thenReturn(1);

        scheduler.tick();

        verify(workflowService).stageEligibleRecommendations();
        verify(offerService).stageNextOffers();
        verify(deliveryService).deliverDueOffers();
        verify(executionService).applyAcceptedWorkflows();
        verify(emergencyAssignmentService).applyStaffingFallbacks();
        verify(emergencyNotificationDeliveryService).deliverDue();
    }

    @Test
    void failureIsContainedInsideOneTickAndCannotEscapeSchedulerThread() {
        when(workflowService.stageEligibleRecommendations())
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThatCode(scheduler::tick).doesNotThrowAnyException();

        verifyNoInteractions(
                offerService,
                deliveryService,
                executionService,
                emergencyAssignmentService,
                emergencyNotificationDeliveryService
        );
    }
}
