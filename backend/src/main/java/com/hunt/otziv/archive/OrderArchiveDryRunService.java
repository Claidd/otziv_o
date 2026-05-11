package com.hunt.otziv.archive;

import com.hunt.otziv.config.settings.AppSettingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

@Slf4j
@Service
public class OrderArchiveDryRunService {

    private static final String ARCHIVE_LOCK_NAME = "otziv.order-archive";
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Irkutsk");
    private static final String RUN_MODE_DRY_RUN = "dry-run";
    private static final String RUN_MODE_LIVE = "live";
    private static final String DEFAULT_REASON = "scheduled-orders-retention-dry-run";
    private static final String DEFAULT_SCHEDULE_CRON = "0 15 4 * * *";
    private static final DateTimeFormatter SCHEDULE_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter LENIENT_SCHEDULE_TIME_FORMATTER = DateTimeFormatter.ofPattern("H:mm");

    private final OrderArchiveDryRunRepository repository;
    private final AppSettingService appSettingService;
    private final Clock clock;

    @Value("${otziv.archive.orders.retention-days:90}")
    private int defaultRetentionDays;

    @Value("${otziv.board.live-slice.retention-days:90}")
    private int boardLiveSliceRetentionDays;

    @Value("${otziv.archive.orders.batch-size:500}")
    private int defaultBatchLimit;

    @Value("${otziv.archive.orders.max-batch-size:5000}")
    private int maxBatchLimit;

    @Value("${otziv.archive.orders.apply-enabled:false}")
    private boolean defaultApplyEnabled;

    @Value("${otziv.archive.orders.schedule.enabled:false}")
    private boolean defaultScheduleWorkerEnabled;

    @Value("${otziv.archive.orders.schedule.cron:" + DEFAULT_SCHEDULE_CRON + "}")
    private String defaultScheduleCron;

    @Value("${otziv.archive.orders.schedule.zone:Asia/Irkutsk}")
    private String defaultScheduleZone;

    @Autowired
    public OrderArchiveDryRunService(OrderArchiveDryRunRepository repository, AppSettingService appSettingService) {
        this(repository, appSettingService, Clock.system(DEFAULT_ZONE));
    }

    OrderArchiveDryRunService(OrderArchiveDryRunRepository repository, Clock clock) {
        this(repository, null, clock);
    }

    OrderArchiveDryRunService(OrderArchiveDryRunRepository repository, AppSettingService appSettingService, Clock clock) {
        this.repository = repository;
        this.appSettingService = appSettingService;
        this.clock = clock;
    }

    @Transactional
    public ArchiveDryRunResult runDryRun(Integer retentionDays, Integer batchLimit, String reason) {
        acquireArchiveLock();
        try {
            return runDryRunLocked(retentionDays, batchLimit, reason);
        } finally {
            repository.releaseArchiveLock(ARCHIVE_LOCK_NAME);
        }
    }

    private ArchiveDryRunResult runDryRunLocked(Integer retentionDays, Integer batchLimit, String reason) {
        int resolvedRetentionDays = positiveOrDefault(retentionDays, defaultRetentionDays, 90);
        int resolvedBatchLimit = batchLimit(batchLimit);
        LocalDate today = LocalDate.now(clock);
        LocalDate cutoffDate = today.minusDays(resolvedRetentionDays);
        LocalDate currentMonthStart = today.withDayOfMonth(1);
        LocalDateTime startedAt = LocalDateTime.now(clock);

        long eligibleOrders = repository.countEligibleOrders(cutoffDate);
        ArchiveCandidateCounts selected = repository.countSelected(cutoffDate, resolvedBatchLimit);
        long missingClosedAnalyticsMonths = repository.countMissingClosedAnalyticsMonths(
                cutoffDate,
                resolvedBatchLimit,
                currentMonthStart
        );

        String archiveReason = normalizeReason(reason);
        String message = "dry-run only"
                + "; cutoffDate=" + cutoffDate
                + "; batchLimit=" + resolvedBatchLimit
                + "; missingClosedAnalyticsMonths=" + missingClosedAnalyticsMonths;
        Long batchId = repository.insertDryRunBatch(
                startedAt,
                LocalDateTime.now(clock),
                archiveReason,
                resolvedRetentionDays,
                eligibleOrders,
                selected,
                message
        );

        ArchiveDryRunResult result = new ArchiveDryRunResult(
                batchId,
                true,
                cutoffDate,
                resolvedRetentionDays,
                resolvedBatchLimit,
                eligibleOrders,
                selected,
                missingClosedAnalyticsMonths,
                message + "; eligibleOrders=" + eligibleOrders
        );
        log.info("Order archive dry-run completed: {}", result);
        return result;
    }

    @Transactional
    public ArchiveRunResult runArchive(Integer retentionDays, Integer batchLimit, String reason, boolean confirm) {
        if (!confirm) {
            throw new IllegalArgumentException("Archive run requires confirm=true");
        }
        if (!runtimeSettings().applyEnabled()) {
            throw new IllegalStateException("Order archive apply is disabled");
        }

        acquireArchiveLock();
        try {
            return runArchiveLocked(retentionDays, batchLimit, reason);
        } finally {
            repository.releaseArchiveLock(ARCHIVE_LOCK_NAME);
        }
    }

    private ArchiveRunResult runArchiveLocked(Integer retentionDays, Integer batchLimit, String reason) {
        int resolvedRetentionDays = positiveOrDefault(retentionDays, defaultRetentionDays, 90);
        int resolvedBatchLimit = batchLimit(batchLimit);
        LocalDate today = LocalDate.now(clock);
        LocalDate cutoffDate = today.minusDays(resolvedRetentionDays);
        LocalDate currentMonthStart = today.withDayOfMonth(1);
        LocalDateTime startedAt = LocalDateTime.now(clock);
        String archiveReason = normalizeReason(reason);

        long eligibleOrders = repository.countEligibleOrders(cutoffDate);
        repository.prepareCandidateOrders(cutoffDate, resolvedBatchLimit);
        ArchiveCandidateCounts selected = repository.countPreparedCandidates();
        long missingClosedAnalyticsMonths = repository.countMissingClosedAnalyticsMonthsForPreparedCandidates(currentMonthStart);
        if (missingClosedAnalyticsMonths > 0) {
            throw new IllegalStateException("Closed analytics months are missing for selected archive candidates");
        }

        Long batchId = repository.insertStartedArchiveBatch(
                startedAt,
                archiveReason,
                resolvedRetentionDays,
                selected,
                "archive started; cutoffDate=" + cutoffDate + "; batchLimit=" + resolvedBatchLimit
        );

        repository.copyPreparedCandidatesToArchive(batchId, LocalDateTime.now(clock), archiveReason);
        ArchiveCandidateCounts archived = repository.countArchivedPreparedCandidates();
        verifyArchiveComplete(selected, archived);
        ArchiveCandidateCounts deleted = repository.deletePreparedCandidatesFromLive();
        verifyDeleteComplete(selected, deleted);

        String message = "archive completed"
                + "; cutoffDate=" + cutoffDate
                + "; batchLimit=" + resolvedBatchLimit
                + "; eligibleOrders=" + eligibleOrders;
        repository.completeArchiveBatch(batchId, LocalDateTime.now(clock), archived, deleted, message);

        ArchiveRunResult result = new ArchiveRunResult(
                batchId,
                false,
                cutoffDate,
                resolvedRetentionDays,
                resolvedBatchLimit,
                eligibleOrders,
                selected,
                archived,
                deleted,
                missingClosedAnalyticsMonths,
                message
        );
        log.warn("Order archive live run completed: {}", result);
        return result;
    }

    @Transactional(readOnly = true)
    public List<ArchiveBatchSummary> latestBatches(Integer limit) {
        int resolvedLimit = Math.min(Math.max(positiveOrDefault(limit, 20, 20), 1), 100);
        return repository.findLatestBatches(resolvedLimit);
    }

    @Transactional(readOnly = true)
    public ArchiveBatchDetails batchDetails(Long batchId) {
        if (batchId == null || batchId <= 0) {
            throw new IllegalArgumentException("Archive batch id is required");
        }
        ArchiveBatchSummary summary = repository.findBatch(batchId);
        ArchiveCandidateCounts totals = new ArchiveCandidateCounts(
                summary.ordersArchived(),
                summary.orderDetailsArchived(),
                summary.reviewsArchived(),
                summary.badReviewTasksArchived(),
                summary.nextOrderRequestsArchived(),
                summary.zpArchived(),
                summary.paymentCheckArchived()
        );
        return new ArchiveBatchDetails(
                summary,
                totals,
                summary.dryRun() ? List.of() : repository.findArchivedOrdersByBatch(batchId, 30)
        );
    }

    @Transactional(readOnly = true)
    public ArchiveLockStatus lockStatus() {
        return repository.lockStatus(ARCHIVE_LOCK_NAME);
    }

    @Transactional(readOnly = true)
    public ArchiveCandidatesPreview previewCandidates(Integer retentionDays, Integer batchLimit, Integer previewLimit) {
        int resolvedRetentionDays = positiveOrDefault(retentionDays, defaultRetentionDays, 90);
        int resolvedBatchLimit = batchLimit(batchLimit);
        int resolvedPreviewLimit = Math.min(Math.max(positiveOrDefault(previewLimit, 8, 8), 1), 30);
        LocalDate today = LocalDate.now(clock);
        LocalDate cutoffDate = today.minusDays(resolvedRetentionDays);
        LocalDate currentMonthStart = today.withDayOfMonth(1);

        long eligibleOrders = repository.countEligibleOrders(cutoffDate);
        ArchiveCandidateCounts selected = repository.countSelected(cutoffDate, resolvedBatchLimit);
        long missingClosedAnalyticsMonths = repository.countMissingClosedAnalyticsMonths(
                cutoffDate,
                resolvedBatchLimit,
                currentMonthStart
        );

        return new ArchiveCandidatesPreview(
                cutoffDate,
                resolvedRetentionDays,
                resolvedBatchLimit,
                eligibleOrders,
                selected,
                missingClosedAnalyticsMonths,
                repository.findCandidateOrders(cutoffDate, resolvedBatchLimit, resolvedPreviewLimit)
        );
    }

    public ArchiveOrdersSettingsResponse settings() {
        RuntimeSettings settings = runtimeSettings();
        return new ArchiveOrdersSettingsResponse(
                positiveOrDefault(null, boardLiveSliceRetentionDays, 90),
                settings.retentionDays(),
                settings.batchSize(),
                Math.max(maxBatchLimit, 1),
                settings.applyEnabled(),
                settings.scheduleWorkerEnabled(),
                settings.scheduleEnabled(),
                settings.runMode(),
                settings.reason(),
                settings.scheduleTime(),
                settings.scheduleCron(),
                settings.scheduleZone()
        );
    }

    @Transactional
    public ArchiveOrdersSettingsResponse updateSettings(ArchiveOrdersSettingsRequest request) {
        if (appSettingService == null) {
            throw new IllegalStateException("Archive settings storage is not available");
        }
        ArchiveOrdersSettingsRequest value = request == null
                ? new ArchiveOrdersSettingsRequest(null, null, null, null, null, null, null, null, null, null)
                : request;
        RuntimeSettings currentSettings = runtimeSettings();

        int resolvedRetentionDays = positiveOrDefault(value.archiveRetentionDays(), defaultRetentionDays, 90);
        int resolvedBatchSize = batchLimit(value.batchSize());
        boolean resolvedApplyEnabled = value.applyEnabled() == null
                ? currentSettings.applyEnabled()
                : Boolean.TRUE.equals(value.applyEnabled());
        boolean resolvedScheduleWorkerEnabled = value.scheduleWorkerEnabled() == null
                ? currentSettings.scheduleWorkerEnabled()
                : Boolean.TRUE.equals(value.scheduleWorkerEnabled());
        boolean resolvedScheduleEnabled = value.scheduleEnabled() == null
                ? currentSettings.scheduleEnabled()
                : Boolean.TRUE.equals(value.scheduleEnabled());
        String resolvedRunMode = normalizeRunMode(value.runMode());
        if (RUN_MODE_LIVE.equals(resolvedRunMode) && !resolvedApplyEnabled) {
            throw new IllegalArgumentException("Live archive mode requires archive apply enabled");
        }
        String resolvedReason = normalizeReason(StringUtils.hasText(value.reason()) ? value.reason() : DEFAULT_REASON);
        String resolvedScheduleZone = normalizeRequestZone(value.scheduleZone(), currentSettings.scheduleZone());
        String resolvedScheduleCron = normalizeRequestCron(
                value.scheduleCron(),
                value.scheduleTime(),
                currentSettings.scheduleCron()
        );

        appSettingService.setInt(AppSettingService.ARCHIVE_ORDERS_RETENTION_DAYS, resolvedRetentionDays);
        appSettingService.setInt(AppSettingService.ARCHIVE_ORDERS_BATCH_SIZE, resolvedBatchSize);
        appSettingService.setBoolean(AppSettingService.ARCHIVE_ORDERS_APPLY_ENABLED, resolvedApplyEnabled);
        appSettingService.setBoolean(AppSettingService.ARCHIVE_ORDERS_SCHEDULE_WORKER_ENABLED, resolvedScheduleWorkerEnabled);
        appSettingService.setBoolean(AppSettingService.ARCHIVE_ORDERS_SCHEDULE_ENABLED, resolvedScheduleEnabled);
        appSettingService.setString(AppSettingService.ARCHIVE_ORDERS_RUN_MODE, resolvedRunMode);
        appSettingService.setString(AppSettingService.ARCHIVE_ORDERS_REASON, resolvedReason);
        appSettingService.setString(AppSettingService.ARCHIVE_ORDERS_SCHEDULE_CRON, resolvedScheduleCron);
        appSettingService.setString(AppSettingService.ARCHIVE_ORDERS_SCHEDULE_ZONE, resolvedScheduleZone);

        return settings();
    }

    @Transactional
    public Object runScheduledConfiguredArchive() {
        RuntimeSettings settings = runtimeSettings();
        if (!settings.scheduleWorkerEnabled()) {
            log.debug("Scheduled order archive skipped: worker is disabled");
            return null;
        }
        if (!settings.scheduleEnabled()) {
            log.debug("Scheduled order archive skipped: runtime schedule is disabled");
            return null;
        }

        if (RUN_MODE_LIVE.equals(settings.runMode())) {
            return runArchive(settings.retentionDays(), settings.batchSize(), settings.reason(), true);
        }

        return runDryRun(settings.retentionDays(), settings.batchSize(), settings.reason());
    }

    @Transactional
    public boolean claimScheduledArchiveRun(String runKey) {
        if (!StringUtils.hasText(runKey)) {
            throw new IllegalArgumentException("Scheduled archive run key is required");
        }
        if (appSettingService == null) {
            return true;
        }

        String normalizedRunKey = runKey.trim();
        String lastRunKey = appSettingService.getString(
                AppSettingService.ARCHIVE_ORDERS_SCHEDULE_LAST_RUN_KEY,
                ""
        );
        if (normalizedRunKey.equals(lastRunKey)) {
            return false;
        }

        appSettingService.setString(AppSettingService.ARCHIVE_ORDERS_SCHEDULE_LAST_RUN_KEY, normalizedRunKey);
        return true;
    }

    void setDefaultRetentionDays(int defaultRetentionDays) {
        this.defaultRetentionDays = defaultRetentionDays;
    }

    void setDefaultBatchLimit(int defaultBatchLimit) {
        this.defaultBatchLimit = defaultBatchLimit;
    }

    void setMaxBatchLimit(int maxBatchLimit) {
        this.maxBatchLimit = maxBatchLimit;
    }

    void setApplyEnabled(boolean applyEnabled) {
        this.defaultApplyEnabled = applyEnabled;
    }

    void setDefaultScheduleWorkerEnabled(boolean defaultScheduleWorkerEnabled) {
        this.defaultScheduleWorkerEnabled = defaultScheduleWorkerEnabled;
    }

    void setDefaultScheduleCron(String defaultScheduleCron) {
        this.defaultScheduleCron = defaultScheduleCron;
    }

    void setDefaultScheduleZone(String defaultScheduleZone) {
        this.defaultScheduleZone = defaultScheduleZone;
    }

    private void verifyArchiveComplete(ArchiveCandidateCounts selected, ArchiveCandidateCounts archived) {
        if (!selected.equals(archived)) {
            throw new IllegalStateException("Archive copy verification failed: selected=" + selected + ", archived=" + archived);
        }
    }

    private void verifyDeleteComplete(ArchiveCandidateCounts selected, ArchiveCandidateCounts deleted) {
        if (!selected.equals(deleted)) {
            throw new IllegalStateException("Archive delete verification failed: selected=" + selected + ", deleted=" + deleted);
        }
    }

    private void acquireArchiveLock() {
        if (!repository.tryAcquireArchiveLock(ARCHIVE_LOCK_NAME, 0)) {
            throw new IllegalStateException("Order archive is already running");
        }
    }

    private int batchLimit(Integer value) {
        int fallback = positiveOrDefault(null, defaultBatchLimit, 500);
        int resolved = positiveOrDefault(value, fallback, 500);
        int max = Math.max(maxBatchLimit, 1);
        return Math.min(resolved, max);
    }

    private int positiveOrDefault(Integer value, int configuredDefault, int hardDefault) {
        if (value != null && value > 0) {
            return value;
        }
        if (configuredDefault > 0) {
            return configuredDefault;
        }
        return hardDefault;
    }

    private String normalizeReason(String reason) {
        String value = reason == null || reason.isBlank()
                ? "orders-retention-dry-run"
                : reason.trim();
        return value.length() <= 100 ? value : value.substring(0, 100);
    }

    private RuntimeSettings runtimeSettings() {
        int retentionDays = appSettingService == null
                ? positiveOrDefault(null, defaultRetentionDays, 90)
                : appSettingService.getInt(
                        AppSettingService.ARCHIVE_ORDERS_RETENTION_DAYS,
                        positiveOrDefault(null, defaultRetentionDays, 90)
                );
        int batchSize = appSettingService == null
                ? positiveOrDefault(null, defaultBatchLimit, 500)
                : appSettingService.getInt(
                        AppSettingService.ARCHIVE_ORDERS_BATCH_SIZE,
                        positiveOrDefault(null, defaultBatchLimit, 500)
                );
        boolean runtimeScheduleEnabled = appSettingService != null && appSettingService.getBoolean(
                AppSettingService.ARCHIVE_ORDERS_SCHEDULE_ENABLED,
                false
        );
        boolean applyEnabled = appSettingService == null
                ? defaultApplyEnabled
                : appSettingService.getBoolean(AppSettingService.ARCHIVE_ORDERS_APPLY_ENABLED, defaultApplyEnabled);
        boolean scheduleWorkerEnabled = appSettingService == null
                ? defaultScheduleWorkerEnabled
                : appSettingService.getBoolean(
                        AppSettingService.ARCHIVE_ORDERS_SCHEDULE_WORKER_ENABLED,
                        defaultScheduleWorkerEnabled
                );
        String runMode = appSettingService == null
                ? RUN_MODE_DRY_RUN
                : appSettingService.getString(AppSettingService.ARCHIVE_ORDERS_RUN_MODE, RUN_MODE_DRY_RUN);
        String reason = appSettingService == null
                ? DEFAULT_REASON
                : appSettingService.getString(AppSettingService.ARCHIVE_ORDERS_REASON, DEFAULT_REASON);
        String scheduleCron = appSettingService == null
                ? normalizeConfiguredCron(defaultScheduleCron, DEFAULT_SCHEDULE_CRON)
                : normalizeConfiguredCron(
                        appSettingService.getString(
                                AppSettingService.ARCHIVE_ORDERS_SCHEDULE_CRON,
                                normalizeConfiguredCron(defaultScheduleCron, DEFAULT_SCHEDULE_CRON)
                        ),
                        DEFAULT_SCHEDULE_CRON
                );
        String scheduleZone = appSettingService == null
                ? normalizeConfiguredZone(defaultScheduleZone, DEFAULT_ZONE.getId())
                : normalizeConfiguredZone(
                        appSettingService.getString(
                                AppSettingService.ARCHIVE_ORDERS_SCHEDULE_ZONE,
                                normalizeConfiguredZone(defaultScheduleZone, DEFAULT_ZONE.getId())
                        ),
                        DEFAULT_ZONE.getId()
                );
        String normalizedRunMode = normalizeRunMode(runMode);
        if (RUN_MODE_LIVE.equals(normalizedRunMode) && !applyEnabled) {
            normalizedRunMode = RUN_MODE_DRY_RUN;
        }

        return new RuntimeSettings(
                positiveOrDefault(retentionDays, defaultRetentionDays, 90),
                batchLimit(batchSize),
                applyEnabled,
                scheduleWorkerEnabled,
                runtimeScheduleEnabled,
                normalizedRunMode,
                normalizeReason(reason),
                scheduleTimeFromCron(scheduleCron),
                scheduleCron,
                scheduleZone
        );
    }

    private String normalizeRunMode(String runMode) {
        String value = StringUtils.hasText(runMode) ? runMode.trim().toLowerCase() : RUN_MODE_DRY_RUN;
        if (RUN_MODE_DRY_RUN.equals(value) || RUN_MODE_LIVE.equals(value)) {
            return value;
        }
        throw new IllegalArgumentException("Archive run mode must be dry-run or live");
    }

    private String normalizeRequestCron(String scheduleCron, String scheduleTime, String fallback) {
        String value = StringUtils.hasText(scheduleTime)
                ? dailyCronFromTime(scheduleTime)
                : (StringUtils.hasText(scheduleCron) ? scheduleCron.trim() : fallback);
        if (!CronExpression.isValidExpression(value)) {
            throw new IllegalArgumentException("Archive schedule cron is invalid");
        }
        return value;
    }

    private String normalizeConfiguredCron(String scheduleCron, String fallback) {
        String value = StringUtils.hasText(scheduleCron) ? scheduleCron.trim() : fallback;
        if (CronExpression.isValidExpression(value)) {
            return value;
        }
        if (CronExpression.isValidExpression(fallback)) {
            return fallback;
        }
        return DEFAULT_SCHEDULE_CRON;
    }

    private String normalizeRequestZone(String scheduleZone, String fallback) {
        String value = StringUtils.hasText(scheduleZone) ? scheduleZone.trim() : fallback;
        try {
            return ZoneId.of(value).getId();
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("Archive schedule zone is invalid", exception);
        }
    }

    private String normalizeConfiguredZone(String scheduleZone, String fallback) {
        String value = StringUtils.hasText(scheduleZone) ? scheduleZone.trim() : fallback;
        try {
            return ZoneId.of(value).getId();
        } catch (DateTimeException exception) {
            return fallback;
        }
    }

    private String dailyCronFromTime(String scheduleTime) {
        LocalTime time = parseScheduleTime(scheduleTime);
        return "0 " + time.getMinute() + " " + time.getHour() + " * * *";
    }

    private LocalTime parseScheduleTime(String scheduleTime) {
        String value = scheduleTime == null ? "" : scheduleTime.trim();
        try {
            return LocalTime.parse(value, LENIENT_SCHEDULE_TIME_FORMATTER).withSecond(0).withNano(0);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("Archive schedule time must be HH:mm", exception);
        }
    }

    private String scheduleTimeFromCron(String scheduleCron) {
        String[] fields = scheduleCron == null ? new String[0] : scheduleCron.trim().split("\\s+");
        if (fields.length != 6) {
            return "";
        }
        if (!"0".equals(fields[0]) || !dailyCronWildcard(fields[3]) || !"*".equals(fields[4]) || !dailyCronWildcard(fields[5])) {
            return "";
        }
        try {
            int minute = Integer.parseInt(fields[1]);
            int hour = Integer.parseInt(fields[2]);
            if (minute < 0 || minute > 59 || hour < 0 || hour > 23) {
                return "";
            }
            return LocalTime.of(hour, minute).format(SCHEDULE_TIME_FORMATTER);
        } catch (NumberFormatException exception) {
            return "";
        }
    }

    private boolean dailyCronWildcard(String field) {
        return "*".equals(field) || "?".equals(field);
    }

    private record RuntimeSettings(
            int retentionDays,
            int batchSize,
            boolean applyEnabled,
            boolean scheduleWorkerEnabled,
            boolean scheduleEnabled,
            String runMode,
            String reason,
            String scheduleTime,
            String scheduleCron,
            String scheduleZone
    ) {
    }
}
