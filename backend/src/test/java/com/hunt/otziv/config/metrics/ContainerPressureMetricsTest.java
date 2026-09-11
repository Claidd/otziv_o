package com.hunt.otziv.config.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerPressureMetricsTest {
    @TempDir Path cgroup;

    @Test
    void readsUpdatedCountersAfterGarbageCollection() throws Exception {
        writeCounters(10, 2, 500_000);
        var metrics = new ContainerPressureMetrics(cgroup);
        try (var registry = new SimpleMeterRegistry()) {
            metrics.bindTo(registry);
            assertEquals(10, registry.get("otziv.container.cpu.periods").functionCounter().count());

            // A Spring singleton remains alive while Micrometer holds weak references
            // to the measured objects. Exercise a collection between actual reads.
            var sentinel = new WeakReference<>(new Object());
            for (int attempt = 0; attempt < 20 && sentinel.get() != null; attempt++) {
                System.gc();
                Thread.sleep(10);
            }
            assertNull(sentinel.get(), "Test requires a completed garbage collection");
            writeCounters(20, 5, 1_250_000);
            assertEquals(20, registry.get("otziv.container.cpu.periods").functionCounter().count());
            assertEquals(5, registry.get("otziv.container.cpu.throttled.periods").functionCounter().count());
            assertEquals(1.25, registry.get("otziv.container.cpu.throttled.seconds").functionCounter().count());
            for (String resource : new String[]{"cpu", "memory", "io"}) {
                assertEquals(1.25, registry.get("otziv.container.pressure.seconds")
                        .tag("resource", resource).functionCounter().count());
            }
            Reference.reachabilityFence(metrics);
        }
    }

    @Test
    void unsupportedHostRegistersNoCounters() {
        try (var registry = new SimpleMeterRegistry()) {
            new ContainerPressureMetrics(cgroup).bindTo(registry);
            assertTrue(registry.getMeters().isEmpty());
        }
    }

    @Test
    void missingOrInvalidCounterIsUnavailable() throws Exception {
        Path stat = cgroup.resolve("cpu.stat");
        assertTrue(Double.isNaN(ContainerPressureMetrics.counter(stat, "nr_periods", 1)));
        Files.writeString(stat, "nr_periods invalid\n");
        assertTrue(Double.isNaN(ContainerPressureMetrics.counter(stat, "nr_periods", 1)));
        assertTrue(Double.isNaN(ContainerPressureMetrics.counter(stat, "nr_throttled", 1)));
        Files.writeString(stat, "some avg10=0.0 total=invalid\n");
        assertTrue(Double.isNaN(ContainerPressureMetrics.pressure(stat)));
    }

    private void writeCounters(long periods, long throttled, long micros) throws Exception {
        Files.writeString(cgroup.resolve("cpu.stat"), "nr_periods " + periods
                + "\nnr_throttled " + throttled + "\nthrottled_usec " + micros + "\n");
        for (String resource : new String[]{"cpu", "memory", "io"}) {
            Files.writeString(cgroup.resolve(resource + ".pressure"),
                    "some avg10=0.0 avg60=0.0 avg300=0.0 total=" + micros + "\n");
        }
    }
}
