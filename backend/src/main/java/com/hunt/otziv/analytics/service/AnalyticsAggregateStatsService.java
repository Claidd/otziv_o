package com.hunt.otziv.analytics.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.admin.dto.personal_stat.StatDTO;
import com.hunt.otziv.analytics.model.AnalyticsDailyTotal;
import com.hunt.otziv.analytics.model.AnalyticsMetricAggregate;
import com.hunt.otziv.analytics.model.AnalyticsMonthlyTotal;
import com.hunt.otziv.u_users.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnalyticsAggregateStatsService {

    private static final String ROLE_ADMIN = "ROLE_ADMIN";
    private static final String ROLE_OWNER = "ROLE_OWNER";
    private static final LocalDate ANALYTICS_START = LocalDate.of(2000, 1, 1);
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private final AnalyticsAggregateReadService readService;
    private final ObjectMapper objectMapper;

    public Optional<StatDTO> buildStats(LocalDate selectedDate, User user, String role) {
        if (selectedDate == null || user == null) {
            return Optional.empty();
        }

        String scopeKey = scopeKey(user, role);
        if (scopeKey == null) {
            return Optional.empty();
        }

        LocalDate firstDayOfMonth = selectedDate.withDayOfMonth(1);
        boolean hasAggregateData = !readService.monthlyTotals(scopeKey, ANALYTICS_START, selectedDate).isEmpty()
                || !readService.dailyTotals(scopeKey, firstDayOfMonth, selectedDate).isEmpty();
        if (!hasAggregateData) {
            return Optional.empty();
        }

        LocalDate firstDayOfPreviousMonth = firstDayOfMonth.minusMonths(1);
        LocalDate firstDayOfThreeMonthsAgo = firstDayOfMonth.minusMonths(3);
        LocalDate lastDayOfPreviousMonth = firstDayOfPreviousMonth.withDayOfMonth(firstDayOfPreviousMonth.lengthOfMonth());
        LocalDate lastDayOfThreeMonthsAgo = firstDayOfThreeMonthsAgo.withDayOfMonth(firstDayOfThreeMonthsAgo.lengthOfMonth());
        LocalDate firstDayOfYear = selectedDate.withDayOfYear(1);
        LocalDate firstDayOfPreviousYear = selectedDate.minusYears(1).withDayOfYear(1);
        LocalDate sameDayPreviousYear = selectedDate.minusYears(1);

        StatDTO stats = new StatDTO();
        stats.setOrderPayMap(toJson(dailyMetricMap(
                scopeKey,
                firstDayOfMonth,
                selectedDate,
                selectedDate.lengthOfMonth(),
                AnalyticsMetricAggregate::getPaymentSum
        )));
        stats.setOrderPayMapMonth(toJson(monthlyMetricMap(scopeKey, selectedDate, AnalyticsMetricAggregate::getPaymentSum)));
        stats.setZpPayMap(toJson(dailyMetricMap(
                scopeKey,
                firstDayOfMonth,
                selectedDate,
                selectedDate.lengthOfMonth(),
                AnalyticsMetricAggregate::getSalarySum
        )));
        stats.setZpPayMapMonth(toJson(monthlyMetricMap(scopeKey, selectedDate, AnalyticsMetricAggregate::getSalarySum)));

        BigDecimal payment1Day = sumDecimal(scopeKey, selectedDate.minusDays(1), selectedDate.minusDays(1), selectedDate, AnalyticsMetricAggregate::getPaymentSum);
        BigDecimal payment2Day = sumDecimal(scopeKey, selectedDate.minusDays(2), selectedDate.minusDays(2), selectedDate, AnalyticsMetricAggregate::getPaymentSum);
        BigDecimal payment7Day = sumDecimal(scopeKey, selectedDate.minusDays(7), selectedDate, selectedDate, AnalyticsMetricAggregate::getPaymentSum);
        BigDecimal payment14Day = sumDecimal(scopeKey, selectedDate.minusDays(14), selectedDate.minusDays(8), selectedDate, AnalyticsMetricAggregate::getPaymentSum);
        BigDecimal paymentCurrentMonth = sumDecimal(scopeKey, firstDayOfMonth, selectedDate, selectedDate, AnalyticsMetricAggregate::getPaymentSum);
        BigDecimal paymentPreviousMonth = sumDecimal(scopeKey, firstDayOfPreviousMonth, lastDayOfPreviousMonth, selectedDate, AnalyticsMetricAggregate::getPaymentSum);
        BigDecimal paymentCurrentYear = sumDecimal(scopeKey, firstDayOfYear, selectedDate, selectedDate, AnalyticsMetricAggregate::getPaymentSum);
        BigDecimal paymentPreviousYear = sumDecimal(scopeKey, firstDayOfPreviousYear, sameDayPreviousYear, selectedDate, AnalyticsMetricAggregate::getPaymentSum);

        long paymentCurrentMonthCount = sumLong(scopeKey, firstDayOfMonth, selectedDate, selectedDate, AnalyticsMetricAggregate::getPaymentCount);
        long paymentPreviousMonthCount = sumLong(scopeKey, firstDayOfPreviousMonth, lastDayOfPreviousMonth, selectedDate, AnalyticsMetricAggregate::getPaymentCount);
        long paymentThreeMonthsAgoCount = sumLong(scopeKey, firstDayOfThreeMonthsAgo, lastDayOfThreeMonthsAgo, selectedDate, AnalyticsMetricAggregate::getPaymentCount);
        long leadsCurrentMonth = sumLong(scopeKey, firstDayOfMonth, firstDayOfMonth.withDayOfMonth(firstDayOfMonth.lengthOfMonth()), selectedDate, AnalyticsMetricAggregate::getLeadsNewCount);
        long leadsPreviousMonth = sumLong(scopeKey, firstDayOfPreviousMonth, lastDayOfPreviousMonth, selectedDate, AnalyticsMetricAggregate::getLeadsNewCount);
        long leadsInWorkCurrentMonth = sumLong(scopeKey, firstDayOfMonth, firstDayOfMonth.withDayOfMonth(firstDayOfMonth.lengthOfMonth()), selectedDate, AnalyticsMetricAggregate::getLeadsInWorkCount);
        long leadsInWorkPreviousMonth = sumLong(scopeKey, firstDayOfPreviousMonth, lastDayOfPreviousMonth, selectedDate, AnalyticsMetricAggregate::getLeadsInWorkCount);

        stats.setSum1DayPay(payment1Day.intValue());
        stats.setSum1WeekPay(payment7Day.intValue());
        stats.setSum1MonthPay(paymentCurrentMonth.intValue());
        stats.setSum1YearPay(paymentCurrentYear.intValue());
        stats.setSumOrders1MonthPay(toInt(paymentCurrentMonthCount));
        stats.setSumOrders2MonthPay(toInt(paymentPreviousMonthCount));
        stats.setNewLeads(toInt(leadsCurrentMonth));
        stats.setLeadsInWork(toInt(leadsInWorkCurrentMonth));
        stats.setPercent1DayPay(calculatePercentageDifference(payment1Day, payment2Day).intValue());
        stats.setPercent1WeekPay(calculatePercentageDifference(payment7Day, payment14Day).intValue());
        stats.setPercent1MonthPay(calculatePercentageDifference(paymentCurrentMonth, paymentPreviousMonth).intValue());
        stats.setPercent1YearPay(calculatePercentageDifference(paymentCurrentYear, paymentPreviousYear).intValue());
        stats.setPercent1MonthOrdersPay(calculatePercentageDifference(paymentCurrentMonthCount, paymentPreviousMonthCount));
        stats.setPercent2MonthOrdersPay(calculatePercentageDifference(paymentPreviousMonthCount, paymentThreeMonthsAgoCount));
        stats.setPercent1NewLeadsPay(calculatePercentageDifference(leadsCurrentMonth, leadsPreviousMonth));
        stats.setPercent2InWorkLeadsPay(calculatePercentageDifference(leadsInWorkCurrentMonth, leadsInWorkPreviousMonth));

        BigDecimal salary1Day = sumDecimal(scopeKey, selectedDate.minusDays(1), selectedDate.minusDays(1), selectedDate, AnalyticsMetricAggregate::getSalarySum);
        BigDecimal salary2Day = sumDecimal(scopeKey, selectedDate.minusDays(2), selectedDate.minusDays(2), selectedDate, AnalyticsMetricAggregate::getSalarySum);
        BigDecimal salary7Day = sumDecimal(scopeKey, selectedDate.minusDays(7), selectedDate, selectedDate, AnalyticsMetricAggregate::getSalarySum);
        BigDecimal salary14Day = sumDecimal(scopeKey, selectedDate.minusDays(14), selectedDate.minusDays(8), selectedDate, AnalyticsMetricAggregate::getSalarySum);
        BigDecimal salaryCurrentMonth = sumDecimal(scopeKey, firstDayOfMonth, selectedDate, selectedDate, AnalyticsMetricAggregate::getSalarySum);
        BigDecimal salaryPreviousMonth = sumDecimal(scopeKey, firstDayOfPreviousMonth, lastDayOfPreviousMonth, selectedDate, AnalyticsMetricAggregate::getSalarySum);
        BigDecimal salaryCurrentYear = sumDecimal(scopeKey, firstDayOfYear, selectedDate, selectedDate, AnalyticsMetricAggregate::getSalarySum);
        BigDecimal salaryPreviousYear = sumDecimal(scopeKey, firstDayOfPreviousYear, sameDayPreviousYear, selectedDate, AnalyticsMetricAggregate::getSalarySum);
        long salaryCurrentMonthCount = sumLong(scopeKey, firstDayOfMonth, selectedDate, selectedDate, AnalyticsMetricAggregate::getSalaryEntryCount);
        long salaryPreviousMonthCount = sumLong(scopeKey, firstDayOfPreviousMonth, lastDayOfPreviousMonth, selectedDate, AnalyticsMetricAggregate::getSalaryEntryCount);
        long salaryThreeMonthsAgoCount = sumLong(scopeKey, firstDayOfThreeMonthsAgo, lastDayOfThreeMonthsAgo, selectedDate, AnalyticsMetricAggregate::getSalaryEntryCount);

        stats.setSum1Day(salary1Day.intValue());
        stats.setSum1Week(salary7Day.intValue());
        stats.setSum1Month(salaryCurrentMonth.intValue());
        stats.setSum1Year(salaryCurrentYear.intValue());
        stats.setSumOrders1Month(toInt(salaryCurrentMonthCount));
        stats.setSumOrders2Month(toInt(salaryPreviousMonthCount));
        stats.setPercent1Day(calculatePercentageDifference(salary1Day, salary2Day).intValue());
        stats.setPercent1Week(calculatePercentageDifference(salary7Day, salary14Day).intValue());
        stats.setPercent1Month(calculatePercentageDifference(salaryCurrentMonth, salaryPreviousMonth).intValue());
        stats.setPercent1Year(calculatePercentageDifference(salaryCurrentYear, salaryPreviousYear).intValue());
        stats.setPercent1MonthOrders(calculatePercentageDifference(salaryCurrentMonthCount, salaryPreviousMonthCount));
        stats.setPercent2MonthOrders(calculatePercentageDifference(salaryPreviousMonthCount, salaryThreeMonthsAgoCount));

        return Optional.of(stats);
    }

    private String scopeKey(User user, String role) {
        if (ROLE_ADMIN.equals(role)) {
            return AnalyticsAggregateReadService.SCOPE_ADMIN_ALL;
        }
        if (ROLE_OWNER.equals(role)) {
            return AnalyticsAggregateReadService.ownerScopeKey(user.getId());
        }
        return null;
    }

    private BigDecimal sumDecimal(
            String scopeKey,
            LocalDate fromInclusive,
            LocalDate toInclusive,
            LocalDate selectedDate,
            Function<AnalyticsMetricAggregate, BigDecimal> metric
    ) {
        return aggregateRange(scopeKey, fromInclusive, toInclusive, selectedDate)
                .stream()
                .map(metric)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private long sumLong(
            String scopeKey,
            LocalDate fromInclusive,
            LocalDate toInclusive,
            LocalDate selectedDate,
            ToLongFunction<AnalyticsMetricAggregate> metric
    ) {
        return aggregateRange(scopeKey, fromInclusive, toInclusive, selectedDate)
                .stream()
                .mapToLong(metric)
                .sum();
    }

    private List<AnalyticsMetricAggregate> aggregateRange(
            String scopeKey,
            LocalDate fromInclusive,
            LocalDate toInclusive,
            LocalDate selectedDate
    ) {
        AnalyticsAggregateReadService.AggregatePeriod period = readService.splitPeriod(fromInclusive, toInclusive, selectedDate);
        List<AnalyticsMetricAggregate> rows = period.monthlyRanges().stream()
                .flatMap(range -> readService.monthlyTotals(scopeKey, range.fromInclusive(), range.toInclusive()).stream())
                .map(AnalyticsMetricAggregate.class::cast)
                .collect(Collectors.toList());
        period.dailyRanges().stream()
                .flatMap(range -> readService.dailyTotals(scopeKey, range.fromInclusive(), range.toInclusive()).stream())
                .map(AnalyticsMetricAggregate.class::cast)
                .forEach(rows::add);
        return rows;
    }

    private Map<Integer, BigDecimal> dailyMetricMap(
            String scopeKey,
            LocalDate fromInclusive,
            LocalDate toInclusive,
            int daysInMonth,
            Function<AnalyticsMetricAggregate, BigDecimal> metric
    ) {
        Map<Integer, BigDecimal> result = IntStream.rangeClosed(1, daysInMonth)
                .boxed()
                .collect(Collectors.toMap(
                        Function.identity(),
                        ignored -> BigDecimal.ZERO,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        readService.dailyTotals(scopeKey, fromInclusive, toInclusive)
                .forEach(total -> result.merge(total.getMetricDate().getDayOfMonth(), metric.apply(total), BigDecimal::add));
        return result;
    }

    private Map<Integer, Map<Integer, BigDecimal>> monthlyMetricMap(
            String scopeKey,
            LocalDate selectedDate,
            Function<AnalyticsMetricAggregate, BigDecimal> metric
    ) {
        AnalyticsAggregateReadService.AggregatePeriod period = readService.splitPeriod(ANALYTICS_START, selectedDate, selectedDate);
        Map<Integer, Map<Integer, BigDecimal>> result = new TreeMap<>();

        period.monthlyRanges().stream()
                .flatMap(range -> readService.monthlyTotals(scopeKey, range.fromInclusive(), range.toInclusive()).stream())
                .forEach(total -> addMonthlyValue(result, total.getMonthStart(), metric.apply(total)));
        period.dailyRanges().stream()
                .flatMap(range -> readService.dailyTotals(scopeKey, range.fromInclusive(), range.toInclusive()).stream())
                .forEach(total -> addMonthlyValue(result, total.getMetricDate(), metric.apply(total)));

        return result;
    }

    private void addMonthlyValue(Map<Integer, Map<Integer, BigDecimal>> result, LocalDate date, BigDecimal value) {
        result.computeIfAbsent(date.getYear(), ignored -> new TreeMap<>())
                .merge(date.getMonthValue(), value, BigDecimal::add);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize analytics aggregate chart data", exception);
        }
    }

    private BigDecimal calculatePercentageDifference(BigDecimal sum1, BigDecimal sum2) {
        if (isZero(sum1) || isZero(sum2)) {
            return handleZeroValues(sum1, sum2);
        }

        BigDecimal difference = sum1.subtract(sum2);
        BigDecimal baseValue = difference.compareTo(BigDecimal.ZERO) > 0 ? sum1 : sum2;
        return difference.divide(baseValue, 2, RoundingMode.HALF_UP).multiply(ONE_HUNDRED);
    }

    private boolean isZero(BigDecimal value) {
        return value.compareTo(BigDecimal.ZERO) == 0;
    }

    private BigDecimal handleZeroValues(BigDecimal sum1, BigDecimal sum2) {
        if (isZero(sum1) && !isZero(sum2)) {
            return ONE_HUNDRED.negate();
        }
        if (!isZero(sum1) && isZero(sum2)) {
            return ONE_HUNDRED;
        }
        return BigDecimal.ZERO;
    }

    private int calculatePercentageDifference(long sum1, long sum2) {
        if (sum1 == 0 || sum2 == 0) {
            if (sum1 == 0 && sum2 == 0) {
                return 0;
            }
            return sum1 == 0 ? -100 : 100;
        }

        long difference = sum1 - sum2;
        long baseValue = difference > 0 ? sum1 : sum2;
        return BigDecimal.valueOf(difference)
                .divide(BigDecimal.valueOf(baseValue), 2, RoundingMode.HALF_UP)
                .multiply(ONE_HUNDRED)
                .intValue();
    }

    private int toInt(long value) {
        return Math.toIntExact(value);
    }
}
