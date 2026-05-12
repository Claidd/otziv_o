package com.hunt.otziv.analytics.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.admin.dto.personal_stat.UserStatDTO;
import com.hunt.otziv.analytics.model.AnalyticsUserMetricAggregate;
import com.hunt.otziv.u_users.model.Image;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.z_zp.services.ZpService;
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
public class AnalyticsAggregateUserStatsService {

    private static final long DEFAULT_IMAGE_ID = 1L;
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private final AnalyticsAggregateReadService readService;
    private final ObjectMapper objectMapper;
    private final ZpService zpService;

    public Optional<UserStatDTO> buildUserStats(LocalDate selectedDate, User user) {
        if (selectedDate == null || user == null || user.getId() == null) {
            return Optional.empty();
        }

        LocalDate historyStart = selectedDate.minusYears(1).withDayOfYear(1);
        LocalDate firstDayOfMonth = selectedDate.withDayOfMonth(1);
        boolean hasAggregateData = !readService.monthlyUsers(List.of(user.getId()), historyStart, selectedDate).isEmpty()
                || !readService.dailyUsers(List.of(user.getId()), firstDayOfMonth, selectedDate).isEmpty();
        if (!hasAggregateData) {
            return Optional.empty();
        }

        LocalDate firstDayOfPreviousMonth = firstDayOfMonth.minusMonths(1);
        LocalDate firstDayOfTwoMonthsAgo = firstDayOfMonth.minusMonths(2);
        LocalDate lastDayOfPreviousMonth = firstDayOfPreviousMonth.withDayOfMonth(firstDayOfPreviousMonth.lengthOfMonth());
        LocalDate lastDayOfTwoMonthsAgo = firstDayOfTwoMonthsAgo.withDayOfMonth(firstDayOfTwoMonthsAgo.lengthOfMonth());
        LocalDate firstDayOfYear = selectedDate.withDayOfYear(1);
        LocalDate firstDayOfPreviousYear = selectedDate.minusYears(1).withDayOfYear(1);
        LocalDate sameDayPreviousYear = selectedDate.minusYears(1);

        UserStatDTO stats = new UserStatDTO();
        stats.setId(user.getId());
        stats.setFio(user.getFio());
        stats.setImageId(imageId(user));
        stats.setCoefficient(user.getCoefficient());

        stats.setZpPayMap(toJson(dailySalaryMap(user.getId(), firstDayOfMonth, selectedDate, selectedDate.lengthOfMonth())));
        stats.setZpPayMapMonth(toJson(monthlySalaryMap(user.getId(), historyStart, selectedDate)));

        BigDecimal salary1Day = liveSalaryForDay(user.getId(), selectedDate);
        BigDecimal salary2Day = liveSalaryForDay(user.getId(), selectedDate.minusDays(1));
        BigDecimal salary7Day = sumDecimal(user.getId(), selectedDate.minusDays(7), selectedDate, selectedDate, AnalyticsUserMetricAggregate::getSalarySum);
        BigDecimal salary14Day = sumDecimal(user.getId(), selectedDate.minusDays(14), selectedDate.minusDays(8), selectedDate, AnalyticsUserMetricAggregate::getSalarySum);
        BigDecimal salaryCurrentMonth = sumDecimal(user.getId(), firstDayOfMonth, selectedDate, selectedDate, AnalyticsUserMetricAggregate::getSalarySum);
        BigDecimal salaryPreviousMonth = sumDecimal(user.getId(), firstDayOfPreviousMonth, lastDayOfPreviousMonth, selectedDate, AnalyticsUserMetricAggregate::getSalarySum);
        BigDecimal salaryCurrentYear = sumDecimal(user.getId(), firstDayOfYear, selectedDate, selectedDate, AnalyticsUserMetricAggregate::getSalarySum);
        BigDecimal salaryPreviousYear = sumDecimal(user.getId(), firstDayOfPreviousYear, sameDayPreviousYear, selectedDate, AnalyticsUserMetricAggregate::getSalarySum);

        long salaryCurrentMonthCount = sumLong(user.getId(), firstDayOfMonth, selectedDate, selectedDate, AnalyticsUserMetricAggregate::getSalaryEntryCount);
        long salaryPreviousMonthCount = sumLong(user.getId(), firstDayOfPreviousMonth, lastDayOfPreviousMonth, selectedDate, AnalyticsUserMetricAggregate::getSalaryEntryCount);
        long salaryTwoMonthsAgoCount = sumLong(user.getId(), firstDayOfTwoMonthsAgo, lastDayOfTwoMonthsAgo, selectedDate, AnalyticsUserMetricAggregate::getSalaryEntryCount);

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
        stats.setPercent2MonthOrders(calculatePercentageDifference(salaryPreviousMonthCount, salaryTwoMonthsAgoCount));

        return Optional.of(stats);
    }

    private BigDecimal liveSalaryForDay(Long userId, LocalDate date) {
        return zpService.sumByUserAndCreated(userId, date);
    }

    private BigDecimal sumDecimal(
            Long userId,
            LocalDate fromInclusive,
            LocalDate toInclusive,
            LocalDate selectedDate,
            Function<AnalyticsUserMetricAggregate, BigDecimal> metric
    ) {
        return aggregateRange(userId, fromInclusive, toInclusive, selectedDate)
                .stream()
                .map(metric)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private long sumLong(
            Long userId,
            LocalDate fromInclusive,
            LocalDate toInclusive,
            LocalDate selectedDate,
            ToLongFunction<AnalyticsUserMetricAggregate> metric
    ) {
        return aggregateRange(userId, fromInclusive, toInclusive, selectedDate)
                .stream()
                .mapToLong(metric)
                .sum();
    }

    private List<AnalyticsUserMetricAggregate> aggregateRange(
            Long userId,
            LocalDate fromInclusive,
            LocalDate toInclusive,
            LocalDate selectedDate
    ) {
        AnalyticsAggregateReadService.AggregatePeriod period = readService.splitPeriod(fromInclusive, toInclusive, selectedDate);
        List<AnalyticsUserMetricAggregate> rows = period.monthlyRanges().stream()
                .flatMap(range -> readService.monthlyUsers(List.of(userId), range.fromInclusive(), range.toInclusive()).stream())
                .map(AnalyticsUserMetricAggregate.class::cast)
                .collect(Collectors.toList());
        period.dailyRanges().stream()
                .flatMap(range -> readService.dailyUsers(List.of(userId), range.fromInclusive(), range.toInclusive()).stream())
                .map(AnalyticsUserMetricAggregate.class::cast)
                .forEach(rows::add);
        return rows;
    }

    private Map<Integer, BigDecimal> dailySalaryMap(
            Long userId,
            LocalDate fromInclusive,
            LocalDate toInclusive,
            int daysInMonth
    ) {
        Map<Integer, BigDecimal> result = IntStream.rangeClosed(1, daysInMonth)
                .boxed()
                .collect(Collectors.toMap(
                        Function.identity(),
                        ignored -> BigDecimal.ZERO,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        readService.dailyUsers(List.of(userId), fromInclusive, toInclusive)
                .forEach(row -> result.merge(row.getMetricDate().getDayOfMonth(), row.getSalarySum(), BigDecimal::add));
        return result;
    }

    private Map<Integer, Map<Integer, BigDecimal>> monthlySalaryMap(
            Long userId,
            LocalDate fromInclusive,
            LocalDate selectedDate
    ) {
        AnalyticsAggregateReadService.AggregatePeriod period = readService.splitPeriod(fromInclusive, selectedDate, selectedDate);
        Map<Integer, Map<Integer, BigDecimal>> result = new TreeMap<>();

        period.monthlyRanges().stream()
                .flatMap(range -> readService.monthlyUsers(List.of(userId), range.fromInclusive(), range.toInclusive()).stream())
                .filter(this::hasSalaryData)
                .forEach(row -> addMonthlyValue(result, row.getMonthStart(), row.getSalarySum()));
        period.dailyRanges().stream()
                .flatMap(range -> readService.dailyUsers(List.of(userId), range.fromInclusive(), range.toInclusive()).stream())
                .filter(this::hasSalaryData)
                .forEach(row -> addMonthlyValue(result, row.getMetricDate(), row.getSalarySum()));

        return result;
    }

    private boolean hasSalaryData(AnalyticsUserMetricAggregate row) {
        return row.getSalaryEntryCount() > 0 || row.getSalarySum().compareTo(BigDecimal.ZERO) != 0;
    }

    private void addMonthlyValue(Map<Integer, Map<Integer, BigDecimal>> result, LocalDate date, BigDecimal value) {
        result.computeIfAbsent(date.getYear(), ignored -> new TreeMap<>())
                .merge(date.getMonthValue(), value, BigDecimal::add);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize user analytics aggregate data", exception);
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

    private Long imageId(User user) {
        if (user.getImageId() != null) {
            return user.getImageId();
        }
        Image image = user.getImage();
        return image == null || image.getId() == null ? DEFAULT_IMAGE_ID : image.getId();
    }

    private int toInt(long value) {
        return Math.toIntExact(value);
    }
}
