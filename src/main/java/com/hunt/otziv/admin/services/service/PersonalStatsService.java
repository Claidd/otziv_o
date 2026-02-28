package com.hunt.otziv.admin.services.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.admin.dto.personal_stat.StatDTO;
import com.hunt.otziv.admin.dto.personal_stat.UserStatDTO;
import com.hunt.otziv.l_lead.services.serv.LeadService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.z_zp.model.PaymentCheck;
import com.hunt.otziv.z_zp.model.Zp;
import com.hunt.otziv.z_zp.services.PaymentCheckService;
import com.hunt.otziv.z_zp.services.ZpService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
@Slf4j
@RequiredArgsConstructor
public class PersonalStatsService {

    private final ZpService zpService;
    private final PaymentCheckService paymentCheckService;
    private final LeadService leadService;
    private final ObjectMapper objectMapper;

    private static final String ROLE_ADMIN = "ROLE_ADMIN";
    private static final String ROLE_OWNER = "ROLE_OWNER";
    private static final String STATUS_IN_WORK = "В работе";
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
    private static final int PERCENT_MULTIPLIER = 100;
    private static final long DEFAULT_IMAGE_ID = 1L;

    @Transactional
    public StatDTO getStats(LocalDate localDate, User user, String role) {
        Set<Manager> managerList = user.getManagers() != null ? user.getManagers() : Collections.emptySet();

        List<PaymentCheck> pcs = getPaymentChecks(localDate, role, managerList);
        List<Zp> zps = getZarplataChecks(localDate, role, managerList);

        List<Long> newLeadList = getNewLeadList(role, localDate, managerList);
        List<Long> inWorkLeadList = getInWorkLeadList(role, localDate, managerList);

        List<Long> newLeadListPrevMonth = getNewLeadList(role, localDate.minusMonths(1), managerList);
        List<Long> inWorkLeadListPrevMonth = getInWorkLeadList(role, localDate.minusMonths(1), managerList);

        PeriodMetrics paymentMetrics = calculatePeriodMetrics(
                pcs,
                PaymentCheck::getCreated,
                PaymentCheck::getSum,
                localDate
        );

        PeriodMetrics zpMetrics = calculatePeriodMetrics(
                zps,
                Zp::getCreated,
                Zp::getSum,
                localDate
        );

        StatDTO statDTO = new StatDTO();

        statDTO.setOrderPayMap(toJson(getDailySumMap(localDate, pcs, PaymentCheck::getCreated, PaymentCheck::getSum)));
        statDTO.setOrderPayMapMonth(toJson(getYearlyMonthlySumMap(pcs, PaymentCheck::getCreated, PaymentCheck::getSum)));
        statDTO.setZpPayMapMonth(toJson(getYearlyMonthlySumMap(zps, Zp::getCreated, Zp::getSum)));

        statDTO.setSum1DayPay(paymentMetrics.getDay1Sum().intValue());
        statDTO.setSum1WeekPay(paymentMetrics.getWeek1Sum().intValue());
        statDTO.setSum1MonthPay(paymentMetrics.getMonth1Sum().intValue());
        statDTO.setSum1YearPay(paymentMetrics.getYear1Sum().intValue());

        statDTO.setSumOrders1MonthPay(paymentMetrics.getMonth1Count());
        statDTO.setSumOrders2MonthPay(paymentMetrics.getMonth2Count());

        statDTO.setPercent1DayPay(calculatePercentageDifference(paymentMetrics.getDay1Sum(), paymentMetrics.getDay2Sum()).intValue());
        statDTO.setPercent1WeekPay(calculatePercentageDifference(paymentMetrics.getWeek1Sum(), paymentMetrics.getWeek2Sum()).intValue());
        statDTO.setPercent1MonthPay(calculatePercentageDifference(paymentMetrics.getMonth1Sum(), paymentMetrics.getMonth2Sum()).intValue());
        statDTO.setPercent1YearPay(calculatePercentageDifference(paymentMetrics.getYear1Sum(), paymentMetrics.getYear2Sum()).intValue());
        statDTO.setPercent1MonthOrdersPay(calculatePercentageDifference(paymentMetrics.getMonth1Count(), paymentMetrics.getMonth2Count()));
        statDTO.setPercent2MonthOrdersPay(calculatePercentageDifference(paymentMetrics.getMonth2Count(), paymentMetrics.getMonth3Count()));

        statDTO.setNewLeads(newLeadList.size());
        statDTO.setLeadsInWork(inWorkLeadList.size());
        statDTO.setPercent1NewLeadsPay(calculatePercentageDifference(newLeadList.size(), newLeadListPrevMonth.size()));
        statDTO.setPercent2InWorkLeadsPay(calculatePercentageDifference(inWorkLeadList.size(), inWorkLeadListPrevMonth.size()));

        statDTO.setZpPayMap(toJson(getDailySumMap(localDate, zps, Zp::getCreated, Zp::getSum)));
        statDTO.setSum1Day(zpMetrics.getDay1Sum().intValue());
        statDTO.setSum1Week(zpMetrics.getWeek1Sum().intValue());
        statDTO.setSum1Month(zpMetrics.getMonth1Sum().intValue());
        statDTO.setSum1Year(zpMetrics.getYear1Sum().intValue());

        statDTO.setSumOrders1Month(zpMetrics.getMonth1Count());
        statDTO.setSumOrders2Month(zpMetrics.getMonth2Count());

        statDTO.setPercent1Day(calculatePercentageDifference(zpMetrics.getDay1Sum(), zpMetrics.getDay2Sum()).intValue());
        statDTO.setPercent1Week(calculatePercentageDifference(zpMetrics.getWeek1Sum(), zpMetrics.getWeek2Sum()).intValue());
        statDTO.setPercent1Month(calculatePercentageDifference(zpMetrics.getMonth1Sum(), zpMetrics.getMonth2Sum()).intValue());
        statDTO.setPercent1Year(calculatePercentageDifference(zpMetrics.getYear1Sum(), zpMetrics.getYear2Sum()).intValue());
        statDTO.setPercent1MonthOrders(calculatePercentageDifference(zpMetrics.getMonth1Count(), zpMetrics.getMonth2Count()));
        statDTO.setPercent2MonthOrders(calculatePercentageDifference(zpMetrics.getMonth2Count(), zpMetrics.getMonth3Count()));

        return statDTO;
    }

    public UserStatDTO getWorkerReviews(User user, LocalDate localDate) {
        List<Zp> zps = zpService.findAllToDateByUser(localDate, user.getId());

        PeriodMetrics metrics = calculatePeriodMetrics(
                zps,
                Zp::getCreated,
                Zp::getSum,
                localDate
        );

        UserStatDTO userStatDTO = new UserStatDTO();
        userStatDTO.setId(user.getId());
        userStatDTO.setImageId(resolveImageId(user));
        userStatDTO.setFio(user.getFio());
        userStatDTO.setCoefficient(user.getCoefficient());

        userStatDTO.setZpPayMap(toJson(getDailySumMap(localDate, zps, Zp::getCreated, Zp::getSum)));
        userStatDTO.setZpPayMapMonth(toJson(getYearlyMonthlySumMap(zps, Zp::getCreated, Zp::getSum)));

        userStatDTO.setSum1Day(metrics.getDay1Sum().intValue());
        userStatDTO.setSum1Week(metrics.getWeek1Sum().intValue());
        userStatDTO.setSum1Month(metrics.getMonth1Sum().intValue());
        userStatDTO.setSum1Year(metrics.getYear1Sum().intValue());

        userStatDTO.setSumOrders1Month(metrics.getMonth1Count());
        userStatDTO.setSumOrders2Month(metrics.getMonth2Count());

        userStatDTO.setPercent1Day(calculatePercentageDifference(metrics.getDay1Sum(), metrics.getDay2Sum()).intValue());
        userStatDTO.setPercent1Week(calculatePercentageDifference(metrics.getWeek1Sum(), metrics.getWeek2Sum()).intValue());
        userStatDTO.setPercent1Month(calculatePercentageDifference(metrics.getMonth1Sum(), metrics.getMonth2Sum()).intValue());
        userStatDTO.setPercent1Year(calculatePercentageDifference(metrics.getYear1Sum(), metrics.getYear2Sum()).intValue());
        userStatDTO.setPercent1MonthOrders(calculatePercentageDifference(metrics.getMonth1Count(), metrics.getMonth2Count()));
        userStatDTO.setPercent2MonthOrders(calculatePercentageDifference(metrics.getMonth2Count(), metrics.getMonth3Count()));

        return userStatDTO;
    }

    private List<PaymentCheck> getPaymentChecks(LocalDate localDate, String role, Set<Manager> managerList) {
        return checkRoleAndExecute(
                role,
                () -> paymentCheckService.findAllToDate(localDate),
                owner -> paymentCheckService.findAllToDateByOwner(localDate, owner),
                managerList
        );
    }

    private List<Zp> getZarplataChecks(LocalDate localDate, String role, Set<Manager> managerList) {
        return checkRoleAndExecute(
                role,
                () -> zpService.findAllToDate(localDate),
                owner -> zpService.findAllToDateByOwner(localDate, owner),
                managerList
        );
    }

    private List<Long> getInWorkLeadList(String role, LocalDate localDate, Set<Manager> managerList) {
        return checkRoleAndExecute(
                role,
                () -> leadService.getAllLeadsByDateAndStatus(localDate, STATUS_IN_WORK),
                owner -> leadService.getAllLeadsByDateAndStatusToOwner(localDate, STATUS_IN_WORK, owner),
                managerList
        );
    }

    private List<Long> getNewLeadList(String role, LocalDate localDate, Set<Manager> managerList) {
        return checkRoleAndExecute(
                role,
                () -> leadService.getAllLeadsByDate(localDate),
                owner -> leadService.getAllLeadsByDateToOwner(localDate, owner),
                managerList
        );
    }

    private <T> List<T> checkRoleAndExecute(
            String role,
            Supplier<List<T>> adminFunction,
            Function<Set<Manager>, List<T>> ownerFunction,
            Set<Manager> managerList
    ) {
        if (ROLE_ADMIN.equals(role)) {
            return adminFunction.get();
        }
        if (ROLE_OWNER.equals(role)) {
            return ownerFunction.apply(managerList);
        }
        return adminFunction.get();
    }

    private <T> Map<Integer, BigDecimal> getDailySumMap(
            LocalDate targetDate,
            List<T> items,
            Function<T, LocalDate> dateExtractor,
            Function<T, BigDecimal> sumExtractor
    ) {
        Map<Integer, BigDecimal> result = IntStream.rangeClosed(1, targetDate.lengthOfMonth())
                .boxed()
                .collect(Collectors.toMap(
                        Function.identity(),
                        day -> BigDecimal.ZERO,
                        (a, b) -> a,
                        LinkedHashMap::new
                ));

        if (items == null || items.isEmpty()) {
            return result;
        }

        for (T item : items) {
            LocalDate created = dateExtractor.apply(item);
            if (created.getYear() == targetDate.getYear() && created.getMonth() == targetDate.getMonth()) {
                result.merge(created.getDayOfMonth(), sumExtractor.apply(item), BigDecimal::add);
            }
        }

        return result;
    }

    private <T> Map<Integer, Map<Integer, BigDecimal>> getYearlyMonthlySumMap(
            List<T> items,
            Function<T, LocalDate> dateExtractor,
            Function<T, BigDecimal> sumExtractor
    ) {
        Map<Integer, Map<Integer, BigDecimal>> result = new HashMap<>();

        if (items == null || items.isEmpty()) {
            return result;
        }

        for (T item : items) {
            LocalDate created = dateExtractor.apply(item);
            int year = created.getYear();
            int month = created.getMonthValue();

            result.computeIfAbsent(year, y -> new HashMap<>())
                    .merge(month, sumExtractor.apply(item), BigDecimal::add);
        }

        return result;
    }

    private String toJson(Object source) {
        try {
            return objectMapper.writeValueAsString(source);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Произошла ошибка при преобразовании данных в JSON", e);
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
        return value == null || value.compareTo(BigDecimal.ZERO) == 0;
    }

    private BigDecimal handleZeroValues(BigDecimal sum1, BigDecimal sum2) {
        if (isZero(sum1) && !isZero(sum2)) {
            return ONE_HUNDRED.negate();
        } else if (!isZero(sum1) && isZero(sum2)) {
            return ONE_HUNDRED;
        } else {
            return BigDecimal.ZERO;
        }
    }

    private int calculatePercentageDifference(int sum1, int sum2) {
        if (sum1 == 0 || sum2 == 0) {
            return handleZeroSumCase(sum1, sum2);
        }
        return computePercentageDifference(sum1, sum2);
    }

    private int computePercentageDifference(int sum1, int sum2) {
        int difference = sum1 - sum2;

        if (difference == 0) {
            return 0;
        }

        int denominator = difference > 0 ? sum1 : sum2;
        return difference * PERCENT_MULTIPLIER / Math.abs(denominator);
    }

    private int handleZeroSumCase(int sum1, int sum2) {
        if (sum1 == 0 && sum2 == 0) {
            return 0;
        }
        if (sum1 == 0) {
            return sum2 > 0 ? -PERCENT_MULTIPLIER : 0;
        }
        return PERCENT_MULTIPLIER;
    }

    private long resolveImageId(User user) {
        return user.getImage() != null ? user.getImage().getId() : DEFAULT_IMAGE_ID;
    }

    private <T> PeriodMetrics calculatePeriodMetrics(
            List<T> items,
            Function<T, LocalDate> dateExtractor,
            Function<T, BigDecimal> sumExtractor,
            LocalDate now
    ) {
        PeriodBoundaries boundaries = PeriodBoundaries.of(now);
        PeriodMetrics metrics = new PeriodMetrics();

        if (items == null || items.isEmpty()) {
            return metrics;
        }

        for (T item : items) {
            LocalDate created = dateExtractor.apply(item);
            BigDecimal sum = sumExtractor.apply(item);

            if (created.isEqual(boundaries.previousDay)) {
                metrics.day1Sum = metrics.day1Sum.add(sum);
            }

            if (created.isEqual(boundaries.twoDaysAgo)) {
                metrics.day2Sum = metrics.day2Sum.add(sum);
            }

            if (!created.isBefore(boundaries.last7DaysStart) && !created.isAfter(boundaries.today)) {
                metrics.week1Sum = metrics.week1Sum.add(sum);
            }

            if (!created.isBefore(boundaries.previous7DaysStart) && created.isBefore(boundaries.last7DaysStart)) {
                metrics.week2Sum = metrics.week2Sum.add(sum);
            }

            if (!created.isBefore(boundaries.currentMonthStart) && !created.isAfter(boundaries.currentMonthEnd)) {
                metrics.month1Sum = metrics.month1Sum.add(sum);
                metrics.month1Count++;
            }

            if (!created.isBefore(boundaries.previousMonthStart) && !created.isAfter(boundaries.previousMonthEnd)) {
                metrics.month2Sum = metrics.month2Sum.add(sum);
                metrics.month2Count++;
            }

            if (!created.isBefore(boundaries.threeMonthsAgoStart) && !created.isAfter(boundaries.threeMonthsAgoEnd)) {
                metrics.month3Sum = metrics.month3Sum.add(sum);
                metrics.month3Count++;
            }

            if (!created.isBefore(boundaries.currentYearStart) && !created.isAfter(boundaries.today)) {
                metrics.year1Sum = metrics.year1Sum.add(sum);
            }

            if (!created.isBefore(boundaries.previousYearStart) && !created.isAfter(boundaries.previousYearSameDate)) {
                metrics.year2Sum = metrics.year2Sum.add(sum);
            }
        }

        return metrics;
    }

    private static final class PeriodBoundaries {
        private final LocalDate today;
        private final LocalDate previousDay;
        private final LocalDate twoDaysAgo;
        private final LocalDate last7DaysStart;
        private final LocalDate previous7DaysStart;
        private final LocalDate currentMonthStart;
        private final LocalDate currentMonthEnd;
        private final LocalDate previousMonthStart;
        private final LocalDate previousMonthEnd;
        private final LocalDate threeMonthsAgoStart;
        private final LocalDate threeMonthsAgoEnd;
        private final LocalDate currentYearStart;
        private final LocalDate previousYearStart;
        private final LocalDate previousYearSameDate;

        private PeriodBoundaries(
                LocalDate today,
                LocalDate previousDay,
                LocalDate twoDaysAgo,
                LocalDate last7DaysStart,
                LocalDate previous7DaysStart,
                LocalDate currentMonthStart,
                LocalDate currentMonthEnd,
                LocalDate previousMonthStart,
                LocalDate previousMonthEnd,
                LocalDate threeMonthsAgoStart,
                LocalDate threeMonthsAgoEnd,
                LocalDate currentYearStart,
                LocalDate previousYearStart,
                LocalDate previousYearSameDate
        ) {
            this.today = today;
            this.previousDay = previousDay;
            this.twoDaysAgo = twoDaysAgo;
            this.last7DaysStart = last7DaysStart;
            this.previous7DaysStart = previous7DaysStart;
            this.currentMonthStart = currentMonthStart;
            this.currentMonthEnd = currentMonthEnd;
            this.previousMonthStart = previousMonthStart;
            this.previousMonthEnd = previousMonthEnd;
            this.threeMonthsAgoStart = threeMonthsAgoStart;
            this.threeMonthsAgoEnd = threeMonthsAgoEnd;
            this.currentYearStart = currentYearStart;
            this.previousYearStart = previousYearStart;
            this.previousYearSameDate = previousYearSameDate;
        }

        private static PeriodBoundaries of(LocalDate now) {
            LocalDate currentMonthStart = now.withDayOfMonth(1);
            LocalDate previousMonthStart = currentMonthStart.minusMonths(1);
            LocalDate threeMonthsAgoStart = currentMonthStart.minusMonths(3);

            return new PeriodBoundaries(
                    now,
                    now.minusDays(1),
                    now.minusDays(2),
                    now.minusDays(7),
                    now.minusDays(14),
                    currentMonthStart,
                    currentMonthStart.withDayOfMonth(currentMonthStart.lengthOfMonth()),
                    previousMonthStart,
                    previousMonthStart.withDayOfMonth(previousMonthStart.lengthOfMonth()),
                    threeMonthsAgoStart,
                    threeMonthsAgoStart.withDayOfMonth(threeMonthsAgoStart.lengthOfMonth()),
                    now.withDayOfYear(1),
                    now.minusYears(1).withDayOfYear(1),
                    now.minusYears(1)
            );
        }
    }

    private static final class PeriodMetrics {
        private BigDecimal day1Sum = BigDecimal.ZERO;
        private BigDecimal day2Sum = BigDecimal.ZERO;
        private BigDecimal week1Sum = BigDecimal.ZERO;
        private BigDecimal week2Sum = BigDecimal.ZERO;
        private BigDecimal month1Sum = BigDecimal.ZERO;
        private BigDecimal month2Sum = BigDecimal.ZERO;
        private BigDecimal month3Sum = BigDecimal.ZERO;
        private BigDecimal year1Sum = BigDecimal.ZERO;
        private BigDecimal year2Sum = BigDecimal.ZERO;

        private int month1Count = 0;
        private int month2Count = 0;
        private int month3Count = 0;

        public BigDecimal getDay1Sum() {
            return day1Sum;
        }

        public BigDecimal getDay2Sum() {
            return day2Sum;
        }

        public BigDecimal getWeek1Sum() {
            return week1Sum;
        }

        public BigDecimal getWeek2Sum() {
            return week2Sum;
        }

        public BigDecimal getMonth1Sum() {
            return month1Sum;
        }

        public BigDecimal getMonth2Sum() {
            return month2Sum;
        }

        public BigDecimal getMonth3Sum() {
            return month3Sum;
        }

        public BigDecimal getYear1Sum() {
            return year1Sum;
        }

        public BigDecimal getYear2Sum() {
            return year2Sum;
        }

        public int getMonth1Count() {
            return month1Count;
        }

        public int getMonth2Count() {
            return month2Count;
        }

        public int getMonth3Count() {
            return month3Count;
        }
    }
}
