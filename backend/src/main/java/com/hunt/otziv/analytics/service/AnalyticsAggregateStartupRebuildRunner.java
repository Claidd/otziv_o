package com.hunt.otziv.analytics.service;

import com.hunt.otziv.analytics.service.AnalyticsAggregateVerificationService.AnalyticsAdminMonthComparison;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "otziv.analytics.rebuild.startup", name = "enabled", havingValue = "true")
public class AnalyticsAggregateStartupRebuildRunner implements ApplicationRunner {

    private final AnalyticsAggregateRebuildService rebuildService;
    private final AnalyticsAggregateVerificationService verificationService;

    @Value("${otziv.analytics.rebuild.startup.month:}")
    private String month;

    @Value("${otziv.analytics.rebuild.startup.closed:false}")
    private boolean closed;

    @Override
    public void run(ApplicationArguments args) {
        LocalDate monthStart = parseMonth(month);
        log.warn("Starting manual analytics aggregate rebuild for month={}, closed={}", monthStart, closed);

        AnalyticsAggregateRebuildService.AnalyticsAggregateRebuildResult rebuild = rebuildService.rebuildMonth(monthStart, closed);
        AnalyticsAdminMonthComparison comparison = verificationService.compareAdminMonth(monthStart);

        log.warn("Analytics aggregate rebuild result: {}", rebuild);
        log.warn("Analytics aggregate admin comparison: {}", comparison);

        if (!comparison.matches()) {
            throw new IllegalStateException("Analytics aggregate comparison failed for month " + monthStart);
        }
    }

    private LocalDate parseMonth(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("otziv.analytics.rebuild.startup.month must be yyyy-MM or yyyy-MM-dd");
        }

        String normalized = value.trim();
        if (normalized.length() == 7) {
            return YearMonth.parse(normalized).atDay(1);
        }
        return LocalDate.parse(normalized).withDayOfMonth(1);
    }
}
