package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItemStatus;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlConcreteItemRepository;
import com.hunt.otziv.scheduler.service.SchedulerLeaseService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class ManagerControlWorkerReminderScheduler {

    static final String LEASE_NAME = "manager-control-worker-explanation-reminders";
    /** A pass sends at most one batch; each claim renews the lease, so this only bounds a crashed owner. */
    static final Duration LEASE_DURATION = Duration.ofMinutes(10);
    private static final int BATCH_SIZE = 100;
    private static final int REMINDER_INTERVAL_HOURS = 3;

    private final ManagerDailyControlConcreteItemRepository concreteItemRepository;
    private final ManagerControlWorkerTaskTelegramCallbackService callbackService;
    private final SchedulerLeaseService leases;

    /**
     * A successful request leaves the card ACTION_TAKEN with a 3-hour follow-up, so pending
     * explanations are selected among all unresolved cards, limited to today's controls: an older
     * day's card is history and today's control has its own card for a problem that remains.
     *
     * <p>One instance runs a pass at a time (rolling deploy overlap): the pass holds a DB lease and
     * every reminder claim renews it inside the claim transaction, so an owner that lost the lease
     * cannot claim another card.
     */
    @Scheduled(
            fixedDelayString = "${manager.worker-explanation-reminders.fixed-delay-ms:300000}",
            initialDelayString = "${manager.worker-explanation-reminders.initial-delay-ms:60000}"
    )
    public void remindPendingExplanations() {
        var acquired = leases.tryAcquire(LEASE_NAME, LEASE_DURATION);
        if (acquired.isEmpty()) {
            return;
        }
        SchedulerLeaseService.Lease lease = acquired.get();
        try {
            remindPendingExplanations(lease);
        } finally {
            leases.release(lease);
        }
    }

    private void remindPendingExplanations(SchedulerLeaseService.Lease lease) {
        LocalDateTime now = LocalDateTime.now();
        List<ManagerDailyControlConcreteItem> pending = concreteItemRepository
                .findPendingWorkerExplanationReminders(
                        now.minusHours(REMINDER_INTERVAL_HOURS),
                        ManagerDailyControlItemStatus.RESOLVED,
                        now.toLocalDate(),
                        PageRequest.of(0, BATCH_SIZE)
                );
        Runnable fence = () -> {
            try {
                leases.holdForTransaction(lease, LEASE_DURATION);
            } catch (RuntimeException lost) {
                throw new LeaseLost(lost);
            }
        };
        for (ManagerDailyControlConcreteItem item : pending) {
            Long itemId = item == null ? null : item.getId();
            try {
                callbackService.remindPendingExplanation(itemId, now, fence);
            } catch (LeaseLost lost) {
                log.warn("Worker explanation reminder pass stopped: the scheduler lease was lost", lost);
                return;
            } catch (RuntimeException exception) {
                log.warn("Не удалось повторно напомнить специалисту по карточке {}", itemId, exception);
            }
        }
    }

    private static final class LeaseLost extends RuntimeException {
        LeaseLost(Throwable cause) {
            super(cause);
        }
    }
}
