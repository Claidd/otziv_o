package com.hunt.otziv.s3.backup.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Exposes {@code otziv_backup_last_verified_epoch{database}} for the stale-backup alert. The value is
 * the time of the latest verified receipt in the evidence file, or 0 when none can be read, so a
 * missing, unreadable or never-written receipt alerts instead of looking healthy.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "backup.enabled", havingValue = "true")
public class BackupEvidenceMetrics implements MeterBinder {

    static final String METRIC = "otziv.backup.last.verified.epoch";
    static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final DatabaseBackupService backupService;
    private final Clock clock;
    private final Map<String, Reading> readings = new ConcurrentHashMap<>();

    @Autowired
    public BackupEvidenceMetrics(DatabaseBackupService backupService) {
        this(backupService, Clock.systemUTC());
    }

    BackupEvidenceMetrics(DatabaseBackupService backupService, Clock clock) {
        this.backupService = backupService;
        this.clock = clock;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        for (String database : backupService.evidenceDatabases()) {
            Gauge.builder(METRIC, this, metrics -> metrics.latestVerifiedEpoch(database))
                    .tag("database", database)
                    .description("Epoch seconds of the latest verified backup receipt; 0 when none is readable")
                    .register(registry);
        }
    }

    double latestVerifiedEpoch(String database) {
        Instant now = clock.instant();
        // Scrapes come every 15 s; the alert works in hours, so read each evidence file once per 5 minutes.
        Reading reading = readings.compute(database, (key, cached) ->
                cached != null && cached.readAt().plus(CACHE_TTL).isAfter(now) ? cached : read(key, now));
        return reading.epochSeconds();
    }

    private Reading read(String database, Instant now) {
        try {
            return new Reading(now, backupService.readTargetEvidenceSummary(database).latestVerifiedAt()
                    .map(verifiedAt -> verifiedAt.toEpochMilli() / 1000.0)
                    .orElse(0.0));
        } catch (Exception exception) {
            log.warn("Backup evidence is unreadable for the freshness metric: database={}, error={}",
                    database, exception.getClass().getSimpleName());
            return new Reading(now, 0.0);
        }
    }

    private record Reading(Instant readAt, double epochSeconds) {
    }
}
