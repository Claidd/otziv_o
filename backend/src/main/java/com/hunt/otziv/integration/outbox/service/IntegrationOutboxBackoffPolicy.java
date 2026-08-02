package com.hunt.otziv.integration.outbox.service;

import com.hunt.otziv.integration.outbox.config.IntegrationOutboxProperties;
import com.hunt.otziv.integration.outbox.dto.IntegrationOutboxEvent;
import com.hunt.otziv.integration.outbox.dto.IntegrationOutboxEventDraft;
import com.hunt.otziv.integration.outbox.dto.IntegrationOutboxStatusResponse;
import com.hunt.otziv.integration.outbox.repository.IntegrationOutboxRepository;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Exponential retry delay with symmetric bounded jitter. */
@Component
class IntegrationOutboxBackoffPolicy {

    private final IntegrationOutboxProperties properties;
    private final DoubleSupplier randomUnit;

    @Autowired
    IntegrationOutboxBackoffPolicy(IntegrationOutboxProperties properties) {
        this(properties, () -> ThreadLocalRandom.current().nextDouble());
    }

    IntegrationOutboxBackoffPolicy(
            IntegrationOutboxProperties properties,
            DoubleSupplier randomUnit
    ) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.randomUnit = Objects.requireNonNull(randomUnit, "randomUnit");
    }

    Duration delayForAttempt(int completedAttemptCount) {
        int exponent = Math.max(0, Math.min(30, completedAttemptCount - 1));
        long baseMillis = properties.getBaseBackoff().toMillis();
        long maximumMillis = properties.getMaxBackoff().toMillis();

        long exponential;
        try {
            exponential = Math.multiplyExact(baseMillis, 1L << exponent);
        } catch (ArithmeticException exception) {
            exponential = maximumMillis;
        }
        long cappedMillis = Math.min(maximumMillis, exponential);

        double unit = Math.max(0.0d, Math.min(1.0d, randomUnit.getAsDouble()));
        double signedJitter = ((unit * 2.0d) - 1.0d) * properties.getJitterRatio();
        long jitteredMillis = Math.round(cappedMillis * (1.0d + signedJitter));
        return Duration.ofMillis(Math.max(1L, Math.min(maximumMillis, jitteredMillis)));
    }
}
