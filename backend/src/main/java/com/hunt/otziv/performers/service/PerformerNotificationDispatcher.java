package com.hunt.otziv.performers.service;

import com.hunt.otziv.performers.repository.PerformerNotificationRepository.Intent;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "performers.notifications.dispatch-enabled", havingValue = "true")
public class PerformerNotificationDispatcher {
    private final PerformerNotificationService notifications;
    private final PerformerTelegramNotificationService telegram;
    private final PerformerNotificationMetrics metrics;

    @Scheduled(fixedDelayString = "${performers.notifications.delay-ms:5000}", initialDelay = 30000)
    public void tick() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Performer dispatch must run outside a transaction");
        }
        try { metrics.expired(notifications.expireClaims()); }
        catch (RuntimeException failure) { metrics.error(); throw failure; }
        for (int i = 0; i < 20; i++) {
            Optional<Intent> claimed;
            try { claimed = notifications.claim(); }
            catch (RuntimeException failure) { metrics.error(); throw failure; }
            if (claimed.isEmpty()) return;
            var intent = claimed.get();
            try {
                var message = notifications.prepare(intent);
                if (message.isEmpty()) continue;
                Integer messageId = telegram.sendOnce(message.get()).orElse(null);
                if (messageId == null) metrics.unknownTransport();
                if (!notifications.complete(intent, messageId)) metrics.fenced();
            } catch (RuntimeException exception) {
                metrics.error();
                // Persist ambiguity; neither exception nor lease expiry authorizes resend.
                try { if (!notifications.complete(intent, null)) metrics.fenced(); }
                catch (RuntimeException completionFailure) {
                    log.warn("Performer notification completion unavailable; lease recovery will retain UNKNOWN");
                }
            }
        }
    }
}
