package com.hunt.otziv.p_products.next_order;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@Slf4j
@RequiredArgsConstructor
public class NextOrderAutomationListener {

    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 300L;

    private final NextOrderAutomationService automationService;
    private final NextOrderRequestService requestService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(NextOrderRequestedEvent event) {
        Exception lastException = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                automationService.createNextOrder(event.requestId());
                return;
            } catch (Exception exception) {
                lastException = exception;
                if (!isRetryableLockFailure(exception) || attempt == MAX_ATTEMPTS) {
                    break;
                }
                log.warn(
                        "Автосоздание следующего заказа по заявке {} упало на блокировке, повтор {}/{}",
                        event.requestId(),
                        attempt + 1,
                        MAX_ATTEMPTS,
                        exception
                );
                sleepBeforeRetry();
            }
        }

        if (lastException != null) {
            requestService.markFailed(event.requestId(), lastException);
        }
    }

    private boolean isRetryableLockFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String className = current.getClass().getName();
            String message = current.getMessage();
            if (className.contains("CannotAcquireLock")
                    || className.contains("Deadlock")
                    || className.contains("LockAcquisition")
                    || className.contains("MySQLTransactionRollback")
                    || (message != null && message.toLowerCase().contains("deadlock found"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(RETRY_DELAY_MS);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }
}
