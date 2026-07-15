package com.hunt.otziv.client_messages.service;

import com.hunt.otziv.review_recovery.event.ReviewRecoveryReleasedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@Slf4j
@RequiredArgsConstructor
public class ClientMessageReviewRecoveryReleasedListener {

    private final ScheduledClientMessageService scheduledClientMessageService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void resumePausedMessages(ReviewRecoveryReleasedEvent event) {
        if (event == null || event.orderId() == null || event.orderId() <= 0) {
            return;
        }
        try {
            int released = scheduledClientMessageService.releaseReviewRecoveryHold(event.orderId());
            if (released > 0) {
                log.info("После восстановления отзывов возобновлено сценариев автоответчика: orderId={}, states={}",
                        event.orderId(), released);
            }
        } catch (RuntimeException e) {
            log.warn("Не удалось сразу возобновить автоответчик после восстановления, orderId={}", event.orderId(), e);
        }
    }
}
