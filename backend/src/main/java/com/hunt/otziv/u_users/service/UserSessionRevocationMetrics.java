package com.hunt.otziv.u_users.service;

import com.hunt.otziv.u_users.repository.AuthSessionStateRepository;
import com.hunt.otziv.u_users.repository.AuthSessionStateRepository.OperationalSnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Samples only active mutation phases; immutable historical session bindings are not scanned. */
@Component
public class UserSessionRevocationMetrics {
    private final AuthSessionStateRepository state;
    private final MeterRegistry registry;
    private volatile OperationalSnapshot snapshot=new OperationalSnapshot(0,0,0,0,0,0);
    private volatile long sampledAt;
    public UserSessionRevocationMetrics(AuthSessionStateRepository state,ObjectProvider<MeterRegistry> provider) {
        this.state=state; this.registry=provider.getIfAvailable();
        if(registry==null) return;
        String p="otziv.security.session_revocations.";
        registry.gauge(p+"pending",this,m -> m.snapshot.pending());
        registry.gauge(p+"unknown",this,m -> m.snapshot.unknown());
        registry.gauge(p+"password_in_flight",this,m -> m.snapshot.passwordInFlight());
        registry.gauge(p+"dispatchable",this,m -> m.snapshot.dispatchable());
        registry.gauge(p+"oldest_pending_seconds",this,m -> m.snapshot.oldestPendingSeconds());
        registry.gauge(p+"oldest_unknown_seconds",this,m -> m.snapshot.oldestUnknownSeconds());
        registry.gauge(p+"snapshot_available",this,m -> m.sampledAt==0 ? 0 : 1);
        registry.gauge(p+"snapshot_age_seconds",this,m -> m.sampledAt==0 ? Double.NaN : (System.currentTimeMillis()-m.sampledAt)/1000d);
    }
    @Scheduled(fixedDelay=30000,initialDelay=30000)
    public void refresh() {
        if(registry==null) return;
        try { snapshot=state.operationalSnapshot(); sampledAt=System.currentTimeMillis(); }
        catch(RuntimeException failure) { count("snapshot.errors"); }
    }
    public void error() { count("dispatch.errors"); }
    public void reconciled() { count("dispatch.reconciled_attempts"); }
    private void count(String name) { if(registry!=null) registry.counter("otziv.security.session_revocations."+name).increment(); }
}
