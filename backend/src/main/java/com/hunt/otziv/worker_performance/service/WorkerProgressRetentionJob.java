package com.hunt.otziv.worker_performance.service;

import com.hunt.otziv.config.settings.AppSettingService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkerProgressRetentionJob {

    private final AppSettingService appSettingService;
    private final JdbcTemplate jdbcTemplate;
    private final StaffDailyProgressService staffDailyProgressService;

    @Scheduled(cron = "${worker.progress.cleanup-cron:0 35 3 * * *}", zone = "${worker.progress.zone:Asia/Irkutsk}")
    @Transactional
    public void cleanup() {
        if (!appSettingService.getBoolean(AppSettingService.WORKER_PROGRESS_CLEANUP_ENABLED, true)) {
            return;
        }

        int rawDays = Math.max(30, appSettingService.getInt(AppSettingService.WORKER_PROGRESS_RAW_RETENTION_DAYS, 90));
        int dailyDays = Math.max(90, appSettingService.getInt(AppSettingService.WORKER_PROGRESS_DAILY_RETENTION_DAYS, 400));
        LocalDate today = LocalDate.now();
        LocalDateTime rawCutoff = LocalDateTime.now().minusDays(rawDays);
        LocalDate dailyCutoff = today.minusDays(dailyDays);

        staffDailyProgressService.rebuildMonthlyAggregates(today.minusMonths(1), true);
        staffDailyProgressService.rebuildMonthlyAggregates(today, false);

        int deletedLifecycle = jdbcTemplate.update(
                "DELETE FROM worker_work_item_lifecycle WHERE updated_at < ?",
                rawCutoff
        );
        int deletedDaily = jdbcTemplate.update("""
                DELETE d
                FROM worker_daily_performance d
                WHERE d.progress_date < ?
                  AND EXISTS (
                    SELECT 1
                    FROM worker_performance_monthly m
                    WHERE m.worker_id = d.worker_id
                      AND m.month_start = DATE_SUB(d.progress_date, INTERVAL DAYOFMONTH(d.progress_date) - 1 DAY)
                  )
                """, dailyCutoff);

        log.info(
                "Worker progress retention complete: deletedLifecycle={}, deletedDaily={}, rawCutoff={}, dailyCutoff={}",
                deletedLifecycle,
                deletedDaily,
                rawCutoff,
                dailyCutoff
        );
    }
}
