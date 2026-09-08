package com.hunt.otziv.monitoring;

import java.time.Instant;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** Fixed-memory ten-minute request histogram. No URI, actor, payload or unbounded labels. */
@Component
@ConditionalOnProperty(name = "otziv.monitoring.enabled", havingValue = "true")
public class RuntimeRequestWindow {
    private static final long[] BOUNDS_MS = {1,5,10,25,50,100,250,500,1000,2500,5000,15000,30000,60000,120000};
    private final Bucket[] buckets = new Bucket[60];
    private final LongSupplier now;
    public RuntimeRequestWindow() { this(System::currentTimeMillis); }
    RuntimeRequestWindow(LongSupplier now) { this.now = now; }
    public synchronized void record(long durationNanos, int status) {
        long tick = now.getAsLong() / 10_000;
        int slot = (int) (tick % buckets.length);
        if (buckets[slot] == null || buckets[slot].tick != tick) buckets[slot] = new Bucket(tick);
        Bucket bucket = buckets[slot]; double millis = Math.max(0, durationNanos / 1_000_000d);
        int index = 0; while (index < BOUNDS_MS.length && millis > BOUNDS_MS[index]) index++;
        bucket.histogram[index]++; bucket.count++; if (status >= 500) bucket.errors++;
        bucket.maximum = Math.max(bucket.maximum, millis);
    }
    public synchronized Snapshot snapshot() {
        long current = now.getAsLong(), tick = current / 10_000, count = 0, errors = 0;
        long[] histogram = new long[BOUNDS_MS.length + 1]; double maximum = 0;
        for (Bucket bucket : buckets) if (bucket != null && bucket.tick <= tick && bucket.tick > tick - buckets.length) {
            count += bucket.count; errors += bucket.errors; maximum = Math.max(maximum, bucket.maximum);
            for (int i = 0; i < histogram.length; i++) histogram[i] += bucket.histogram[i];
        }
        if (count == 0) return new Snapshot("NO_TRAFFIC", Instant.ofEpochMilli(current), 600, 0, null, null);
        long threshold = (long) Math.ceil(count * .95), cumulative = 0; double p95 = maximum;
        for (int i = 0; i < histogram.length; i++) { cumulative += histogram[i];
            if (cumulative >= threshold) { p95 = i < BOUNDS_MS.length ? BOUNDS_MS[i] : maximum; break; } }
        return new Snapshot("AVAILABLE", Instant.ofEpochMilli(current), 600, count, errors / (double) count, p95);
    }
    public record Snapshot(String state, Instant observedAt, int windowSeconds, long samples, Double errorRate, Double latencyP95Ms) {}
    private static final class Bucket {
        final long tick; final long[] histogram = new long[BOUNDS_MS.length + 1]; long count, errors; double maximum;
        Bucket(long tick) { this.tick = tick; }
    }
}
