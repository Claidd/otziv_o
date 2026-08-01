package com.hunt.otziv.integration.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class IntegrationOutboxBackoffPolicyTest {

    @Test
    void appliesExponentialBackoffAndBoundedJitter() {
        IntegrationOutboxProperties properties = new IntegrationOutboxProperties();
        properties.setBaseBackoff(Duration.ofSeconds(1));
        properties.setMaxBackoff(Duration.ofSeconds(10));
        properties.setJitterRatio(0.20d);

        assertThat(new IntegrationOutboxBackoffPolicy(properties, () -> 0.0d)
                .delayForAttempt(1)).isEqualTo(Duration.ofMillis(800));
        assertThat(new IntegrationOutboxBackoffPolicy(properties, () -> 1.0d)
                .delayForAttempt(1)).isEqualTo(Duration.ofMillis(1_200));
        assertThat(new IntegrationOutboxBackoffPolicy(properties, () -> 1.0d)
                .delayForAttempt(100)).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void propertyDurationsFailTheirBoundedContracts() {
        IntegrationOutboxProperties properties = new IntegrationOutboxProperties();
        assertThat(properties.isLeaseDurationBounded()).isTrue();
        assertThat(properties.isBackoffConfigurationBounded()).isTrue();

        properties.setLeaseDuration(Duration.ofDays(1));
        properties.setBaseBackoff(Duration.ofMinutes(5));
        properties.setMaxBackoff(Duration.ofMinutes(1));
        assertThat(properties.isLeaseDurationBounded()).isFalse();
        assertThat(properties.isBackoffConfigurationBounded()).isFalse();
    }
}
