package com.hunt.otziv.config.metrics;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

/** Unprivileged cgroup v2 counters. Unsupported hosts expose no fabricated zero series. */
@Component
public class ContainerPressureMetrics implements MeterBinder {
    @Override public void bindTo(MeterRegistry registry) {
        Path cpu = Path.of("/sys/fs/cgroup/cpu.stat");
        if (Files.isReadable(cpu)) {
            FunctionCounter.builder("otziv.container.cpu.periods",cpu,p -> counter(p,"nr_periods",1)).register(registry);
            FunctionCounter.builder("otziv.container.cpu.throttled.periods",cpu,p -> counter(p,"nr_throttled",1)).register(registry);
            FunctionCounter.builder("otziv.container.cpu.throttled.seconds",cpu,p -> counter(p,"throttled_usec",1e6)).register(registry);
        }
        for (String resource : new String[]{"cpu","memory","io"}) {
            Path path=Path.of("/sys/fs/cgroup/"+resource+".pressure");
            if (Files.isReadable(path)) FunctionCounter.builder("otziv.container.pressure.seconds",path,ContainerPressureMetrics::pressure)
                    .tag("resource",resource).register(registry);
        }
    }
    static double counter(Path path,String key,double divisor) {
        try { return Files.readAllLines(path).stream().filter(line -> line.startsWith(key+" "))
                .mapToDouble(line -> Double.parseDouble(line.split("\\s+")[1])/divisor).findFirst().orElse(Double.NaN); }
        catch (java.io.IOException | RuntimeException unavailable) { return Double.NaN; }
    }
    static double pressure(Path path) {
        try { return Files.readAllLines(path).stream().filter(line -> line.startsWith("some "))
                .mapToDouble(line -> Double.parseDouble(line.substring(line.indexOf("total=")+6))/1e6).findFirst().orElse(Double.NaN); }
        catch (java.io.IOException | RuntimeException unavailable) { return Double.NaN; }
    }
}
