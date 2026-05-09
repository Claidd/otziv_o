package com.hunt.otziv.analytics.service;

import com.hunt.otziv.analytics.model.AnalyticsDailyUser;
import com.hunt.otziv.analytics.model.AnalyticsDailyTotal;
import com.hunt.otziv.analytics.model.AnalyticsMonthlyTotal;
import com.hunt.otziv.analytics.model.AnalyticsMonthlyUser;
import com.hunt.otziv.analytics.repository.AnalyticsDailyTotalRepository;
import com.hunt.otziv.analytics.repository.AnalyticsDailyUserRepository;
import com.hunt.otziv.analytics.repository.AnalyticsMonthlyTotalRepository;
import com.hunt.otziv.analytics.repository.AnalyticsMonthlyUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnalyticsAggregateReadService {

    public static final String SCOPE_TYPE_ADMIN = "ADMIN";
    public static final String SCOPE_TYPE_OWNER = "OWNER";
    public static final String SCOPE_TYPE_MANAGER = "MANAGER";
    public static final String SCOPE_ADMIN_ALL = "ADMIN:ALL";

    private final AnalyticsDailyTotalRepository dailyTotalRepository;
    private final AnalyticsDailyUserRepository dailyUserRepository;
    private final AnalyticsMonthlyUserRepository monthlyUserRepository;
    private final AnalyticsMonthlyTotalRepository monthlyTotalRepository;

    public List<AnalyticsDailyTotal> dailyTotals(String scopeKey, LocalDate fromInclusive, LocalDate toInclusive) {
        DateRange period = requirePeriod(fromInclusive, toInclusive);
        return dailyTotalRepository.findByScopeKeyInPeriod(
                requireScopeKey(scopeKey),
                period.fromInclusive(),
                period.toInclusive()
        );
    }

    public List<AnalyticsMonthlyTotal> monthlyTotals(String scopeKey, LocalDate fromInclusive, LocalDate toInclusive) {
        DateRange period = requirePeriod(fromInclusive, toInclusive);
        return monthlyTotalRepository.findByScopeKeyInMonthPeriod(
                requireScopeKey(scopeKey),
                monthStart(period.fromInclusive()),
                monthStart(period.toInclusive())
        );
    }

    public List<AnalyticsMonthlyUser> monthlyUsers(Collection<Long> userIds, LocalDate fromInclusive, LocalDate toInclusive) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }

        DateRange period = requirePeriod(fromInclusive, toInclusive);
        return monthlyUserRepository.findByUserIdsInMonthPeriod(
                userIds,
                monthStart(period.fromInclusive()),
                monthStart(period.toInclusive())
        );
    }

    public List<AnalyticsMonthlyUser> monthlyUsers(LocalDate fromInclusive, LocalDate toInclusive) {
        DateRange period = requirePeriod(fromInclusive, toInclusive);
        return monthlyUserRepository.findAllInMonthPeriod(
                monthStart(period.fromInclusive()),
                monthStart(period.toInclusive())
        );
    }

    public List<AnalyticsDailyUser> dailyUsers(Collection<Long> userIds, LocalDate fromInclusive, LocalDate toInclusive) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }

        DateRange period = requirePeriod(fromInclusive, toInclusive);
        return dailyUserRepository.findByUserIdsInPeriod(userIds, period.fromInclusive(), period.toInclusive());
    }

    public List<AnalyticsDailyUser> dailyUsers(LocalDate fromInclusive, LocalDate toInclusive) {
        DateRange period = requirePeriod(fromInclusive, toInclusive);
        return dailyUserRepository.findAllInPeriod(period.fromInclusive(), period.toInclusive());
    }

    public AggregatePeriod splitPeriod(LocalDate fromInclusive, LocalDate toInclusive, LocalDate today) {
        DateRange requested = requirePeriod(fromInclusive, toInclusive);
        if (today == null) {
            throw new IllegalArgumentException("today must not be null");
        }

        LocalDate currentMonthStart = monthStart(today);
        LocalDate firstFullMonth = monthStart(requested.fromInclusive());
        if (!requested.fromInclusive().equals(firstFullMonth)) {
            firstFullMonth = firstFullMonth.plusMonths(1);
        }

        LocalDate lastFullMonth = monthStart(requested.toInclusive());
        if (!requested.toInclusive().equals(monthEnd(requested.toInclusive()))) {
            lastFullMonth = lastFullMonth.minusMonths(1);
        }

        LocalDate lastClosedMonth = currentMonthStart.minusMonths(1);
        if (lastFullMonth.isAfter(lastClosedMonth)) {
            lastFullMonth = lastClosedMonth;
        }

        List<DateRange> monthlyRanges = new ArrayList<>();
        List<DateRange> dailyRanges = new ArrayList<>();

        if (firstFullMonth.isAfter(lastFullMonth)) {
            dailyRanges.add(requested);
            return new AggregatePeriod(List.of(), List.copyOf(dailyRanges));
        }

        DateRange monthlyRange = new DateRange(firstFullMonth, lastFullMonth);
        monthlyRanges.add(monthlyRange);

        LocalDate firstMonthlyDay = monthlyRange.fromInclusive();
        if (requested.fromInclusive().isBefore(firstMonthlyDay)) {
            dailyRanges.add(new DateRange(requested.fromInclusive(), firstMonthlyDay.minusDays(1)));
        }

        LocalDate lastMonthlyDay = monthEnd(monthlyRange.toInclusive());
        if (lastMonthlyDay.isBefore(requested.toInclusive())) {
            dailyRanges.add(new DateRange(lastMonthlyDay.plusDays(1), requested.toInclusive()));
        }

        return new AggregatePeriod(List.copyOf(monthlyRanges), List.copyOf(dailyRanges));
    }

    public static String ownerScopeKey(Long ownerUserId) {
        return userScopeKey(SCOPE_TYPE_OWNER, ownerUserId);
    }

    public static String managerScopeKey(Long managerUserId) {
        return userScopeKey(SCOPE_TYPE_MANAGER, managerUserId);
    }

    public static LocalDate monthStart(LocalDate date) {
        if (date == null) {
            throw new IllegalArgumentException("date must not be null");
        }
        return date.withDayOfMonth(1);
    }

    private static LocalDate monthEnd(LocalDate date) {
        return date.withDayOfMonth(date.lengthOfMonth());
    }

    private static String userScopeKey(String scopeType, Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId must not be null");
        }
        return scopeType + ":" + userId;
    }

    private static String requireScopeKey(String scopeKey) {
        if (scopeKey == null || scopeKey.isBlank()) {
            throw new IllegalArgumentException("scopeKey must not be blank");
        }
        return scopeKey.trim();
    }

    private static DateRange requirePeriod(LocalDate fromInclusive, LocalDate toInclusive) {
        if (fromInclusive == null || toInclusive == null) {
            throw new IllegalArgumentException("period dates must not be null");
        }
        if (fromInclusive.isAfter(toInclusive)) {
            throw new IllegalArgumentException("fromInclusive must be before or equal to toInclusive");
        }
        return new DateRange(fromInclusive, toInclusive);
    }

    public record AggregatePeriod(List<DateRange> monthlyRanges, List<DateRange> dailyRanges) {
    }

    public record DateRange(LocalDate fromInclusive, LocalDate toInclusive) {
    }
}
