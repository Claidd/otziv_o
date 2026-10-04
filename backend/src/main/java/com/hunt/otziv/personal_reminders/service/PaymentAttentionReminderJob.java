package com.hunt.otziv.personal_reminders.service;

import com.hunt.otziv.client_messages.repository.ScheduledClientMessageStateRepository;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.p_products.dto.OrderPaidPostCommitEvent;
import com.hunt.otziv.personal_reminders.repository.PersonalReminderRepository;
import com.hunt.otziv.scheduler.service.SchedulerLeaseService;
import com.hunt.otziv.scheduler.service.SchedulerLeaseService.Lease;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/** A separate read-side observer of invoices approaching their payment deadline. */
@Component
@Slf4j
public class PaymentAttentionReminderJob {
    private static final String LEASE_NAME = "payment-attention-reminders";
    private static final Duration LEASE_DURATION = Duration.ofMinutes(2);
    private static final int BATCH_SIZE = 200;

    private final SchedulerLeaseService leases;
    private final ScheduledClientMessageStateRepository states;
    private final PersonalReminderRepository reminders;
    private final PaymentAttentionReminderService service;
    private final AppSettingService settings;
    private final TransactionTemplate transaction;

    public PaymentAttentionReminderJob(SchedulerLeaseService leases,
                                       ScheduledClientMessageStateRepository states,
                                       PersonalReminderRepository reminders,
                                       PaymentAttentionReminderService service,
                                       AppSettingService settings,
                                       PlatformTransactionManager transactionManager) {
        this.leases = leases;
        this.states = states;
        this.reminders = reminders;
        this.service = service;
        this.settings = settings;
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
            for (Long id : reminders.findPaymentAttentionIdsToClose(
                    PersonalReminderService.SOURCE_PAYMENT_ATTENTION, PageRequest.of(0, BATCH_SIZE))) {
                apply(lease, id, false);
            }
            if (!settings.getBoolean(AppSettingService.CLIENT_MESSAGES_PAYMENT_OVERDUE_ENABLED, true)
                    || !settings.getBoolean(AppSettingService.CLIENT_MESSAGES_PAYMENT_OVERDUE_LIVE_ENABLED, false)
                    || !settings.getBoolean(AppSettingService.CLIENT_MESSAGES_PAYMENT_REMINDER_ENABLED, true)) {
                return;
            }
            LocalDateTime now = LocalDateTime.now();
            for (Long id : states.findApproachingPaymentDueIds(now, now.plusDays(2),
                    PageRequest.of(0, BATCH_SIZE))) {
                apply(lease, id, true);
            }
        } finally {
            leases.release(lease);
        }
    }

    @Async("orderPaymentPostCommitExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void closeAfterPayment(OrderPaidPostCommitEvent event) {
        if (event == null) return;
        try {
            service.closeForPaidOrder(event.orderId());
        } catch (RuntimeException error) {
            // The scheduled pass retries closure independently of the payment flow.
            log.warn("Payment attention reminder close deferred for order {} ({})",
                    event.orderId(), error.getClass().getSimpleName());
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
