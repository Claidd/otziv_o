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
        RequestWork(PerformanceMetrics owner) { this.owner = owner; }
    }

    /** A scope covers authentication, business processing and response serialization. */
    void beginRequest() { CURRENT.set(new RequestWork(this)); }

    void finishRequest(String endpoint, int status, long durationNanos) {
        RequestWork work = CURRENT.get();
        CURRENT.remove();
        String statusClass = (status / 100) + "xx";
        Timer.builder("otziv.http.duration").tag("endpoint", endpoint).tag("status", statusClass)
                .serviceLevelObjectives(LATENCY_BUCKETS).publishPercentileHistogram()
                .register(meterRegistry).record(durationNanos, TimeUnit.NANOSECONDS);
        if (work != null) {
            var observation = observationRegistry.getCurrentObservation();
            if (observation != null) observation.highCardinalityKeyValue("db.executions", Long.toString(work.queries))
                    .highCardinalityKeyValue("db.duration.ms", Long.toString(work.sqlNanos / 1_000_000));
            DistributionSummary.builder("otziv.http.sql.executions").tag("endpoint", endpoint)
                    .tag("status", statusClass)
                    .register(meterRegistry).record(work.queries);
            Timer.builder("otziv.http.sql.duration").tag("endpoint", endpoint)
                    .tag("status", statusClass)
                    .serviceLevelObjectives(LATENCY_BUCKETS).register(meterRegistry)
                    .record(work.sqlNanos, TimeUnit.NANOSECONDS);
        }
    }

    static boolean collectingSql() { return CURRENT.get() != null; }
    static void recordSql(long elapsedNanos) {
        RequestWork work = CURRENT.get();
        if (work != null) { work.queries++; work.sqlNanos += elapsedNanos; }
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
        String result = "success";

        try {
            return supplier.get();
        } catch (RuntimeException | Error exception) {
            result = "error";
            throw exception;
        } finally {
            sample.stop(timer(endpoint, result));
        }
    }

    public <T> T recordSegment(String component, String segment, Supplier<T> supplier) {
        Timer.Sample sample = Timer.start(meterRegistry);
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
