package com.hunt.otziv.p_products.next_order.service;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class NextOrderRequestRecoveryScheduler {

    private static final int BATCH_SIZE = 50;
    private static final long STALE_MINUTES = 10L;

    private final NextOrderRequestRecoveryService recoveryService;

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        recover("startup");
    }

    @Scheduled(
            fixedDelayString = "${next-order.recovery.fixed-delay:PT5M}",
            initialDelayString = "${next-order.recovery.initial-delay:PT1M}"
    )
    public void recoverScheduled() {
        recover("scheduled");
    }

    private void recover(String source) {
        try {
            int count = recoveryService.republishStaleRequests(
                    LocalDateTime.now().minusMinutes(STALE_MINUTES),
                    BATCH_SIZE
            );
            if (count > 0) {
                log.info("Повторно запущены зависшие заявки следующего заказа: source={}, count={}", source, count);
            }
        } catch (RuntimeException exception) {
            log.error("Не удалось запустить восстановление заявок следующего заказа: source={}", source, exception);
        }
    }
}
