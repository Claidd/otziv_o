package com.hunt.otziv.monitoring;

import com.hunt.otziv.integration.outbox.service.IntegrationOutboxStatusService;
import com.hunt.otziv.client_messages.api.DeliveryQueueHealth;
import com.hunt.otziv.config.settings.api.OutboundMessagePolicy;
import com.hunt.otziv.workload_shadow.health.service.WorkloadShadowHealthService;
import io.micrometer.core.instrument.MeterRegistry;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.annotation.Autowired;

/** Authenticated snapshots read cached projections only; no query is performed by HTTP scrapes. */
@Service
@ConditionalOnProperty(name = "otziv.monitoring.enabled", havingValue = "true")
public class MonitoringRuntimeService {
    private final MeterRegistry registry;
    private final Environment environment;
    private final RuntimeRequestWindow requests;
    private final IntegrationOutboxStatusService outbox;
    private final WorkloadShadowHealthService workload;
    private final List<DeliveryQueueHealth> deliveryQueues;
    private final OutboundMessagePolicy outboundPolicy;
    private static final List<String> DELIVERY_QUEUES = List.of("common_invoice", "manager_client", "whatsapp_reply");
    private volatile List<QueueSample> deliveryProjected = DELIVERY_QUEUES.stream().map(MonitoringRuntimeService::unavailable).toList();
    private volatile List<QueueSample> projected = List.of(unavailable("integration_outbox"), unavailable("workload"));
    private TransactionTemplate samplingTransaction;
    public MonitoringRuntimeService(MeterRegistry registry, Environment environment, RuntimeRequestWindow requests,
            IntegrationOutboxStatusService outbox, WorkloadShadowHealthService workload,
            List<DeliveryQueueHealth> deliveryQueues, OutboundMessagePolicy outboundPolicy) {
        this.registry=registry; this.environment=environment; this.requests=requests; this.outbox=outbox; this.workload=workload;
        this.deliveryQueues=List.copyOf(deliveryQueues); this.outboundPolicy=outboundPolicy;
    }
    @Autowired
    void configureSamplingTransaction(PlatformTransactionManager manager) {
        samplingTransaction=new TransactionTemplate(manager);samplingTransaction.setReadOnly(true);samplingTransaction.setTimeout(5);
    }
    private <T> T read(java.util.function.Supplier<T> supplier) {
        return samplingTransaction==null ? supplier.get() : samplingTransaction.execute(status->supplier.get());
    }
    @Scheduled(fixedDelay=30000, initialDelay=1000, scheduler="monitoringTaskScheduler")
    public void sampleProjections() {
        QueueSample integration, shadow;
        try {
            var value=read(outbox::snapshot);
            integration=value.diagnosticAvailable() ? new QueueSample("integration_outbox", "AVAILABLE", Instant.now(),
                    value.relayEnabled(), (double)(value.due().value()+value.processing().value()), (double)value.dead().value(),
                    null, value.oldestDueAgeSeconds()!=null ? value.oldestDueAgeSeconds().doubleValue()
                        : value.due().value()==0 && !value.due().capped() && value.oldestDueAt()==null && value.databaseTime()!=null ? 0d : null,
                    value.due().capped() || value.processing().capped() || value.dead().capped()) : unavailable("integration_outbox");
        } catch (RuntimeException failed) { integration=unavailable("integration_outbox"); }
        try {
            var value=read(workload::snapshot);
            shadow=new QueueSample("workload", "AVAILABLE", Instant.now(), value.groupNotificationsEnabled(),
                    (double)(value.dueEvents()+value.processingEvents()), (double)value.deadEvents(), null,
                    (double)value.oldestDueAgeSeconds(), false);
        } catch (RuntimeException failed) { shadow=unavailable("workload"); }
        projected=List.of(integration,shadow);
        var next = new ArrayList<QueueSample>();
        for (String name : DELIVERY_QUEUES) {
            try {
                var sources = deliveryQueues.stream().filter(q -> name.equals(q.queueName())).toList();
                if (sources.size() != 1) throw new IllegalStateException("Missing or duplicate queue owner");
                boolean dispatchEnabled = outboundPolicy.clientMessagesEnabled();
                var rows = read(sources.getFirst()::deliveryQueueHealth);
                var states = new HashSet<DeliveryQueueHealth.State>();
                double backlog = 0, dead = 0, unknown = 0, oldest = 0;
                for (var row : rows) {
                    if (!states.add(row.state()) || row.jobs() < 0 || row.oldestSeconds() < 0 || row.expiredLeases() < 0)
                        throw new IllegalStateException("Invalid delivery queue snapshot");
                    backlog += row.jobs();
                    switch (row.state()) {
                        case FAILED -> dead += row.jobs();
                        case UNKNOWN -> unknown += row.jobs();
                        default -> oldest = Math.max(oldest, row.oldestSeconds());
                    }
                }
                next.add(new QueueSample(name, "AVAILABLE", Instant.now(), dispatchEnabled, backlog, dead, unknown, oldest, false));
            } catch (RuntimeException failed) { next.add(unavailable(name)); }
        }
        deliveryProjected = List.copyOf(next);
    }
    public Snapshot snapshot() {
        Instant now=Instant.now(); var queues=new ArrayList<>(projected);
        queues.addAll(deliveryProjected);
        Double leadObserved=gauge("otziv.lead.commands.observed.timestamp.seconds");
        queues.add(queue("lead", leadObserved==null || leadObserved<=0 ? null : Instant.ofEpochMilli((long)(leadObserved*1000)),
                enabled("lead.commands.dispatch-enabled"), sumStates("READY","PROCESSING","UNKNOWN","QUARANTINED","LEGACY"),
                leadState("DEAD"), sumStates("UNKNOWN","QUARANTINED","LEGACY"), gauge("otziv.lead.commands.due.oldest.seconds")));
        String p="otziv.performers.notifications.";
        queues.add(queue("performer", meterObserved(p), enabled("performers.notifications.dispatch-enabled"),
                sum(p+"pending",p+"processing",p+"unknown",p+"blocked",p+"legacy_unknown"), gauge(p+"blocked"),
                sum(p+"unknown",p+"legacy_unknown"), gauge(p+"oldest_pending_seconds")));
        p="otziv.security.session_revocations.";
        queues.add(queue("session_revocation",meterObserved(p),enabled("otziv.security.session-revocation-dispatch-enabled"),
                gauge(p+"pending"),null,gauge(p+"unknown"),gauge(p+"oldest_pending_seconds")));
        var memory=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        Double heap=memory.getMax()>0 ? Math.min(1d,memory.getUsed()/(double)memory.getMax()) : null;
        Double cpu=gauge("process.cpu.usage"), pool=databasePoolSaturation();
        Double saturation=java.util.stream.Stream.of(heap,cpu,pool).filter(Objects::nonNull).max(Double::compare).orElse(null);
        return new Snapshot("otziv-runtime-observed-v1",now,requests.snapshot(),new Saturation(now,saturation,heap,cpu,pool),List.copyOf(queues));
    }
    private Double databasePoolSaturation() {
        Double result=null;
        for(var active:registry.find("hikaricp.connections.active").gauges()) {
            String pool=active.getId().getTag("pool");if(pool==null)continue;
            var maximum=registry.find("hikaricp.connections.max").tag("pool",pool).gauge();
            Double used=valid(active.value()),max=maximum==null?null:valid(maximum.value());
            if(used!=null && max!=null && max>0)result=Math.max(result==null?0:result,Math.min(1d,used/max));
        }
        return result;
    }
    private Instant meterObserved(String prefix) {
        Double present=gauge(prefix+"snapshot_available"),age=gauge(prefix+"snapshot_age_seconds");
        return present!=null && present==1 && age!=null ? Instant.now().minusMillis((long)(age*1000)) : null;
    }
    private boolean enabled(String name) { return environment.getProperty(name,Boolean.class,false); }
    private Double leadState(String state) { var gauge=registry.find("otziv.lead.commands").tag("state",state).gauge(); return valid(gauge==null ? null : gauge.value()); }
    private Double sumStates(String... states) { double sum=0; for(String state:states) { Double value=leadState(state); if(value==null)return null;sum+=value; }return sum; }
    private Double sum(String... names) { double sum=0;for(String name:names){Double value=gauge(name);if(value==null)return null;sum+=value;}return sum; }
    private Double gauge(String name) { var gauge=registry.find(name).gauge();return valid(gauge==null ? null : gauge.value()); }
    private Double valid(Double value) { return value!=null && Double.isFinite(value) && value>=0 ? value : null; }
    private static QueueSample queue(String name,Instant observed,boolean enabled,Double backlog,Double dead,Double unknown,Double due) {
        boolean countersPresent=unknown!=null && (name.equals("session_revocation") || dead!=null);
        return new QueueSample(name,observed!=null && backlog!=null && due!=null && countersPresent ? "AVAILABLE" : "UNAVAILABLE",observed,enabled,backlog,dead,unknown,due,false);
    }
    private static QueueSample unavailable(String name) {return new QueueSample(name,"UNAVAILABLE",null,null,null,null,null,null,false);}
    public record QueueSample(String name,String state,Instant observedAt,Boolean dispatchEnabled,Double backlog,Double dead,Double unknown,Double oldestDueSeconds,boolean countLowerBound) {}
    public record Saturation(Instant observedAt,Double ratio,Double heapRatio,Double cpuRatio,Double databasePoolRatio) {}
    public record Snapshot(String schema,Instant observedAt,RuntimeRequestWindow.Snapshot requests,Saturation saturation,List<QueueSample> queues) {}
}
