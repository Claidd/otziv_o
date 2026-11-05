package com.hunt.otziv.s3.backup.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hunt.otziv.s3.backup.config.BackupProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class RestoreDrillMetricsTest {

    // 2027-01-20 02:00 in Irkutsk is still 2027-01-19 in UTC: the age is counted in the business zone.
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2027-01-19T18:00:00Z"), ZoneOffset.UTC);

    @Test
    void countsDaysSinceTheDrillInIrkutsk() {
        BackupProperties properties = new BackupProperties();
        properties.setRestoreDrillDate("2026-11-05");
        RestoreDrillMetrics metrics = new RestoreDrillMetrics(properties, CLOCK);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        metrics.bindTo(registry);

        assertThat(registry.get(RestoreDrillMetrics.METRIC).gauge().value()).isEqualTo(76.0);
    }

    @Test
    void missingOrInvalidDateIsAbsentNotFresh() {
        BackupProperties properties = new BackupProperties();
        RestoreDrillMetrics metrics = new RestoreDrillMetrics(properties, CLOCK);
        assertThat(metrics.ageDays()).isNaN();
        properties.setRestoreDrillDate("05.11.2026");
        assertThat(metrics.ageDays()).isNaN();
    }
}
