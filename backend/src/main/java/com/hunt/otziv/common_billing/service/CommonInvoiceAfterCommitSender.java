package com.hunt.otziv.common_billing.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** The existing best-effort post-commit notification boundary; never moves money. */
@Service
@RequiredArgsConstructor
@Slf4j
public class CommonInvoiceAfterCommitSender {
    private final ApplicationEventPublisher events;

    public void send(Long invoiceId, boolean manual) {
        Request request = new Request(invoiceId, manual);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            events.publishEvent(request);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try { events.publishEvent(request); }
                catch (RuntimeException error) {
                    log.warn("Common invoice post-commit send failed invoiceId={}", invoiceId, error);
                }
            }
        });
    }

    public record Request(Long invoiceId, boolean manual) {}
}
