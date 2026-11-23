package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.client_messages.api.PeriodicReminderRestart;
import com.hunt.otziv.scheduler.service.SchedulerLeaseService;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Bounded automatic recovery of future periodic occurrences, never replaying an unknown send. */
@Service
@RequiredArgsConstructor
@Slf4j
public class PeriodicReminderRecoveryJob {
    private final PeriodicReminderRestart scheduled;
    private final ManagerControlReminderRestart repair;
    private final ManagerClientMessageQueue queue;
    private final SchedulerLeaseService leases;
    private long afterStateId;

    @Scheduled(fixedDelayString = "${manager-control.periodic-recovery.delay-ms:60000}", initialDelay = 45000,
            scheduler = "periodicReminderRecoveryScheduler")
    public void recover() {
        var acquired = leases.tryAcquire("periodic-reminder-future-recovery", Duration.ofMinutes(5));
        if (acquired.isEmpty()) return;
        var lease = acquired.get();
        try {
            releaseCancelledManualCommands();
            var candidates = scheduled.deferredReminderRecoveryCandidates(afterStateId, 20);
            if (candidates.isEmpty()) { afterStateId = 0; return; }
            long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
            for (var candidate : candidates) {
                if (System.nanoTime() >= deadline) break;
                afterStateId = candidate.stateId();
                try {
                    var next = repair.recoverDeferredAutomatically(candidate.stateId(), candidate.managerId());
                    if (next.isPresent()) log.info("Periodic reminder future queue restored: stateId={}, next={}", candidate.stateId(), next.get());
                } catch (RuntimeException held) {
                    log.warn("Periodic reminder future recovery held: stateId={}, error={}", candidate.stateId(), held.toString(), held);
                }
            }
        } catch (RuntimeException unavailable) {
            log.warn("Periodic reminder recovery scan unavailable: error={}", unavailable.toString(), unavailable);
        } finally { leases.release(lease); }
    }

    /** Orders whose reminders wait only for a manual message cancelled before sending, over two days ago. */
    private void releaseCancelledManualCommands() {
        try {
            for (Long orderId : queue.ordersHeldByCancelledManual(10)) {
                try {
                    int released = repair.releaseCancelledManualAutomatically(orderId);
                    if (released > 0) log.info("Cancelled manual commands released: orderId={}, count={}", orderId, released);
                } catch (RuntimeException held) {
                    log.warn("Cancelled manual command kept: orderId={}, error={}", orderId, held.toString());
                }
            }
        } catch (RuntimeException unavailable) {
            log.warn("Cancelled manual command scan unavailable: error={}", unavailable.toString());
        }
    }
}
