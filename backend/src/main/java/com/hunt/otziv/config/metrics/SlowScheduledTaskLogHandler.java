package com.hunt.otziv.config.metrics;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Logs @Scheduled runs slower than the threshold (Spring's "tasks.scheduled.execution" observation):
 * task name, duration and outcome. Some jobs are routinely long (AI diagnosis, reconciliations), so a
 * task is logged at most once per quiet period; the count of slow runs in between is reported with it.
 */
@Slf4j
@Component
public class SlowScheduledTaskLogHandler implements ObservationHandler<Observation.Context> {

    static final String SCHEDULED_TASK_OBSERVATION = "tasks.scheduled.execution";

    private final long thresholdNanos;
    private final long quietNanos;
    private final LongSupplier clock;
    private final ConcurrentMap<String, Window> windows = new ConcurrentHashMap<>();

    private record Started(long nanos) {
    }

    private static final class Window {
        long nextLogAt;
        long suppressed;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SlowScheduledTaskLogHandler(
            @Value("${otziv.slow-log.task-threshold-ms:30000}") long thresholdMs,
            @Value("${otziv.slow-log.task-quiet-minutes:15}") long quietMinutes) {
        this(thresholdMs, quietMinutes, System::nanoTime);
    }

    SlowScheduledTaskLogHandler(long thresholdMs, long quietMinutes, LongSupplier clock) {
        this.thresholdNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(1, thresholdMs));
        this.quietNanos = TimeUnit.MINUTES.toNanos(Math.max(0, quietMinutes));
        this.clock = clock;
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context != null && SCHEDULED_TASK_OBSERVATION.equals(context.getName());
    }

    @Override
    public void onStart(Observation.Context context) {
        context.put(Started.class, new Started(clock.getAsLong()));
    }

    @Override
    public void onStop(Observation.Context context) {
        Started started = context.get(Started.class);
        if (started == null) return;
        long now = clock.getAsLong();
        long elapsed = now - started.nanos();
        if (elapsed < thresholdNanos) return;
        String task = taskName(context);
        long suppressedBefore;
        Window window = windows.computeIfAbsent(task, ignored -> new Window());
        synchronized (window) {
            if (window.nextLogAt != 0 && now < window.nextLogAt) {
                window.suppressed++;
                return;
            }
            suppressedBefore = window.suppressed;
            window.suppressed = 0;
            window.nextLogAt = now + quietNanos;
        }
        log.warn("Slow scheduled task task={} durationMs={} outcome={} slowRunsSinceLastLog={}",
                task, TimeUnit.NANOSECONDS.toMillis(elapsed), value(context, "outcome"), suppressedBefore);
    }

    /** "scheduledClientMessageService.tick" from the observation's code namespace and function. */
    static String taskName(Observation.Context context) {
        String namespace = value(context, "code.namespace");
        String function = value(context, "code.function");
        if (!namespace.isBlank()) {
            int dot = namespace.lastIndexOf('.');
            namespace = dot >= 0 ? namespace.substring(dot + 1) : namespace;
            int proxy = namespace.indexOf("$$");
            namespace = proxy > 0 ? namespace.substring(0, proxy) : namespace;
        }
        String name = namespace.isBlank() ? function : namespace + (function.isBlank() ? "" : "." + function);
        if (!name.isBlank()) return name;
        String contextual = context.getContextualName();
        return contextual == null || contextual.isBlank() ? "unknown" : contextual;
    }

    private static String value(Observation.Context context, String key) {
        KeyValue keyValue = context.getLowCardinalityKeyValue(key);
        return keyValue == null || keyValue.getValue() == null ? "" : keyValue.getValue();
    }
}
