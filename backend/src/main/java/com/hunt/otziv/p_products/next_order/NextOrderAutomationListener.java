package com.hunt.otziv.p_products.next_order;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@Slf4j
@RequiredArgsConstructor
public class NextOrderAutomationListener {

    private final NextOrderAutomationService automationService;
    private final NextOrderRequestService requestService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(NextOrderRequestedEvent event) {
        try {
            automationService.createNextOrder(event.requestId());
        } catch (Exception exception) {
            requestService.markFailed(event.requestId(), exception);
        }
    }
}
