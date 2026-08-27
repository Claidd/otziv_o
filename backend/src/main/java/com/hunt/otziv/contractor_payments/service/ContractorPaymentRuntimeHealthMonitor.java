package com.hunt.otziv.contractor_payments.service;

import com.hunt.otziv.client_messages.repository.ScheduledClientMessageStateRepository;
import com.hunt.otziv.payments.service.PaymentIssueReminderService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Makes a requested-but-ineffective payment runtime observable and recovers invoices as soon as
 * the global gate becomes healthy. The normal invoice worker remains the only sender; this class
 * merely moves previously postponed, fail-closed states back into its due queue.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ContractorPaymentRuntimeHealthMonitor {

    private static final int NOTIFICATION_LIMIT = 100;
    private static final String ISSUE_SOURCE = "PAYMENT_RUNTIME_BLOCKED";

    private final ContractorPaymentRuntimeSwitch runtimeSwitch;
    private final ContractorPaymentRolloutStateService rolloutStateService;
    private final ScheduledClientMessageStateRepository messageStateRepository;
    private final PaymentIssueReminderService paymentIssueReminderService;
    private final ContractorPaymentBusinessClock businessClock;

    private Boolean previousEffective;
    private boolean previousRequested;

    @Scheduled(
            fixedDelayString = "${otziv.contractor-payments.runtime-health-delay-ms:60000}",
            initialDelayString = "${otziv.contractor-payments.runtime-health-initial-delay-ms:120000}"
    )
    @Transactional
    public synchronized void monitor() {
        try {
            ContractorPaymentRolloutStateService.Snapshot rollout = rolloutStateService.freshSnapshot();
            boolean requested = rollout.completionAccountingActive() && rollout.routingRequested();
            boolean effective = runtimeSwitch.liveRoutingEnabled();
            boolean stateChanged = previousEffective == null
                    || previousEffective != effective
                    || previousRequested != requested;

            if (requested && effective && (previousEffective == null || !previousEffective)) {
                LocalDateTime now = businessClock.now();
                int expedited = messageStateRepository.expediteLiveRoutingBlockedPaymentRetries(now);
                if (expedited > 0) {
                    log.info("LIVE routing recovered; expedited {} blocked payment invoices", expedited);
                }
            } else if (requested && !effective && stateChanged) {
                notifyBlockedOrders(blockerSummary());
            }

            previousRequested = requested;
            previousEffective = effective;
        } catch (RuntimeException exception) {
            log.error(
                    "Contractor payment runtime health monitor failed: failure={}",
                    exception.getClass().getSimpleName()
            );
        }
    }

    private void notifyBlockedOrders(String blockerSummary) {
        List<Long> orderIds = messageStateRepository.findLiveRoutingBlockedPaymentOrderIds(
                PageRequest.of(0, NOTIFICATION_LIMIT)
        );
        for (Long orderId : orderIds) {
            paymentIssueReminderService.notifyOrderIssue(
                    orderId,
                    ISSUE_SOURCE,
                    orderId,
                    "LIVE-routing заблокирован · заказ №" + orderId,
                    "Новые реквизиты не были отправлены. Причины: " + blockerSummary
                            + ". После восстановления система автоматически вернёт счёт в очередь."
            );
        }
        log.error(
                "LIVE routing requested but ineffective: affectedOrders={}, reasons={}",
                orderIds.size(),
                blockerSummary
        );
    }

    private String blockerSummary() {
        List<String> blockers = new ArrayList<>(runtimeSwitch.liveRoutingBlockers());
        return blockers.isEmpty()
                ? "эффективный LIVE-routing не подтверждён; проверьте durable rollout/readiness flags"
                : String.join("; ", blockers);
    }
}
