package com.hunt.otziv.personal_reminders.service;

import com.hunt.otziv.personal_reminders.repository.PersonalReminderRepository;
import com.hunt.otziv.scheduler.service.SchedulerLeaseService;
import com.hunt.otziv.scheduler.service.SchedulerLeaseService.Lease;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Rechecks historical and future fail-closed cards in bounded, independently committed batches. */
@Component
@Slf4j
public class PaymentFailClosedReminderJob {
    private static final String LEASE_NAME = "payment-fail-closed-reminders";
    private static final Duration LEASE_DURATION = Duration.ofMinutes(2);
    private static final int BATCH_SIZE = 100;
    private static final int MAX_PER_SOURCE = 2_000;

    private final SchedulerLeaseService leases;
    private final PersonalReminderRepository reminders;
    private final PaymentFailClosedReminderReconciliationService reconciliation;
    private final TransactionTemplate transaction;
    private final boolean dryRun;

    public PaymentFailClosedReminderJob(
            SchedulerLeaseService leases,
            PersonalReminderRepository reminders,
            PaymentFailClosedReminderReconciliationService reconciliation,
            PlatformTransactionManager transactionManager,
            @Value("${payment.fail-closed-reminders.dry-run:false}") boolean dryRun
    ) {
        this.leases = leases;
        this.reminders = reminders;
        this.reconciliation = reconciliation;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transaction.setTimeout(20);
        this.dryRun = dryRun;
    }

    @Scheduled(
            fixedDelayString = "${payment.fail-closed-reminders.interval-ms:300000}",
            initialDelayString = "${payment.fail-closed-reminders.initial-delay-ms:60000}"
    )
    public void reconcile() {
        var acquired = leases.tryAcquire(LEASE_NAME, LEASE_DURATION);
        if (acquired.isEmpty()) {
            return;
        }
        Lease lease = acquired.get();
        int checked = 0;
        int eligible = 0;
        int closed = 0;
        Map<String, Integer> skipped = new LinkedHashMap<>();
        try {
            for (String source : List.of("PAYMENT_FAIL_CLOSED",
                    PaymentFailClosedReminderReconciliationService.LIVE_ROUTING_SOURCE)) {
                long afterId = 0;
                int processedSource = 0;
                while (processedSource < MAX_PER_SOURCE) {
                    List<Long> ids = reminders.findOpenSourceIdsAfter(source, afterId,
                            PageRequest.of(0, Math.min(BATCH_SIZE, MAX_PER_SOURCE - processedSource)));
                    if (ids.isEmpty()) {
                        break;
                    }
                    for (Long id : ids) {
                        try {
                            var outcome = transaction.execute(status -> {
                                leases.holdForTransaction(lease, LEASE_DURATION);
                                return reconciliation.reconcile(id, dryRun);
                            });
                            checked++;
                            if (outcome == null) {
                                skipped.merge("missing_outcome", 1, Integer::sum);
                            } else if (outcome.eligible()) {
                                eligible++;
                                if (outcome.closed()) {
                                    closed++;
                                }
                            } else {
                                skipped.merge(outcome.reason(), 1, Integer::sum);
                            }
                        } catch (RuntimeException exception) {
                            skipped.merge("reconciliation_error", 1, Integer::sum);
                            log.warn("Payment fail-closed reminder reconciliation failed: reminderId={}", id,
                                    exception);
                        }
                    }
                    processedSource += ids.size();
                    afterId = ids.getLast();
                }
            }
        } finally {
            leases.release(lease);
        }
        if (checked > 0 || !skipped.isEmpty()) {
            log.info("Payment fail-closed reminder reconciliation: dryRun={}, checked={}, eligible={}, closed={}, skipped={}",
                    dryRun, checked, eligible, closed, skipped);
        }
    }
}
