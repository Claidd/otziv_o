package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.u_users.api.CabinetCacheScope;
import com.hunt.otziv.scheduler.service.SchedulerLeaseService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.*;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class ManagerControlSnapshotJob {
    private final ManagerControlBoardWorkflow board;
    private final ManagerControlReadSnapshots snapshots;
    private final CabinetCacheScope scope;
    private final SchedulerLeaseService leases;
    private final TransactionTemplate transaction;
    private final MeterRegistry metrics;
    private final AtomicLong lastSuccess = new AtomicLong();
    private final AtomicLong enabled = new AtomicLong(1);
    public ManagerControlSnapshotJob(ManagerControlBoardWorkflow board, ManagerControlReadSnapshots snapshots,
            CabinetCacheScope scope, SchedulerLeaseService leases,
            PlatformTransactionManager transactions, MeterRegistry metrics) {
        this.board=board; this.snapshots=snapshots; this.scope=scope; this.leases=leases; this.metrics=metrics;
        transaction=new TransactionTemplate(transactions); transaction.setTimeout(45);
        metrics.gauge("otziv.projection.last.success.epoch", java.util.List.of(io.micrometer.core.instrument.Tag.of("projection","manager-control")),lastSuccess);
        metrics.gauge("otziv.projection.enabled", java.util.List.of(io.micrometer.core.instrument.Tag.of("projection","manager-control")),enabled);
    }
    @Scheduled(fixedDelayString="${otziv.projection.manager.refresh-ms:30000}",initialDelayString="${otziv.projection.initial-delay-ms:60000}",scheduler="interactiveProjectionScheduler")
    public void refresh() {
        var acquired=leases.tryAcquire("manager-control-read-snapshots",Duration.ofMinutes(2));
        if(acquired.isEmpty()) return;
        var lease=acquired.get();
        try {
            LocalDate date=LocalDate.now(ZoneId.of("Asia/Irkutsk"));
            // This read directory lives in the existing management workflow; no foreign repository exposure.
            var ids=board.snapshotManagerIds().stream().sorted().toList();
            long deadline=System.nanoTime()+Duration.ofSeconds(20).toNanos();
            for(int batch=0; batch<25 && System.nanoTime()<deadline; batch++) {
                Boolean completed=transaction.execute(status -> {
                    leases.holdForTransaction(lease,Duration.ofMinutes(2));
                    long after=leases.projectionCursor(lease,date);
                    Long id=ids.stream().filter(value -> value>after).findFirst().orElse(null);
                    if(id==null) {
                        leases.advanceProjectionCursor(lease,date,0);
                        return true;
                    }
                    String fingerprint=scope.fingerprint();
                    long generation=snapshots.generation(id,date);
                    var response=board.snapshotManager(id,date);
                    if(response!=null) snapshots.save(date,fingerprint,generation,response);
                    // Finish the pass in the last real batch. Previously exactly ten
                    // managers consumed the whole tick and waited another 30 seconds
                    // just to discover EOF, allowing otherwise valid snapshots to expire.
                    boolean last = id.equals(ids.getLast());
                    leases.advanceProjectionCursor(lease,date,last ? 0 : id);
                    return last;
                });
                if(Boolean.TRUE.equals(completed)) {
                    board.warmSnapshotScore(date);
                    snapshots.cleanup();
                    lastSuccess.set(Instant.now().getEpochSecond());
                    metrics.counter("otziv.projection.refresh","projection","manager-control","result","success").increment();
                    break;
                }
            }
        } catch(RuntimeException failure) {
            metrics.counter("otziv.projection.refresh","projection","manager-control","result","error").increment();
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Manager snapshot refresh failed ({})",failure.getClass().getSimpleName());
        } finally { leases.release(lease); }
    }
}
