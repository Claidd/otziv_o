package com.hunt.otziv.s3.backup.service;

import com.hunt.otziv.s3.backup.config.BackupProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Exposes {@code otziv_backup_restore_drill_age_days}: days since the latest isolated restore drill
 * (BACKUP_RESTORE_DRILL_DATE). The deploy gate refuses drills older than 90 days; the alert warns two
 * weeks earlier. An unset or invalid date gives NaN, so the series is absent instead of looking fresh.
 */
@Component
@ConditionalOnProperty(name = "backup.enabled", havingValue = "true")
public class RestoreDrillMetrics implements MeterBinder {

    static final String METRIC = "otziv.backup.restore.drill.age.days";
    private static final ZoneId ZONE = ZoneId.of("Asia/Irkutsk");

    private final BackupProperties properties;
    private final Clock clock;

    @Autowired
    public RestoreDrillMetrics(BackupProperties properties) {
        this(properties, Clock.systemUTC());
    }

    RestoreDrillMetrics(BackupProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder(METRIC, this, RestoreDrillMetrics::ageDays)
                .description("Days since the latest isolated restore drill; NaN when the date is not configured")
                .register(registry);
    }

    double ageDays() {
        String value = properties.getRestoreDrillDate();
        if (value == null || value.isBlank()) {
            return Double.NaN;
        }
        try {
            LocalDate drill = LocalDate.parse(value.trim());
            return Math.max(0, ChronoUnit.DAYS.between(drill, LocalDate.now(clock.withZone(ZONE))));
        } catch (DateTimeParseException invalid) {
            return Double.NaN;
        }
    }
}
