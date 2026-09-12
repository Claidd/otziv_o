package com.hunt.otziv.worker_performance.service;

import com.hunt.otziv.scheduler.service.SchedulerLeaseService;
import com.hunt.otziv.u_users.api.TeamDirectoryReader;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Bounded, restartable refresh. A database fence covers each batch and its durable cursor. */
@Component
public class TeamPatternSnapshotJob {
    private final TeamDirectoryReader directory;
    private final TeamPatternAnalysisService analysis;
    private final TeamPatternReadSnapshots snapshots;
    private final SchedulerLeaseService leases;
    private final MeterRegistry metrics;
    private final TransactionTemplate transaction;
    private final AtomicLong enabled = new AtomicLong(), lastSuccess = new AtomicLong();

    public TeamPatternSnapshotJob(TeamDirectoryReader directory, TeamPatternAnalysisService analysis, TeamPatternReadSnapshots snapshots,
                                  SchedulerLeaseService leases, MeterRegistry metrics, PlatformTransactionManager transactions) {
        this.directory = directory; this.analysis = analysis; this.snapshots = snapshots; this.leases = leases; this.metrics = metrics;
        transaction = new TransactionTemplate(transactions); transaction.setTimeout(20);
        var tags = List.of(io.micrometer.core.instrument.Tag.of("projection", "team-pattern"));
        metrics.gauge("otziv.projection.enabled", tags, enabled);
        metrics.gauge("otziv.projection.last.success.epoch", tags, lastSuccess);
    }

    @Scheduled(fixedDelayString="${otziv.projection.team-pattern.refresh-ms:30000}",
            initialDelayString="${otziv.projection.initial-delay-ms:60000}", scheduler="interactiveProjectionScheduler")
    public void refresh() {
        enabled.set(snapshots.enabled() ? 1 : 0);
        if (enabled.get() == 0) return;
        var acquired = leases.tryAcquire("team-pattern-snapshot", Duration.ofMinutes(2));
        if (acquired.isEmpty()) return;
        var lease = acquired.get();
        try {
            LocalDate date = LocalDate.now(ZoneId.of("Asia/Irkutsk"));
            LocalDate month = date.withDayOfMonth(1);
            Map<Long, TeamDirectoryReader.Member> uniqueUsers = new LinkedHashMap<>();
            directory.allActive(TeamDirectoryReader.Role.WORKER).forEach(member -> uniqueUsers.putIfAbsent(member.userId(), member));
            var subjects = uniqueUsers.values().stream().map(member -> new TeamPatternAnalysisService.WorkerPatternSubject(member.id(), member.userId(), "")).toList();
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            for (int batch = 0; batch < 4 && System.nanoTime() < deadline; batch++) {
                Long cursor = transaction.execute(status -> {
                    leases.holdForTransaction(lease, Duration.ofMinutes(2));
                    long after = leases.projectionCursor(lease, date);
                    var selected = subjects.stream().filter(subject -> subject.workerId() > after).limit(25).toList();
                    analysis.rebuildSnapshots(selected, month);
                    analysis.rebuildSnapshots(selected, month.minusMonths(1));
                    long next = selected.size() < 25 ? 0 : selected.getLast().workerId();
                    if (next == 0) snapshots.cleanup(month.minusMonths(1));
                    leases.advanceProjectionCursor(lease, date, next);
                    return next;
                });
                if (cursor == null || cursor == 0) {
                    lastSuccess.set(Instant.now().getEpochSecond());
                    metrics.counter("otziv.projection.refresh", "projection", "team-pattern", "result", "success").increment();
                    return;
                }
            }
        } catch (RuntimeException failure) {
            metrics.counter("otziv.projection.refresh", "projection", "team-pattern", "result", "error").increment();
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Team pattern refresh failed ({})", failure.getClass().getSimpleName());
        } finally { leases.release(lease); }
    }
}
