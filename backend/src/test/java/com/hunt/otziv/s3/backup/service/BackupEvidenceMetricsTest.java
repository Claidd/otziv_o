package com.hunt.otziv.s3.backup.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BackupEvidenceMetricsTest {

    private final DatabaseBackupService backupService = mock(DatabaseBackupService.class);
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-05T00:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };

    @Test
    void exposesTheLatestVerifiedReceiptPerBackedUpDatabase() throws Exception {
        when(backupService.evidenceDatabases()).thenReturn(List.of("mysql", "keycloak"));
        Instant mysqlVerified = Instant.parse("2026-11-04T23:10:00Z");
        when(backupService.readTargetEvidenceSummary("mysql"))
                .thenReturn(new DatabaseBackupService.BackupEvidenceSummary(Optional.of(mysqlVerified), Set.of()));
        when(backupService.readTargetEvidenceSummary("keycloak"))
                .thenReturn(new DatabaseBackupService.BackupEvidenceSummary(Optional.empty(), Set.of()));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new BackupEvidenceMetrics(backupService, clock).bindTo(registry);

        assertThat(gauge(registry, "mysql")).isEqualTo(mysqlVerified.getEpochSecond());
        // No receipt yet: 0 makes the freshness alert fire instead of reporting nothing.
        assertThat(gauge(registry, "keycloak")).isZero();
    }

    @Test
    void unreadableEvidenceReportsZeroAndReadsAreCached() throws Exception {
        when(backupService.evidenceDatabases()).thenReturn(List.of("mysql"));
        when(backupService.readTargetEvidenceSummary("mysql")).thenThrow(new IOException("unreadable"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new BackupEvidenceMetrics(backupService, clock).bindTo(registry);

        assertThat(gauge(registry, "mysql")).isZero();
        now.set(now.get().plusSeconds(60));
        assertThat(gauge(registry, "mysql")).isZero();
        verify(backupService, times(1)).readTargetEvidenceSummary("mysql");

        Instant verified = Instant.parse("2026-11-05T00:03:00Z");
        org.mockito.Mockito.reset(backupService);
        when(backupService.readTargetEvidenceSummary("mysql"))
                .thenReturn(new DatabaseBackupService.BackupEvidenceSummary(Optional.of(verified), Set.of()));
        now.set(now.get().plus(BackupEvidenceMetrics.CACHE_TTL));
        assertThat(gauge(registry, "mysql")).isEqualTo(verified.getEpochSecond());
    }

    private static double gauge(SimpleMeterRegistry registry, String database) {
        return registry.get(BackupEvidenceMetrics.METRIC).tag("database", database).gauge().value();
    }
}
