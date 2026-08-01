package com.hunt.otziv.integration.outbox;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Bounded runtime settings for the generic transactional outbox relay.
 * Enqueueing remains available while the relay is disabled so integrations can
 * be introduced with dual-write/shadow rollout before side effects are enabled.
 */
@Validated
@ConfigurationProperties(prefix = "otziv.integration.outbox")
public class IntegrationOutboxProperties {

    private static final Duration MIN_LEASE = Duration.ofSeconds(5);
    private static final Duration MAX_LEASE = Duration.ofHours(1);
    private static final Duration MIN_BACKOFF = Duration.ofMillis(100);
    private static final Duration MAX_BACKOFF_LIMIT = Duration.ofHours(24);

    private boolean relayEnabled;

    @Min(1)
    @Max(100)
    private int batchSize = 25;

    private Duration leaseDuration = Duration.ofMinutes(2);

    @Min(1)
    @Max(100)
    private int defaultMaxAttempts = 20;

    private Duration baseBackoff = Duration.ofSeconds(5);

    private Duration maxBackoff = Duration.ofMinutes(30);

    @DecimalMin("0.0")
    @DecimalMax("0.5")
    private double jitterRatio = 0.20d;

    @Min(1024)
    @Max(1_048_576)
    private int maxPayloadBytes = 65_536;

    @Min(100)
    @Max(100_000)
    private int statusCountCap = 10_000;

    @Valid
    private final Scheduler scheduler = new Scheduler();

    public boolean isRelayEnabled() {
        return relayEnabled;
    }

    public void setRelayEnabled(boolean relayEnabled) {
        this.relayEnabled = relayEnabled;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public Duration getLeaseDuration() {
        return leaseDuration;
    }

    public void setLeaseDuration(Duration leaseDuration) {
        this.leaseDuration = leaseDuration;
    }

    public int getDefaultMaxAttempts() {
        return defaultMaxAttempts;
    }

    public void setDefaultMaxAttempts(int defaultMaxAttempts) {
        this.defaultMaxAttempts = defaultMaxAttempts;
    }

    public Duration getBaseBackoff() {
        return baseBackoff;
    }

    public void setBaseBackoff(Duration baseBackoff) {
        this.baseBackoff = baseBackoff;
    }

    public Duration getMaxBackoff() {
        return maxBackoff;
    }

    public void setMaxBackoff(Duration maxBackoff) {
        this.maxBackoff = maxBackoff;
    }

    public double getJitterRatio() {
        return jitterRatio;
    }

    public void setJitterRatio(double jitterRatio) {
        this.jitterRatio = jitterRatio;
    }

    public int getMaxPayloadBytes() {
        return maxPayloadBytes;
    }

    public void setMaxPayloadBytes(int maxPayloadBytes) {
        this.maxPayloadBytes = maxPayloadBytes;
    }

    public int getStatusCountCap() {
        return statusCountCap;
    }

    public void setStatusCountCap(int statusCountCap) {
        this.statusCountCap = statusCountCap;
    }

    public Scheduler getScheduler() {
        return scheduler;
    }

    @AssertTrue(message = "outbox lease-duration must be between PT5S and PT1H")
    public boolean isLeaseDurationBounded() {
        return within(leaseDuration, MIN_LEASE, MAX_LEASE);
    }

    @AssertTrue(message = "outbox backoff durations are invalid or unbounded")
    public boolean isBackoffConfigurationBounded() {
        return within(baseBackoff, MIN_BACKOFF, MAX_BACKOFF_LIMIT)
                && within(maxBackoff, MIN_BACKOFF, MAX_BACKOFF_LIMIT)
                && !maxBackoff.minus(baseBackoff).isNegative();
    }

    private boolean within(Duration value, Duration minimum, Duration maximum) {
        return value != null
                && !value.isNegative()
                && value.compareTo(minimum) >= 0
                && value.compareTo(maximum) <= 0;
    }

    public static class Scheduler {

        @Min(100)
        @Max(3_600_000)
        private long fixedDelayMs = 5_000;

        @Min(0)
        @Max(86_400_000)
        private long initialDelayMs = 30_000;

        public long getFixedDelayMs() {
            return fixedDelayMs;
        }

        public void setFixedDelayMs(long fixedDelayMs) {
            this.fixedDelayMs = fixedDelayMs;
        }

        public long getInitialDelayMs() {
            return initialDelayMs;
        }

        public void setInitialDelayMs(long initialDelayMs) {
            this.initialDelayMs = initialDelayMs;
        }
    }
}
