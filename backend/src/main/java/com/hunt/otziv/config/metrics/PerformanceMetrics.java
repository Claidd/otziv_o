package com.hunt.otziv.config.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;
import java.time.Duration;
import io.micrometer.core.instrument.DistributionSummary;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class PerformanceMetrics {

    static final Duration[] LATENCY_BUCKETS = {Duration.ofMillis(20), Duration.ofMillis(50),
            Duration.ofMillis(80), Duration.ofMillis(100), Duration.ofMillis(200),
            Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofSeconds(10)};
    private static final ThreadLocal<RequestWork> CURRENT = new ThreadLocal<>();

    static final class RequestWork {
        final PerformanceMetrics owner;
        long queries;
        long sqlNanos;
        long connectionNanos;
        long fetchNanos;
        long rows;
        long started = System.nanoTime();
        long controllerStarted;
        long controllerFinished;
        final java.util.Map<String, Long> segments = new java.util.LinkedHashMap<>();
        io.micrometer.observation.Observation observation;
        io.micrometer.observation.Observation.Scope observationScope;
        RequestWork(PerformanceMetrics owner) { this.owner = owner; }
    }

    /** A scope covers authentication, business processing and response serialization. */
    void beginRequest() { CURRENT.set(new RequestWork(this)); }

    void beginRequest(String endpoint) {
        beginRequest();
        RequestWork work = CURRENT.get();
        work.observation = io.micrometer.observation.Observation.createNotStarted("otziv.interactive.http", observationRegistry)
                .lowCardinalityKeyValue("endpoint", endpoint).start();
        work.observationScope = work.observation.openScope();
    }

    private final java.util.concurrent.ConcurrentMap<String, java.util.concurrent.atomic.AtomicLong> lastRequests = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong nextSlowLog = new java.util.concurrent.atomic.AtomicLong();
    private final Long runtimeEpoch = java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime() / 1000;
    private final String runtime = runtimeEpoch.toString();

    @jakarta.annotation.PostConstruct
    void initializeInteractiveMeters() {
        meterRegistry.gauge("otziv.http.process.started.epoch", java.util.List.of(io.micrometer.core.instrument.Tag.of("runtime", runtime)), runtimeEpoch);
        // Fixed, bounded dimensions establish zero series before ordinary traffic.
        // A cumulative process panel still accounts for calls before the first scrape.
        for (String endpoint : InteractiveRequestMetricsFilter.ENDPOINTS) {
            for (String status : java.util.List.of("2xx", "3xx", "4xx", "5xx")) {
                httpTimer(endpoint, status);
            }
            var last = new java.util.concurrent.atomic.AtomicLong();
            lastRequests.put(endpoint, last);
            meterRegistry.gauge("otziv.http.last.request.epoch", java.util.List.of(io.micrometer.core.instrument.Tag.of("endpoint", endpoint), io.micrometer.core.instrument.Tag.of("runtime", runtime)), last);
        }
    }

    private Timer httpTimer(String endpoint, String status) {
        return Timer.builder("otziv.http.duration").tag("endpoint", endpoint).tag("status", status).tag("runtime", runtime)
                .serviceLevelObjectives(LATENCY_BUCKETS).publishPercentileHistogram().register(meterRegistry);
    }

    void finishRequest(String endpoint, int status, long durationNanos) {
        RequestWork work = CURRENT.get();
        CURRENT.remove();
        try {
            String statusClass = (status / 100) + "xx";
            httpTimer(endpoint, statusClass).record(durationNanos, TimeUnit.NANOSECONDS);
            var last = lastRequests.get(endpoint);
            if (last != null) last.set(java.time.Instant.now().getEpochSecond());
            if (work != null) {
                var observation = observationRegistry.getCurrentObservation();
                if (observation != null) observation.highCardinalityKeyValue("db.executions", Long.toString(work.queries))
                        .highCardinalityKeyValue("db.duration.ms", Long.toString(work.sqlNanos / 1_000_000));
                DistributionSummary.builder("otziv.http.sql.executions").tag("endpoint", endpoint)
                        .tag("status", statusClass).tag("runtime", runtime)
                        .register(meterRegistry).record(work.queries);
                Timer.builder("otziv.http.sql.duration").tag("endpoint", endpoint)
                        .tag("status", statusClass).tag("runtime", runtime)
                        .serviceLevelObjectives(LATENCY_BUCKETS).register(meterRegistry)
                        .record(work.sqlNanos, TimeUnit.NANOSECONDS);
                recordRequestPhase(endpoint, statusClass, "connection", work.connectionNanos);
                recordRequestPhase(endpoint, statusClass, "result-next", work.fetchNanos);
                if (work.controllerStarted != 0 && work.controllerFinished != 0) {
                    recordRequestPhase(endpoint, statusClass, "before-controller", work.controllerStarted - work.started);
                    recordRequestPhase(endpoint, statusClass, "controller", work.controllerFinished - work.controllerStarted);
                    recordRequestPhase(endpoint, statusClass, "after-controller", Math.max(0, durationNanos - (work.controllerFinished - work.started)));
                }
                if (work.observation != null) {
                    work.observation.lowCardinalityKeyValue("status", statusClass)
                            .highCardinalityKeyValue("db.executions", Long.toString(work.queries))
                            .highCardinalityKeyValue("db.execute.ms", Long.toString(work.sqlNanos / 1_000_000))
                            .highCardinalityKeyValue("db.connection.ms", Long.toString(work.connectionNanos / 1_000_000))
                            .highCardinalityKeyValue("db.rows", Long.toString(work.rows));
                }
                // At most one diagnostic event per second per process. Only fixed names
                // and durations are retained; inclusive nested segments must not be added.
                long now = System.nanoTime();
                long previous = nextSlowLog.get();
                if (durationNanos >= TimeUnit.MILLISECONDS.toNanos(100) && now >= previous
                        && nextSlowLog.compareAndSet(previous, now + TimeUnit.SECONDS.toNanos(1))) {
                    org.slf4j.LoggerFactory.getLogger(PerformanceMetrics.class).info(
                            "Interactive slow read endpoint={} status={} totalMs={} beforeControllerMs={} controllerMs={} sqlExecuteMs={} connectionMs={} resultNextMs={} sqlCount={} rows={} inclusiveSegmentsMs={}",
                            endpoint, statusClass, durationNanos / 1_000_000,
                            work.controllerStarted == 0 ? -1 : (work.controllerStarted - work.started) / 1_000_000,
                            work.controllerFinished == 0 ? -1 : (work.controllerFinished - work.controllerStarted) / 1_000_000,
                            work.sqlNanos / 1_000_000, work.connectionNanos / 1_000_000, work.fetchNanos / 1_000_000,
                            work.queries, work.rows, work.segments);
                }
            }
        } finally {
            if (work != null) {
                try { if (work.observationScope != null) work.observationScope.close(); }
                finally { if (work.observation != null) work.observation.stop(); }
            }
        }
    }

    private void recordRequestPhase(String endpoint, String status, String phase, long nanos) {
        Timer.builder("otziv.http.phase.duration").tags("endpoint", endpoint, "status", status, "phase", phase, "runtime", runtime)
                .register(meterRegistry).record(Math.max(0, nanos), TimeUnit.NANOSECONDS);
    }

    static boolean collectingSql() { return CURRENT.get() != null; }
    static void recordSql(long elapsedNanos) {
        RequestWork work = CURRENT.get();
        if (work != null) { work.queries++; work.sqlNanos += elapsedNanos; }
    }
    static void recordConnection(long nanos) {
        RequestWork work = CURRENT.get();
        if (work != null) work.connectionNanos += nanos;
    }
    static void recordFetch(long nanos, boolean row) {
        RequestWork work = CURRENT.get();
        if (work != null) { work.fetchNanos += nanos; if (row) work.rows++; }
    }

    /** Services remain usable in jobs/tests without an HTTP observation scope. */
    public static <T> T segment(String component, String segment, Supplier<T> supplier) {
        RequestWork work = CURRENT.get();
        return work == null ? supplier.get() : work.owner.recordSegment(component, segment, supplier);
    }

    private static final String TIMER_NAME = "otziv.endpoint.duration";
    private static final String SEGMENT_TIMER_NAME = "otziv.service.segment.duration";

    private final MeterRegistry meterRegistry;
    private io.micrometer.observation.ObservationRegistry observationRegistry = io.micrometer.observation.ObservationRegistry.NOOP;

    @org.springframework.beans.factory.annotation.Autowired
    void observationRegistry(io.micrometer.observation.ObservationRegistry registry) { observationRegistry = registry; }

    public <T> T recordEndpoint(String endpoint, Supplier<T> supplier) {
        Timer.Sample sample = Timer.start(meterRegistry);
        RequestWork work = CURRENT.get();
        boolean outer = work != null && work.controllerStarted == 0;
        if (outer) work.controllerStarted = System.nanoTime();
        String result = "success";

        try {
            return supplier.get();
        } catch (RuntimeException | Error exception) {
            result = "error";
            throw exception;
        } finally {
            if (outer) work.controllerFinished = System.nanoTime();
            sample.stop(timer(endpoint, result));
        }
    }

    public <T> T recordSegment(String component, String segment, Supplier<T> supplier) {
        Timer.Sample sample = Timer.start(meterRegistry);
        long started = System.nanoTime();
        String result = "success";
        var observation = io.micrometer.observation.Observation.createNotStarted("otziv.service.segment", observationRegistry)
                .lowCardinalityKeyValue("component", component).lowCardinalityKeyValue("segment", segment).start();

        try (var scope = observation.openScope()) {
            return supplier.get();
        } catch (RuntimeException | Error exception) {
            result = "error";
            observation.error(new SegmentFailure(exception));
            throw exception;
        } finally {
            RequestWork work = CURRENT.get();
            if (work != null && work.segments.size() < 32) work.segments.merge(component + "/" + segment,
                    (System.nanoTime() - started) / 1_000_000, Long::sum);
            observation.stop();
            sample.stop(segmentTimer(component, segment, result));
        }
    }

    /** SQL/provider exceptions can include parameter values; traces receive only the failure type. */
    private static final class SegmentFailure extends RuntimeException {
        private SegmentFailure(Throwable failure) { super(failure.getClass().getSimpleName(), null, false, false); }
    }

    private Timer timer(String endpoint, String result) {
        return Timer.builder(TIMER_NAME)
                .description("Business endpoint processing duration")
                .tag("endpoint", endpoint)
                .tag("result", result)
                .serviceLevelObjectives(LATENCY_BUCKETS)
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    private Timer segmentTimer(String component, String segment, String result) {
        return Timer.builder(SEGMENT_TIMER_NAME)
                .description("Business service segment processing duration")
                .tag("component", component)
                .tag("segment", segment)
                .tag("result", result)
                .serviceLevelObjectives(LATENCY_BUCKETS)
                .publishPercentileHistogram()
                .register(meterRegistry);
    }
}
