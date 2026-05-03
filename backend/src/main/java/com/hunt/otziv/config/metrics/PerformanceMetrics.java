package com.hunt.otziv.config.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
public class PerformanceMetrics {

    private static final String TIMER_NAME = "otziv.endpoint.duration";

    private final MeterRegistry meterRegistry;

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

    private Timer timer(String endpoint, String result) {
        return Timer.builder(TIMER_NAME)
                .description("Business endpoint processing duration")
                .tag("endpoint", endpoint)
                .tag("result", result)
                .publishPercentileHistogram()
                .register(meterRegistry);
    }
}
