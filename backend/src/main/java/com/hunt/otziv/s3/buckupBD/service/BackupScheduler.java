package com.hunt.otziv.s3.buckupBD.service;

import com.hunt.otziv.s3.buckupBD.config.BackupProperties;
import com.hunt.otziv.scheduler.SchedulerLeaseService;
import com.hunt.otziv.scheduler.SchedulerLeaseService.Lease;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "backup.enabled", havingValue = "true")
public class BackupScheduler implements SchedulingConfigurer {

    private static final String BACKUP_LEASE = "database-backup.daily";
    private static final Duration BACKUP_LEASE_DURATION = Duration.ofHours(1);
    private static final Duration MAX_CATCH_UP_WINDOW = Duration.ofHours(36);
    private static final Duration MIN_CATCH_UP_WINDOW = Duration.ofHours(24);
    private static final Duration MAX_CATCH_UP_CHECK_INTERVAL = Duration.ofHours(1);
    private static final int MAX_CATCH_UP_OCCURRENCES = 128;
    private static final int SPRING_CRON_FIELD_COUNT = 6;

    private final DatabaseBackupService backupService;
    private final SchedulerLeaseService schedulerLeaseService;
    private final BackupProperties backupProperties;
    private CronExpression scheduleExpression;
    private ZoneId scheduleZone;

    public BackupScheduler(
            DatabaseBackupService backupService,
            SchedulerLeaseService schedulerLeaseService,
            BackupProperties backupProperties
    ) {
        this.backupService = backupService;
        this.schedulerLeaseService = schedulerLeaseService;
        this.backupProperties = backupProperties;
    }

    @PostConstruct
    void validateConfiguration() {
        BackupProperties.Schedule schedule = requireSchedule();
        BackupProperties.RunOnce runOnce = requireRunOnce();

        if (schedule.isEnabled()) {
            scheduleExpression = parseCron(schedule.getCron());
            scheduleZone = parseZone(schedule.getZone());
            if (!schedule.isCatchUpEnabled()) {
                throw new IllegalStateException(
                        "backup.schedule.catch-up-enabled must be true for recurring production backups"
                );
            }
        }
        if (schedule.isCatchUpEnabled()) {
            if (!schedule.isEnabled()) {
                throw new IllegalStateException("backup.schedule.catch-up-enabled requires backup.schedule.enabled=true");
            }
            Duration catchUpWindow = requirePositiveDuration(
                    schedule.getCatchUpWindow(),
                    "backup.schedule.catch-up-window",
                    MAX_CATCH_UP_WINDOW
            );
            if (catchUpWindow.compareTo(MIN_CATCH_UP_WINDOW) <= 0) {
                throw new IllegalStateException("backup.schedule.catch-up-window must be greater than PT24H");
            }
            requirePositiveDuration(
                    schedule.getCatchUpCheckInterval(),
                    "backup.schedule.catch-up-check-interval",
                    MAX_CATCH_UP_CHECK_INTERVAL
            );
            requireNonNegativeDuration(
                    schedule.getCatchUpInitialDelay(),
                    "backup.schedule.catch-up-initial-delay",
                    MAX_CATCH_UP_CHECK_INTERVAL
            );
        }
        if (runOnce.isEnabled()) {
            if (schedule.isEnabled()) {
                throw new IllegalStateException(
                        "backup.run-once.enabled requires backup.schedule.enabled=false"
                );
            }
            DatabaseBackupService.requireRunRequestId(runOnce.getRequestId());
        }
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        BackupProperties.Schedule schedule = requireSchedule();
        if (!schedule.isEnabled()) {
            return;
        }
        Trigger dailyTrigger = new CronTrigger(schedule.getCron().trim(), scheduleZone);
        taskRegistrar.addTriggerTask(this::daily, dailyTrigger);
        if (schedule.isCatchUpEnabled()) {
            taskRegistrar.addFixedDelayTask(new FixedDelayTask(
                    this::catchUp,
                    schedule.getCatchUpCheckInterval(),
                    schedule.getCatchUpInitialDelay()
            ));
        }
    }

    public void daily() {
        try {
            if (!runWithLease(backupService::runDailyBackup)) {
                log.info("Daily database backup skipped because another replica owns the scheduler lease");
            }
        } catch (Exception exception) {
            log.error("Daily database backup failed", exception);
        }
    }

    public void catchUp() {
        catchUpAt(Instant.now());
    }

    void catchUpAt(Instant now) {
        try {
            Optional<Instant> scheduledAt = mostRecentScheduledOccurrence(
                    scheduleExpression,
                    scheduleZone,
                    now,
                    requireSchedule().getCatchUpWindow()
            );
            if (scheduledAt.isEmpty() || !requiresCatchUp(scheduledAt.get(), now)) {
                return;
            }

            boolean acquired = runWithLease(() -> {
                if (requiresCatchUp(scheduledAt.get(), now)) {
                    backupService.runCatchUpBackup();
                }
            });
            if (!acquired) {
                log.info("Database backup catch-up deferred because another replica owns the scheduler lease");
            }
        } catch (Exception exception) {
            log.error("Database backup catch-up failed", exception);
        }
    }

    public void runOnce(String requestId) throws Exception {
        String normalizedRequestId = DatabaseBackupService.requireRunRequestId(requestId);
        if (requireSchedule().isEnabled()) {
            throw new IllegalStateException("A one-shot backup requires backup.schedule.enabled=false");
        }
        if (backupService.readEvidenceSummary().containsManualRequest(normalizedRequestId)) {
            log.info("Manual database backup request is already verified: requestId={}", normalizedRequestId);
            return;
        }

        boolean acquired = runWithLease(() -> {
            if (backupService.readEvidenceSummary().containsManualRequest(normalizedRequestId)) {
                log.info("Manual database backup request is already verified: requestId={}", normalizedRequestId);
                return;
            }
            backupService.runManualBackup(normalizedRequestId);
        });
        if (!acquired) {
            throw new IllegalStateException("Manual database backup could not acquire the scheduler lease");
        }
    }

    private boolean requiresCatchUp(Instant scheduledAt, Instant now) throws Exception {
        Optional<Instant> latest = backupService.readEvidenceSummary().latestVerifiedAt();
        if (latest.isPresent() && latest.get().isAfter(now.plus(Duration.ofMinutes(5)))) {
            log.error("Ignoring future-dated database backup evidence while deciding catch-up");
            latest = Optional.empty();
        }
        return latest.isEmpty() || latest.get().isBefore(scheduledAt);
    }

    private boolean runWithLease(BackupOperation operation) throws Exception {
        Optional<Lease> acquired = schedulerLeaseService.tryAcquire(BACKUP_LEASE, BACKUP_LEASE_DURATION);
        if (acquired.isEmpty()) {
            return false;
        }

        Exception operationFailure = null;
        try {
            operation.run();
            return true;
        } catch (Exception exception) {
            operationFailure = exception;
            throw exception;
        } finally {
            try {
                schedulerLeaseService.release(acquired.get());
            } catch (RuntimeException releaseFailure) {
                if (operationFailure != null) {
                    operationFailure.addSuppressed(releaseFailure);
                } else {
                    throw releaseFailure;
                }
            }
        }
    }

    static Optional<Instant> mostRecentScheduledOccurrence(
            CronExpression cron,
            ZoneId zone,
            Instant now,
            Duration catchUpWindow
    ) {
        ZonedDateTime cursor = now.minus(catchUpWindow).atZone(zone).minusNanos(1);
        Instant latest = null;
        for (int occurrences = 0; occurrences <= MAX_CATCH_UP_OCCURRENCES; occurrences++) {
            ZonedDateTime next = cron.next(cursor);
            if (next == null || next.toInstant().isAfter(now)) {
                return Optional.ofNullable(latest);
            }
            if (occurrences == MAX_CATCH_UP_OCCURRENCES) {
                throw new IllegalStateException("backup.schedule.cron is too frequent for bounded catch-up");
            }
            latest = next.toInstant();
            cursor = next;
        }
        throw new IllegalStateException("Unable to evaluate backup catch-up schedule safely");
    }

    private BackupProperties.Schedule requireSchedule() {
        if (backupProperties.getSchedule() == null) {
            throw new IllegalStateException("backup.schedule is required");
        }
        return backupProperties.getSchedule();
    }

    private BackupProperties.RunOnce requireRunOnce() {
        if (backupProperties.getRunOnce() == null) {
            throw new IllegalStateException("backup.run-once is required");
        }
        return backupProperties.getRunOnce();
    }

    private static CronExpression parseCron(String value) {
        String cron = value == null ? "" : value.trim();
        if (cron.isEmpty()) {
            throw new IllegalStateException("backup.schedule.cron is required");
        }
        String[] fields = cron.split("\\s+");
        if (fields.length != SPRING_CRON_FIELD_COUNT) {
            throw new IllegalStateException("backup.schedule.cron must contain six Spring cron fields");
        }
        requireSingleNumericCronField(fields[0], "seconds", 0, 59);
        requireSingleNumericCronField(fields[1], "minutes", 0, 59);
        requireSingleNumericCronField(fields[2], "hours", 0, 23);
        requireDailyWildcardCronField(fields[3], "day-of-month");
        requireDailyWildcardCronField(fields[4], "month");
        requireDailyWildcardCronField(fields[5], "day-of-week");
        try {
            return CronExpression.parse(cron);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("backup.schedule.cron is invalid", exception);
        }
    }

    private static void requireDailyWildcardCronField(String value, String fieldName) {
        if (!"*".equals(value)) {
            throw new IllegalStateException(
                    "backup.schedule.cron " + fieldName + " must be '*' for one backup every calendar day"
            );
        }
    }

    private static void requireSingleNumericCronField(
            String value,
            String fieldName,
            int minimum,
            int maximum
    ) {
        if (value == null || !value.matches("[0-9]+")) {
            throw new IllegalStateException(
                    "backup.schedule.cron " + fieldName + " must be one numeric value"
            );
        }
        long parsed;
        try {
            parsed = Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(
                    "backup.schedule.cron " + fieldName + " must be one numeric value",
                    exception
            );
        }
        if (parsed < minimum || parsed > maximum) {
            throw new IllegalStateException(
                    "backup.schedule.cron " + fieldName + " must be between " + minimum + " and " + maximum
            );
        }
    }

    private static ZoneId parseZone(String value) {
        String zone = value == null ? "" : value.trim();
        if (zone.isEmpty()) {
            throw new IllegalStateException("backup.schedule.zone is required");
        }
        try {
            return ZoneId.of(zone);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("backup.schedule.zone is invalid", exception);
        }
    }

    private static Duration requirePositiveDuration(Duration value, String name, Duration maximum) {
        if (value == null || value.isZero() || value.isNegative() || value.compareTo(maximum) > 0) {
            throw new IllegalStateException(name + " must be positive and no greater than " + maximum);
        }
        return value;
    }

    private static Duration requireNonNegativeDuration(Duration value, String name, Duration maximum) {
        if (value == null || value.isNegative() || value.compareTo(maximum) > 0) {
            throw new IllegalStateException(name + " must be non-negative and no greater than " + maximum);
        }
        return value;
    }

    @FunctionalInterface
    private interface BackupOperation {
        void run() throws Exception;
    }
}
