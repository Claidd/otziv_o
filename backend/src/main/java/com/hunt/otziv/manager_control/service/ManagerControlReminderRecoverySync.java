package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.client_messages.api.ClientReminderQueueRestored;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.ConcurrentSkipListSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * A restored periodic reminder queue closes its "delivery failed" card on the control board. The
 * in-memory event covers this process; the committed restart journal (its row count) covers
 * restorations whose event was lost, for example by a restart right after the commit. Because the
 * count is not remembered across restarts, the first pass after a start refreshes today (a no-op
 * re-check when nothing was lost).
 * The previous day is refreshed only while its closing window lasts (until 05:00) and only when its
 * control is still open: the board never reopens or rescores a closed or past day.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ManagerControlReminderRecoverySync {
    private static final ZoneId IRKUTSK = ZoneId.of("Asia/Irkutsk");

    private final ObjectProvider<ManagerControlBoardWorkflow> board;
    private final ObjectProvider<com.hunt.otziv.client_messages.api.PeriodicReminderRestart> recoveries;
    private final ConcurrentSkipListSet<LocalDate> pending = new ConcurrentSkipListSet<>();
    private long seenGeneration;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void restored(ClientReminderQueueRestored event) {
        if (event.controlDate() != null) {
            pending.add(event.controlDate());
            // A recovery just after midnight may still close the previous day's card; the board
            // skips that day unless its control is open and inside the closing window.
            pending.add(event.controlDate().minusDays(1));
        }
    }

    @Scheduled(fixedDelay = 15000, initialDelay = 15000, scheduler = "periodicReminderRecoveryScheduler")
    public void synchronize() {
        synchronize(LocalDateTime.now(IRKUTSK));
    }

    void synchronize(LocalDateTime now) {
        try {
            long generation = recoveries.getObject().reminderRecoveryGeneration();
            if (generation != seenGeneration) {
                seenGeneration = generation;
                LocalDate today = now.toLocalDate();
                pending.add(today);
                if (ManagerControlDayLifecycle.isPreviousDayClosingWindow(today.minusDays(1), now)) {
                    pending.add(today.minusDays(1));
                }
            }
        } catch (RuntimeException unavailable) {
            log.warn("Reminder recovery journal unavailable: error={}", unavailable.toString(), unavailable);
        }
        for (var date : pending.stream().limit(2).toList()) {
            if (!pending.remove(date)) continue;
            try { board.getObject().synchronizeDailySnapshot(date); }
            catch (RuntimeException retry) {
                pending.add(date);
                log.warn("Restored reminder control sync delayed: date={}, error={}", date, retry.toString(), retry);
            }
        }
    }
}
