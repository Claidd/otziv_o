package com.hunt.otziv.worker_performance.service;

import com.hunt.otziv.scheduler.service.SchedulerLeaseService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Rescans committed state after restarts/failures; each idempotent batch holds a database fence. */
@Component
public class WorkerProgressSnapshotJob {
    private final StaffDailyProgressService progress;
    private final SchedulerLeaseService leases;
    private final TransactionTemplate transaction;
    private final MeterRegistry metrics;
    private final AtomicLong lastSuccess = new AtomicLong();
    private final AtomicLong enabled = new AtomicLong();
    public WorkerProgressSnapshotJob(StaffDailyProgressService progress, SchedulerLeaseService leases,
                                     PlatformTransactionManager transactions, MeterRegistry metrics) {
        this.progress = progress; this.leases = leases; this.metrics = metrics;
        this.transaction = new TransactionTemplate(transactions);
        transaction.setTimeout(45);
        metrics.gauge("otziv.projection.last.success.epoch", java.util.List.of(io.micrometer.core.instrument.Tag.of("projection", "worker-progress")), lastSuccess);
        metrics.gauge("otziv.projection.enabled", java.util.List.of(io.micrometer.core.instrument.Tag.of("projection", "worker-progress")), enabled);
    }
    @Scheduled(fixedDelayString = "${otziv.projection.worker.refresh-ms:30000}", initialDelayString = "${otziv.projection.initial-delay-ms:60000}", scheduler = "interactiveProjectionScheduler")
    public void refresh() {
        enabled.set(progress.progressEnabled() ? 1 : 0);
        if (enabled.get() == 0) return;
        var acquired = leases.tryAcquire("worker-progress-snapshot", Duration.ofMinutes(2));
        if (acquired.isEmpty()) return;
        var lease = acquired.get();
        try {
            LocalDate date = LocalDate.now(ZoneId.of("Asia/Irkutsk"));
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            // Bounded work per tick; progress is committed with each batch and survives restarts.
            for (int batch = 0; batch < 4 && System.nanoTime() < deadline; batch++) {
                Long next = transaction.execute(status -> {
                    leases.holdForTransaction(lease, Duration.ofMinutes(2));
                    long after = leases.projectionCursor(lease, date);
                    long cursor = progress.refreshSnapshotBatch(after, date);
                    if (cursor == 0) progress.rebuildMonthlyAggregates(date.withDayOfMonth(1), false);
                    leases.advanceProjectionCursor(lease, date, cursor);
                    return cursor;
                });
                if (next == null || next == 0) {
                    lastSuccess.set(java.time.Instant.now().getEpochSecond());
                    metrics.counter("otziv.projection.refresh", "projection", "worker-progress", "result", "success").increment();
                    return;
                }
            }
        } catch (RuntimeException failure) {
            metrics.counter("otziv.projection.refresh", "projection", "worker-progress", "result", "error").increment();
            // No SQL text/parameters in diagnostics; scheduler retries from committed source next tick.
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Worker snapshot refresh failed ({})", failure.getClass().getSimpleName());
        } finally { leases.release(lease); }
    }
}
