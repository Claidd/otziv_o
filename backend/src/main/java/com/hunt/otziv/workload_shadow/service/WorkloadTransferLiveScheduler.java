package com.hunt.otziv.workload_shadow.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class WorkloadTransferLiveScheduler {

    private final WorkloadTransferWorkflowService workflowService;
    private final WorkloadTransferOfferService offerService;
    private final WorkloadTransferOfferDeliveryService deliveryService;
    private final WorkloadTransferExecutionService executionService;
    private final WorkloadEmergencyAssignmentService emergencyAssignmentService;
    private final WorkloadEmergencyNotificationDeliveryService emergencyNotificationDeliveryService;

    @Scheduled(fixedDelay = 60_000L, initialDelay = 180_000L)
    public void tick() {
        try {
            WorkloadTransferWorkflowService.StageResult workflows =
                    workflowService.stageEligibleRecommendations();
            if (!workflows.enabled()) {
                return;
            }
            WorkloadTransferOfferService.OfferStageResult offers =
                    offerService.stageNextOffers();
            int delivered = deliveryService.deliverDueOffers();
            int applied = (int) executionService.applyAcceptedWorkflows().stream()
                    .filter(value -> "APPLIED".equals(value.status()))
                    .count();
            int emergencyApplied = emergencyAssignmentService
                    .applyStaffingFallbacks()
                    .size();
            int emergencyNotified =
                    emergencyNotificationDeliveryService.deliverDue();
            if (workflows.staged() > 0
                    || offers.staged() > 0
                    || delivered > 0
                    || applied > 0
                    || emergencyApplied > 0
                    || emergencyNotified > 0) {
                log.info(
                        "Workload live tick: workflows={}, offers={}, delivered={}, applied={}, "
                                + "emergencyApplied={}, emergencyNotified={}",
                        workflows.staged(),
                        offers.staged(),
                        delivered,
                        applied,
                        emergencyApplied,
                        emergencyNotified
                );
            }
        } catch (RuntimeException exception) {
            log.error(
                    "Workload live tick failed; assignment mutations are transactional, "
                            + "while completed staging or Telegram delivery will be reconciled",
                    exception
            );
        }
    }
}
