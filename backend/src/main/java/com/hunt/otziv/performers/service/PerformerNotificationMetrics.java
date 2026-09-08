package com.hunt.otziv.performers.service;

import com.hunt.otziv.performers.repository.PerformerNotificationRepository;
import com.hunt.otziv.performers.repository.PerformerNotificationRepository.OperationalSnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Cached low-cardinality gauges: scraping never queries the queue or exposes delivery identities. */
@Component
public class PerformerNotificationMetrics {
    private final PerformerNotificationRepository repository;
    private final MeterRegistry registry;
    private volatile OperationalSnapshot snapshot = new OperationalSnapshot(0,0,0,0,0,0,0,0);
    private volatile long sampledAt;
    public PerformerNotificationMetrics(PerformerNotificationRepository repository,ObjectProvider<MeterRegistry> provider) {
        this.repository=repository; this.registry=provider.getIfAvailable();
        if(registry==null) return;
        String p="otziv.performers.notifications.";
        registry.gauge(p+"pending",this,m -> m.snapshot.pending());
        registry.gauge(p+"processing",this,m -> m.snapshot.processing());
        registry.gauge(p+"unknown",this,m -> m.snapshot.unknown());
        registry.gauge(p+"blocked",this,m -> m.snapshot.blocked());
        registry.gauge(p+"legacy_unknown",this,m -> m.snapshot.legacyUnknown());
        registry.gauge(p+"oldest_pending_seconds",this,m -> m.snapshot.oldestPendingSeconds());
        registry.gauge(p+"oldest_unknown_seconds",this,m -> m.snapshot.oldestUnknownSeconds());
        registry.gauge(p+"expired_leases",this,m -> m.snapshot.expiredLeases());
        registry.gauge(p+"snapshot_available",this,m -> m.sampledAt==0 ? 0 : 1);
        registry.gauge(p+"snapshot_age_seconds",this,m -> m.sampledAt==0 ? Double.NaN : (System.currentTimeMillis()-m.sampledAt)/1000d);
    }
    @Scheduled(fixedDelay=30000,initialDelay=30000)
    public void refresh() {
        if(registry==null) return;
        try { snapshot=repository.operationalSnapshot(); sampledAt=System.currentTimeMillis(); }
        catch(RuntimeException unavailable) { count("snapshot.errors",1); }
    }
    public void expired(int count) { count("claims.expired",Math.max(0,count)); }
    public void fenced() { count("completion.fenced",1); }
    public void unknownTransport() { count("transport.unknown",1); }
    public void error() { count("dispatch.errors",1); }
    private void count(String name,int amount) {
        if(registry!=null && amount>0) registry.counter("otziv.performers.notifications."+name).increment(amount);
    }
}
