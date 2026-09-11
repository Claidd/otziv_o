package com.hunt.otziv.l_lead.service;

import com.hunt.otziv.l_lead.repository.LeadCommandRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Scrapes read cached values; bounded state labels never contain customer or command identities. */
@Component
public class LeadCommandMetrics {
    private final LeadCommandRepository repository;
    private final Map<String, AtomicLong> counts = new LinkedHashMap<>();
    private final Map<String, AtomicLong> ages = new LinkedHashMap<>();
    private final AtomicLong observedAt = new AtomicLong();
    private final AtomicLong dueCount = new AtomicLong();
    private final AtomicLong dueAge = new AtomicLong();

    public LeadCommandMetrics(LeadCommandRepository repository, MeterRegistry registry) {
        this.repository = repository;
        for (String state : List.of("READY","PROCESSING","SUCCEEDED","UNKNOWN","DEAD","QUARANTINED","LEGACY")) {
            var count = new AtomicLong(); var age = new AtomicLong();
            counts.put(state, count); ages.put(state, age);
            Gauge.builder("otziv.lead.commands", count, AtomicLong::get).tag("state",state).register(registry);
            Gauge.builder("otziv.lead.command.oldest.seconds", age, AtomicLong::get).tag("state",state).register(registry);
        }
        Gauge.builder("otziv.lead.commands.observed.timestamp.seconds", observedAt, AtomicLong::get).register(registry);
        Gauge.builder("otziv.lead.commands.due", dueCount, AtomicLong::get).register(registry);
        Gauge.builder("otziv.lead.commands.due.oldest.seconds", dueAge, AtomicLong::get).register(registry);
    }

    @Scheduled(fixedDelayString="${lead.commands.metrics-delay-ms:30000}")
    public void sample() {
        // On DB failure the timestamp remains stale; never publish a false empty queue.
        var snapshot = repository.health();
        var due = repository.dueHealth();
        counts.values().forEach(value -> value.set(0)); ages.values().forEach(value -> value.set(0));
        for (var row : snapshot) {
            if (counts.containsKey(row.state())) {
                counts.get(row.state()).set(row.count()); ages.get(row.state()).set(row.oldestSeconds());
            }
        }
        dueCount.set(due.count()); dueAge.set(due.oldestSeconds());
        observedAt.set(System.currentTimeMillis() / 1000);
    }
}
