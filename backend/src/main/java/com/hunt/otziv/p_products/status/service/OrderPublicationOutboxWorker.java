package com.hunt.otziv.p_products.status.service;

import com.hunt.otziv.client_messages.api.ClientMessageDelivery;
import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.common_billing.api.PublicationInvoiceCompletion;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.service.OrderStatusCheckerService;
import com.hunt.otziv.p_products.status.service.OrderPublicationOutbox.Claim;
import com.hunt.otziv.p_products.status.service.OrderStatusNotificationService.PreparedPublicationProgress;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Polling is authoritative; progress failures cannot prevent the order completion workflow. */
@Service
@Slf4j
public class OrderPublicationOutboxWorker {
    private final OrderPublicationOutbox outbox;
    private final OrderRepository orders;
    private final OrderStatusCheckerService completion;
    private final OrderStatusNotificationService notifications;
    private final ClientMessageDelivery delivery;
    private final AppSettingService settings;
    private final PublicationInvoiceCompletion billing;
    private final TransactionTemplate transaction;
    private final TransactionTemplate outsideTransaction;

    public OrderPublicationOutboxWorker(OrderPublicationOutbox outbox, OrderRepository orders,
            OrderStatusCheckerService completion, OrderStatusNotificationService notifications,
            ClientMessageDelivery delivery, AppSettingService settings, PublicationInvoiceCompletion billing, PlatformTransactionManager manager) {
        this.outbox = outbox; this.orders = orders; this.completion = completion; this.notifications = notifications;
        this.delivery = delivery; this.settings = settings; this.billing = billing;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        outsideTransaction = new TransactionTemplate(manager);
        outsideTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }

    @Scheduled(fixedDelayString="${orders.publication-outbox.delay-ms:5000}", initialDelayString="${orders.publication-outbox.initial-delay-ms:10000}")
    public void drain() {
        // Independent queues: a disabled or uncertain progress notification never holds an invoice hostage.
        for (long id : outbox.dueCompletions(20)) completeOrder(id);
        for (long id : outbox.dueBilling(20)) completeBilling(id);
        for (long id : outbox.dueDeliveries(20)) deliver(id);
    }

    public void completeBilling(long id) {
        outsideTransaction.executeWithoutResult(status -> {
            try {
                boolean done = outbox.orderId(id).map(billing::finalizePublishedInvoiceForOrder).orElse(true);
                outbox.finishBilling(id, done);
            } catch (RuntimeException failure) {
                log.warn("Publication common invoice remains queued: intentId={}", id, failure);
                outbox.finishBilling(id, false);
            }
        });
    }

    public void completeOrder(long id) {
        try {
            transaction.executeWithoutResult(status -> outbox.orderId(id).ifPresent(orderId -> {
                var order = orders.findByIdForCounterUpdate(orderId).orElse(null);
                if (!outbox.lockCompletion(id)) return;
                if (order != null) {
                    try {
                        if (!completion.checkPublicationCompletion(order)) {
                            outbox.deferOrderCompletion(orderId);
                            return;
                        }
                    }
                    catch (Exception failure) { throw new IllegalStateException("Publication completion failed", failure); }
                }
                outbox.completeOrder(orderId);
            }));
        } catch (RuntimeException failure) {
            log.warn("Publication completion remains queued: intentId={}", id, failure);
            outbox.completionFailed(id);
        }
    }

    public void deliver(long id) {
        try {
            outbox.claim(id, liveEnabled()).ifPresent(claim -> outsideTransaction.executeWithoutResult(status -> dispatch(claim)));
        } catch (RuntimeException failure) {
            // The committed barrier survives a process/transaction failure. Next sweep only reads its receipt.
            log.warn("Publication delivery remains unconfirmed: intentId={}", id, failure);
        }
    }

    private void dispatch(Claim claim) {
        PreparedPublicationProgress prepared;
        try { prepared = outbox.decode(claim); }
        catch (RuntimeException invalid) {
            outbox.finish(claim, ClientMessageSendResult.failed("invalid_snapshot", "Publication snapshot needs review"));
            return;
        }
        if (claim.receiptOnly()) {
            var outcome = delivery.recordedOutcome(claim.operationId());
            if (outbox.finish(claim, outcome) && OrderPublicationOutbox.confirmed(outcome)) {
                try { notifications.notifyPublicationProgressOutcome(prepared, outcome); }
                catch (RuntimeException alertFailure) { log.warn("Publication recovery notification failed: intentId={}", claim.id(), alertFailure); }
            }
            return;
        }
        boolean orderExists;
        try { orderExists = orders.existsById(claim.orderId()); }
        catch (RuntimeException unavailable) {
            outbox.pauseBeforeProvider(claim);
            return;
        }
        if (!orderExists) {
            outbox.skipDeletedOrderBeforeProvider(claim);
            return;
        }
        if (!maySend(prepared)) {
            outbox.pauseBeforeProvider(claim);
            return;
        }
        ClientMessageSendResult result;
        try { result = notifications.dispatchPublicationProgress(prepared); }
        catch (RuntimeException unknown) { result = ClientMessageSendResult.failed("operation_unknown", "Publication result is unconfirmed"); }
        if (outbox.finish(claim, result) && result != null) {
            try { notifications.notifyPublicationProgressOutcome(prepared, result); }
            catch (RuntimeException alertFailure) { log.warn("Publication auth notification failed: intentId={}", claim.id(), alertFailure); }
        }
    }

    private boolean maySend(PreparedPublicationProgress prepared) {
        try {
            return prepared.target() != null && delivery.publicationProgressEnabled(prepared.target().companyId()) && liveEnabled();
        } catch (RuntimeException unavailable) { return false; }
    }

    private boolean liveEnabled() {
        try {
            return settings.getBooleanFreshFailClosed(AppSettingService.CLIENT_MESSAGES_IMMEDIATE_ENABLED, true)
                    && settings.getBooleanFreshFailClosed(AppSettingService.CLIENT_PUBLICATION_PROGRESS_REPORTS_ENABLED, true);
        } catch (RuntimeException unavailable) { return false; }
    }
}
