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
    private final SchedulerLeaseService leases;
    private long afterStateId;

    @Scheduled(fixedDelayString = "${manager-control.periodic-recovery.delay-ms:60000}", initialDelay = 45000,
            scheduler = "periodicReminderRecoveryScheduler")
    public void recover() {
        var acquired = leases.tryAcquire("periodic-reminder-future-recovery", Duration.ofMinutes(5));
        if (acquired.isEmpty()) return;
        var lease = acquired.get();
        try {
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
                    log.warn("Periodic reminder future recovery held: stateId={}, errorType={}", candidate.stateId(), held.getClass().getSimpleName());
                }
            }
        } catch (RuntimeException unavailable) {
            log.warn("Periodic reminder recovery scan unavailable: errorType={}", unavailable.getClass().getSimpleName());
        } finally { leases.release(lease); }
    }
}
