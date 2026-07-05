package com.hunt.otziv.performers.service;

import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class PerformerPublicationRequestedListener {

    private final PerformerAssignmentService assignmentService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPublicationRequested(PerformerPublicationRequestedEvent event) {
        if (event != null && event.orderId() != null) {
            assignmentService.createAssignmentsForOrder(event.orderId());
        }
    }
}
