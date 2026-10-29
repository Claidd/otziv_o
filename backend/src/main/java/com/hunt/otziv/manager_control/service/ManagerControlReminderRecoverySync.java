package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.client_messages.api.ClientReminderQueueRestored;
import java.time.LocalDate;
import java.util.concurrent.ConcurrentSkipListSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Coalesces committed recoveries, including the preceding day at midnight, without recording manager activity. */
@Service
@RequiredArgsConstructor
@Slf4j
public class ManagerControlReminderRecoverySync {
    private final ObjectProvider<ManagerControlBoardWorkflow> board;
    private final ObjectProvider<com.hunt.otziv.client_messages.api.PeriodicReminderRestart> recoveries;
    private final ConcurrentSkipListSet<LocalDate> pending = new ConcurrentSkipListSet<>();
    private long seenGeneration;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void restored(ClientReminderQueueRestored event) {
        if (event.controlDate() != null) {
            pending.add(event.controlDate());
            pending.add(event.controlDate().minusDays(1));
        }
    }

    @Scheduled(fixedDelay = 15000, initialDelay = 15000, scheduler = "periodicReminderRecoveryScheduler")
    public void synchronize() {
        try {
            long generation = recoveries.getObject().reminderRecoveryGeneration();
            if (generation != seenGeneration) {
                seenGeneration = generation;
                var date = LocalDate.now(java.time.ZoneId.of("Asia/Irkutsk"));
                pending.add(date); pending.add(date.minusDays(1));
            }
        } catch (RuntimeException unavailable) {
            log.warn("Reminder recovery journal unavailable: errorType={}", unavailable.getClass().getSimpleName());
        }
        for (var date : pending.stream().limit(2).toList()) {
            if (!pending.remove(date)) continue;
            try { board.getObject().synchronizeDailySnapshot(date); }
            catch (RuntimeException retry) {
                pending.add(date);
                log.warn("Restored reminder control sync delayed: date={}, errorType={}", date, retry.getClass().getSimpleName());
            }
        }
    }
}
