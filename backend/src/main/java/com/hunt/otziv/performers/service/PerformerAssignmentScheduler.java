package com.hunt.otziv.performers.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PerformerAssignmentScheduler {

    private final PerformerAssignmentService assignmentService;

    @Scheduled(
            fixedDelayString = "${performers.scheduler.fixed-delay-ms:60000}",
            initialDelayString = "${performers.scheduler.initial-delay-ms:60000}"
    )
    public void tick() {
        int created = assignmentService.createDueAssignments();
        int expired = assignmentService.expireOffers();
        int offered = assignmentService.offerQueuedAssignments();
        int ready = assignmentService.notifyReadyToPublish();
        if (created > 0 || expired > 0 || offered > 0 || ready > 0) {
            log.info(
                    "Performer scheduler tick: created={}, expired={}, offered={}, readyNotifications={}",
                    created,
                    expired,
                    offered,
                    ready
            );
        }
    }
}
