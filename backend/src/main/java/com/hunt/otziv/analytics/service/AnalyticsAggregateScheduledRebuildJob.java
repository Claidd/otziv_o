package com.hunt.otziv.analytics.service;

import com.hunt.otziv.analytics.service.AnalyticsAggregateRebuildService.AnalyticsAggregateRebuildResult;
import com.hunt.otziv.analytics.service.AnalyticsAggregateVerificationService.AnalyticsAdminMonthComparison;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "otziv.analytics.rebuild.schedule", name = "enabled", havingValue = "true")
public class AnalyticsAggregateScheduledRebuildJob {

    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Irkutsk");

    private final AnalyticsAggregateRebuildService rebuildService;
    private final AnalyticsAggregateVerificationService verificationService;
    private final Clock clock;

    @Value("${otziv.analytics.rebuild.schedule.previous-month-window-days:7}")
    private int previousMonthWindowDays;

    @Value("${otziv.analytics.rebuild.schedule.verify-admin-month:true}")
    private boolean verifyAdminMonth;

    public AnalyticsAggregateScheduledRebuildJob(
            AnalyticsAggregateRebuildService rebuildService,
            AnalyticsAggregateVerificationService verificationService
    ) {
        this(rebuildService, verificationService, Clock.system(DEFAULT_ZONE));
    }

    AnalyticsAggregateScheduledRebuildJob(
            AnalyticsAggregateRebuildService rebuildService,
            AnalyticsAggregateVerificationService verificationService,
            Clock clock
    ) {
        this.rebuildService = rebuildService;
        this.verificationService = verificationService;
        this.clock = clock;
    }

    @Scheduled(
            cron = "${otziv.analytics.rebuild.schedule.cron:0 30 3 * * *}",
            zone = "${otziv.analytics.rebuild.schedule.zone:Asia/Irkutsk}"
    )
    public void runScheduledRebuild() {
        LocalDate today = LocalDate.now(clock);
        try {
            AnalyticsScheduledRebuildRun run = rebuildRecentMonths(today);
            log.info("Analytics aggregate scheduled rebuild completed: {}", run);
            if (!run.matches()) {
                log.error("Analytics aggregate scheduled rebuild finished with verification mismatches: {}", run);
            }
        } catch (RuntimeException exception) {
            log.error("Analytics aggregate scheduled rebuild failed for date {}", today, exception);
        }
    }

    AnalyticsScheduledRebuildRun rebuildRecentMonths(LocalDate today) {
        if (today == null) {
            throw new IllegalArgumentException("today must not be null");
        }

        LocalDate currentMonth = AnalyticsAggregateReadService.monthStart(today);
        List<AnalyticsScheduledRebuildMonth> months = new ArrayList<>();
        months.add(rebuildMonth(currentMonth, false));

        if (shouldRebuildPreviousMonth(today)) {
            months.add(rebuildMonth(currentMonth.minusMonths(1), true));
        }

        return new AnalyticsScheduledRebuildRun(today, List.copyOf(months));
    }

    boolean shouldRebuildPreviousMonth(LocalDate today) {
        if (today == null) {
            throw new IllegalArgumentException("today must not be null");
        }
        return previousMonthWindowDays > 0 && today.getDayOfMonth() <= previousMonthWindowDays;
    }

    void setPreviousMonthWindowDays(int previousMonthWindowDays) {
        this.previousMonthWindowDays = previousMonthWindowDays;
    }

    void setVerifyAdminMonth(boolean verifyAdminMonth) {
        this.verifyAdminMonth = verifyAdminMonth;
    }

    private AnalyticsScheduledRebuildMonth rebuildMonth(LocalDate monthStart, boolean periodClosed) {
        AnalyticsAggregateRebuildResult rebuild = rebuildService.rebuildMonth(monthStart, periodClosed);
        AnalyticsAdminMonthComparison comparison = verifyAdminMonth
                ? verificationService.compareAdminMonth(monthStart)
                : null;
        return new AnalyticsScheduledRebuildMonth(monthStart, periodClosed, rebuild, comparison);
    }

    public record AnalyticsScheduledRebuildRun(
            LocalDate runDate,
            List<AnalyticsScheduledRebuildMonth> months
    ) {
        boolean matches() {
            return months.stream().allMatch(AnalyticsScheduledRebuildMonth::matches);
        }
    }

    public record AnalyticsScheduledRebuildMonth(
            LocalDate monthStart,
            boolean periodClosed,
            AnalyticsAggregateRebuildResult rebuild,
            AnalyticsAdminMonthComparison adminComparison
    ) {
        boolean matches() {
            return adminComparison == null || adminComparison.matches();
        }
    }
}
