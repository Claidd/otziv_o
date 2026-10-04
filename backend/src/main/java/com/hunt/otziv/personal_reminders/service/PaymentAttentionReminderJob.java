package com.hunt.otziv.personal_reminders.service;

import com.hunt.otziv.client_messages.api.PaymentDeadlineNoticeSource;
import com.hunt.otziv.personal_reminders.repository.PersonalReminderRepository;
import com.hunt.otziv.scheduler.service.SchedulerLeaseService;
import com.hunt.otziv.scheduler.service.SchedulerLeaseService.Lease;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** A separate read-side observer of invoices approaching their payment deadline. */
@Component
@Slf4j
public class PaymentAttentionReminderJob {
    private static final String LEASE_NAME = "payment-attention-reminders";
    private static final Duration LEASE_DURATION = Duration.ofMinutes(2);
    private static final int BATCH_SIZE = 200;

    private final SchedulerLeaseService leases;
    private final PaymentDeadlineNoticeSource deadlines;
    private final PersonalReminderRepository reminders;
    private final PaymentAttentionReminderService service;
    private final TransactionTemplate transaction;

    public PaymentAttentionReminderJob(SchedulerLeaseService leases,
                                       PaymentDeadlineNoticeSource deadlines,
                                       PersonalReminderRepository reminders,
                                       PaymentAttentionReminderService service,
                                       PlatformTransactionManager transactionManager) {
        this.leases = leases;
        this.deadlines = deadlines;
        this.reminders = reminders;
        this.service = service;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setTimeout(20);
    }

    @Scheduled(fixedDelayString = "${payment.attention.interval-ms:300000}",
            initialDelayString = "${payment.attention.initial-delay-ms:60000}")
    public void refresh() {
        var acquired = leases.tryAcquire(LEASE_NAME, LEASE_DURATION);
        if (acquired.isEmpty()) return;
        Lease lease = acquired.get();
        try {
            long afterId = 0;
            while (true) {
                List<Long> ids = reminders.findOpenSourceIdsAfter(
                        PersonalReminderService.SOURCE_PAYMENT_ATTENTION, afterId,
                        PageRequest.of(0, BATCH_SIZE));
                if (ids.isEmpty()) break;
                for (Long id : ids) apply(lease, id, false);
                afterId = ids.getLast();
            }
            LocalDateTime now = LocalDateTime.now();
            afterId = 0;
            while (true) {
                List<Long> ids = deadlines.approachingIds(now, now.plusDays(2), afterId, BATCH_SIZE);
                if (ids.isEmpty()) break;
                for (Long id : ids) apply(lease, id, true);
                afterId = ids.getLast();
            }
        } finally {
            leases.release(lease);
        }
    }

    private void apply(Lease lease, Long id, boolean create) {
        try {
            transaction.execute(status -> {
                leases.holdForTransaction(lease, LEASE_DURATION);
                if (create) service.remindIfDue(id, LocalDateTime.now());
                else service.closeIfNoLongerAwaitingPayment(id);
                return null;
            });
        } catch (RuntimeException error) {
            log.warn("Payment attention reminder refresh failed for source {} ({})",
                    id, error.getClass().getSimpleName());
        }
    }
}