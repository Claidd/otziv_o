package com.hunt.otziv.b_bots.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@Slf4j
@RequiredArgsConstructor
public class ReviewAccountPoolAlertScheduler {

    private final ReviewAccountPoolAlertService alertService;

    @Value("${otziv.review-account-pool-alert.enabled:true}")
    private boolean enabled;

    @EventListener(ApplicationReadyEvent.class)
    public void checkOnStartup() {
        checkSafely();
    }

    @Scheduled(
            initialDelayString = "${otziv.review-account-pool-alert.initial-delay-ms:60000}",
            fixedDelayString = "${otziv.review-account-pool-alert.fixed-delay-ms:60000}"
    )
    public void scheduledCheck() {
        checkSafely();
    }

    public void checkAfterPoolChange() {
        if (!enabled) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            checkSafely();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                checkSafely();
            }
        });
    }

    private void checkSafely() {
        if (!enabled) {
            return;
        }
        try {
            alertService.reconcileAndNotify();
        } catch (RuntimeException e) {
            log.warn("Не удалось проверить остаток пула аккаунтов", e);
        }
    }
}
