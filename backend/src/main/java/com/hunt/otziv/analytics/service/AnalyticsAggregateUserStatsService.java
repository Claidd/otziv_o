package com.hunt.otziv.analytics.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.admin.dto.personal_stat.UserStatDTO;
import com.hunt.otziv.analytics.service.AnalyticsSalarySourceService.DailySalary;
import com.hunt.otziv.u_users.model.Image;
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
public class AnalyticsAggregateUserStatsService {

    private static final long DEFAULT_IMAGE_ID = 1L;
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private final AnalyticsSalarySourceService salarySourceService;
    private final ObjectMapper objectMapper;

    public Optional<UserStatDTO> buildUserStats(LocalDate selectedDate, User user) {
        if (selectedDate == null || user == null || user.getId() == null) {
            return Optional.empty();
        }

        LocalDate historyStart = selectedDate.minusYears(1).withDayOfYear(1);
        List<DailySalary> salaryRows = salarySourceService.dailyForUsers(
                List.of(user.getId()), historyStart, selectedDate
        );
        if (salaryRows.isEmpty()) {
            return Optional.empty();
        }

        LocalDate firstDayOfMonth = selectedDate.withDayOfMonth(1);
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

        BigDecimal salary1Day = sumDecimal(salaryRows, selectedDate, selectedDate, DailySalary::salarySum);
        BigDecimal salary2Day = sumDecimal(salaryRows, selectedDate.minusDays(1), selectedDate.minusDays(1), DailySalary::salarySum);
        BigDecimal salary7Day = sumDecimal(salaryRows, selectedDate.minusDays(7), selectedDate, DailySalary::salarySum);
        BigDecimal salary14Day = sumDecimal(salaryRows, selectedDate.minusDays(14), selectedDate.minusDays(8), DailySalary::salarySum);
        BigDecimal salaryCurrentMonth = sumDecimal(salaryRows, firstDayOfMonth, selectedDate, DailySalary::salarySum);
        BigDecimal salaryPreviousMonth = sumDecimal(salaryRows, firstDayOfPreviousMonth, lastDayOfPreviousMonth, DailySalary::salarySum);
        BigDecimal salaryCurrentYear = sumDecimal(salaryRows, firstDayOfYear, selectedDate, DailySalary::salarySum);
        BigDecimal salaryPreviousYear = sumDecimal(salaryRows, firstDayOfPreviousYear, sameDayPreviousYear, DailySalary::salarySum);

        long salaryCurrentMonthCount = sumLong(salaryRows, firstDayOfMonth, selectedDate, DailySalary::salaryEntryCount);
        long salaryPreviousMonthCount = sumLong(salaryRows, firstDayOfPreviousMonth, lastDayOfPreviousMonth, DailySalary::salaryEntryCount);
        long salaryTwoMonthsAgoCount = sumLong(salaryRows, firstDayOfTwoMonthsAgo, lastDayOfTwoMonthsAgo, DailySalary::salaryEntryCount);

        stats.setZpPayMap(toJson(dailySalaryMap(salaryRows, firstDayOfMonth, selectedDate, selectedDate.lengthOfMonth())));
        stats.setZpPayMapMonth(toJson(monthlySalaryMap(salaryRows)));

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

    private BigDecimal sumDecimal(
            List<DailySalary> rows,
            LocalDate fromInclusive,
            LocalDate toInclusive,
            Function<DailySalary, BigDecimal> metric
    ) {
        return rows.stream()
                .filter(row -> includes(fromInclusive, toInclusive, row.metricDate()))
                .map(metric)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private long sumLong(
            List<DailySalary> rows,
            LocalDate fromInclusive,
            LocalDate toInclusive,
            ToLongFunction<DailySalary> metric
    ) {
        return rows.stream()
                .filter(row -> includes(fromInclusive, toInclusive, row.metricDate()))
                .mapToLong(metric)
                .sum();
    }

    private Map<Integer, BigDecimal> dailySalaryMap(
            List<DailySalary> rows,
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
        rows.stream()
                .filter(row -> includes(fromInclusive, toInclusive, row.metricDate()))
                .forEach(row -> result.merge(row.metricDate().getDayOfMonth(), row.salarySum(), BigDecimal::add));
        return result;
    }

    private Map<Integer, Map<Integer, BigDecimal>> monthlySalaryMap(List<DailySalary> rows) {
        Map<Integer, Map<Integer, BigDecimal>> result = new TreeMap<>();
        rows.stream()
                .filter(row -> row.salaryEntryCount() > 0 || row.salarySum().compareTo(BigDecimal.ZERO) != 0)
                .forEach(row -> result.computeIfAbsent(row.metricDate().getYear(), ignored -> new TreeMap<>())
                        .merge(row.metricDate().getMonthValue(), row.salarySum(), BigDecimal::add));
        return result;
    }

    private boolean includes(LocalDate fromInclusive, LocalDate toInclusive, LocalDate date) {
        return !date.isBefore(fromInclusive) && !date.isAfter(toInclusive);
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
