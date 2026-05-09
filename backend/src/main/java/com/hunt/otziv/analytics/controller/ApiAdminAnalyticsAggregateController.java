package com.hunt.otziv.analytics.controller;

import com.hunt.otziv.analytics.service.AnalyticsAggregateRebuildService;
import com.hunt.otziv.analytics.service.AnalyticsAggregateRebuildService.AnalyticsAggregateRebuildResult;
import com.hunt.otziv.analytics.service.AnalyticsAggregateScoreComparisonService;
import com.hunt.otziv.analytics.service.AnalyticsAggregateScoreComparisonService.AnalyticsScoreComparison;
import com.hunt.otziv.analytics.service.AnalyticsAggregateStatsComparisonService;
import com.hunt.otziv.analytics.service.AnalyticsAggregateStatsComparisonService.AnalyticsStatsComparison;
import com.hunt.otziv.analytics.service.AnalyticsAggregateTeamComparisonService;
import com.hunt.otziv.analytics.service.AnalyticsAggregateTeamComparisonService.AnalyticsTeamComparison;
import com.hunt.otziv.analytics.service.AnalyticsAggregateUserStatsComparisonService;
import com.hunt.otziv.analytics.service.AnalyticsAggregateUserStatsComparisonService.AnalyticsUserStatsComparison;
import com.hunt.otziv.analytics.service.AnalyticsAggregateVerificationService;
import com.hunt.otziv.analytics.service.AnalyticsAggregateVerificationService.AnalyticsAdminMonthComparison;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.YearMonth;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/analytics/aggregates")
@PreAuthorize("hasRole('ADMIN')")
@ConditionalOnProperty(prefix = "otziv.analytics.rebuild", name = "api-enabled", havingValue = "true")
public class ApiAdminAnalyticsAggregateController {

    private final AnalyticsAggregateRebuildService rebuildService;
    private final AnalyticsAggregateVerificationService verificationService;
    private final AnalyticsAggregateStatsComparisonService statsComparisonService;
    private final AnalyticsAggregateScoreComparisonService scoreComparisonService;
    private final AnalyticsAggregateTeamComparisonService teamComparisonService;
    private final AnalyticsAggregateUserStatsComparisonService userStatsComparisonService;

    @PostMapping("/rebuild-month")
    public RebuildMonthResponse rebuildMonth(
            @RequestParam("month") String month,
            @RequestParam(name = "closed", defaultValue = "false") boolean closed
    ) {
        LocalDate monthStart = parseMonth(month);
        AnalyticsAggregateRebuildResult rebuild = rebuildService.rebuildMonth(monthStart, closed);
        AnalyticsAdminMonthComparison adminComparison = verificationService.compareAdminMonth(monthStart);
        return new RebuildMonthResponse(monthStart, closed, rebuild, adminComparison);
    }

    @GetMapping("/compare-admin-month")
    public AnalyticsAdminMonthComparison compareAdminMonth(@RequestParam("month") String month) {
        return verificationService.compareAdminMonth(parseMonth(month));
    }

    @GetMapping("/compare-cabinet-analyse")
    public AnalyticsStatsComparison compareCabinetAnalyse(
            @RequestParam("username") String username,
            @RequestParam("date")
            @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date,
            @RequestParam(name = "role", required = false) String role
    ) {
        return statsComparisonService.compare(username, date, role);
    }

    @GetMapping("/compare-score")
    public AnalyticsScoreComparison compareScore(
            @RequestParam("date")
            @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date
    ) {
        return scoreComparisonService.compare(date);
    }

    @GetMapping("/compare-team")
    public AnalyticsTeamComparison compareTeam(
            @RequestParam("username") String username,
            @RequestParam("date")
            @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date,
            @RequestParam(name = "role", required = false) String role
    ) {
        return teamComparisonService.compare(username, date, role);
    }

    @GetMapping("/compare-user-stats")
    public AnalyticsUserStatsComparison compareUserStats(
            @RequestParam("userId") Long userId,
            @RequestParam("date")
            @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date
    ) {
        return userStatsComparisonService.compare(userId, date);
    }

    private LocalDate parseMonth(String value) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "month must not be blank");
        }

        String normalized = value.trim();
        try {
            if (normalized.length() == 7) {
                return YearMonth.parse(normalized).atDay(1);
            }
            return LocalDate.parse(normalized).withDayOfMonth(1);
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(BAD_REQUEST, "month must be yyyy-MM or yyyy-MM-dd", ex);
        }
    }

    public record RebuildMonthResponse(
            LocalDate monthStart,
            boolean periodClosed,
            AnalyticsAggregateRebuildResult rebuild,
            AnalyticsAdminMonthComparison adminComparison
    ) {
    }
}
