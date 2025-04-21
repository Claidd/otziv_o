package com.hunt.otziv.admin.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.admin.dto.personal_stat.StatDTO;
import com.hunt.otziv.admin.dto.personal_stat.UserLKDTO;
import com.hunt.otziv.admin.dto.personal_stat.UserStatDTO;
import com.hunt.otziv.admin.dto.presonal.*;
import com.hunt.otziv.admin.model.Quadruple;
import com.hunt.otziv.c_companies.services.CompanyService;
import com.hunt.otziv.l_lead.services.LeadService;
import com.hunt.otziv.p_products.dto.OrderDTOList;
import com.hunt.otziv.p_products.services.service.OrderService;
import com.hunt.otziv.r_review.services.ReviewService;
import com.hunt.otziv.u_users.dto.RegistrationUserDTO;
import com.hunt.otziv.u_users.model.*;
import com.hunt.otziv.u_users.services.service.*;
import com.hunt.otziv.z_zp.model.PaymentCheck;
import com.hunt.otziv.z_zp.model.Zp;
import com.hunt.otziv.z_zp.services.PaymentCheckService;
import com.hunt.otziv.z_zp.services.ZpService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Triple;
import org.springframework.data.domain.Page;
import org.springframework.data.util.Pair;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.Principal;
import java.time.LocalDate;
import java.time.Month;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

@Service
@Slf4j
@RequiredArgsConstructor
public class PersonalServiceImpl implements PersonalService {
    private final ManagerService managerService;
    private final MarketologService marketologService;
    private final WorkerService workerService;
    private final OperatorService operatorService;
    private final ZpService zpService;
    private final PaymentCheckService paymentCheckService;
    private final UserService userService;
    private final LeadService leadService;
    private final ReviewService reviewService;
    private final OrderService orderService;
    private final CompanyService companyService;
    private final ImageService imageService;

    private static final String ROLE_ADMIN = "ROLE_ADMIN";
    private static final String ROLE_OWNER = "ROLE_OWNER";
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
    private static final int PERCENT_MULTIPLIER = 100;

    public UserLKDTO getUserLK(Principal principal){
        User user = userService.findByUserName(principal.getName()).orElseThrow();
        Long imageId = user.getImage() != null ? user.getImage().getId() : 1L;
        UserLKDTO userLKDTO = new UserLKDTO();
        userLKDTO.setUsername(user.getUsername());
        userLKDTO.setRole(user.getRoles().iterator().next().getAuthority().substring("ROLE_".length()));
        userLKDTO.setImage(imageId);
        userLKDTO.setLeadCount(leadService.findAllByLidListStatus(principal.getName()).size());
        userLKDTO.setReviewCount(reviewService.findAllByReviewListStatus(principal.getName()));
        return userLKDTO;
    }

//    ========================================== PERSONAL STAT START ==================================================
@Transactional
    public StatDTO getStats(LocalDate localDate, User user, String role) {
//        User user = userService.findByUserName(principal.getName()).orElseThrow();
        User user1 = userService.findByUserName(user.getUsername()).orElseThrow();
        Set<Manager> managerList = user1.getManagers();
//        System.out.println(managerList);

        //      СТАТИСТИКА берем все чеки и зп
        List<PaymentCheck> pcs = getPaymentChecks(localDate, role, managerList);
        List<Zp> zps = getZarplataChecks(localDate, role, managerList);


        //      СТАТИСТИКА новых лидов и тех, что поступили в работу
        List<Long> newleadList = getNewLeadList(role, localDate, managerList); // берем всех лидов за текущий месяц
        List<Long> inWorkleadList = getInWorkLeadList(role, localDate, managerList);// берем всех лидов за текущий месяц + статус
        List<Long> newleadList2Month = getNewLeadList(role, localDate.minusMonths(1), managerList);// берем всех лидов за текущий месяц
        List<Long> inWorkleadList2Month = getInWorkLeadList(role, localDate.minusMonths(1), managerList); // берем всех лидов за текущий месяц + статус;

        //        выбираем даты месяца
        LocalDate firstDayOfMonth = localDate.withDayOfMonth(1);
        LocalDate firstDayOfMonthAgo = firstDayOfMonth.minusMonths(1).withDayOfMonth(1);
        LocalDate firstDayOf3MonthAgo = firstDayOfMonth.minusMonths(3).withDayOfMonth(1);
        LocalDate lastDayOfMonth = localDate.withDayOfMonth(localDate.lengthOfMonth());
        LocalDate lastDayOfMonthAgo = firstDayOfMonthAgo.withDayOfMonth(firstDayOfMonthAgo.lengthOfMonth());
        LocalDate lastDayOf3MonthAgo = firstDayOf3MonthAgo.withDayOfMonth(firstDayOf3MonthAgo.lengthOfMonth());
        //        выбираем даты текущего года
        LocalDate firstDayOfYear = localDate.withDayOfYear(1);
        //        выбираем даты прошлого года
        LocalDate firstDayOf1YearAgo = localDate.minusYears(1).withDayOfYear(1);

        //        ОПЛАТЫ Разбивка на списки 1-2-7-14-30-60-90-360-730 дней от текущей даты
        List<PaymentCheck> Pay1Day = pcs.stream().filter(p -> p.getCreated().isEqual(localDate.minusDays(1))).toList();
        List<PaymentCheck> Pay2Day = pcs.stream().filter(p -> p.getCreated().isEqual(localDate.minusDays(2))).toList();
        List<PaymentCheck> Pay7Day = pcs.stream().filter(p -> p.getCreated().isEqual(localDate.minusDays(7)) || p.getCreated().isAfter(localDate.minusDays(7))).toList();
        List<PaymentCheck> Pay14Day = pcs.stream().filter(p -> (p.getCreated().isEqual(localDate.minusDays(14)) || p.getCreated().isAfter(localDate.minusDays(14))) && p.getCreated().isBefore(localDate.minusDays(7))).toList();
        List<PaymentCheck> Pay30Day = pcs.stream().filter(p -> (p.getCreated().isEqual(firstDayOfMonth) || p.getCreated().isAfter(firstDayOfMonth)) && (p.getCreated().isEqual(lastDayOfMonth) || p.getCreated().isBefore(lastDayOfMonth))).toList();
        List<PaymentCheck> Pay60Day = pcs.stream().filter(p -> (p.getCreated().isEqual(firstDayOfMonthAgo) || p.getCreated().isAfter(firstDayOfMonthAgo)) && (p.getCreated().isEqual(lastDayOfMonthAgo) || p.getCreated().isBefore(lastDayOfMonthAgo))).toList();
        List<PaymentCheck> Pay90Day = pcs.stream().filter(p -> (p.getCreated().isEqual(firstDayOf3MonthAgo) || p.getCreated().isAfter(firstDayOf3MonthAgo)) && (p.getCreated().isEqual(lastDayOf3MonthAgo) || p.getCreated().isBefore(lastDayOf3MonthAgo))).toList();
        List<PaymentCheck> Pay365Day = pcs.stream().filter(p -> (p.getCreated().isEqual(firstDayOfYear) || p.getCreated().isAfter(firstDayOfYear)) && (p.getCreated().isEqual(localDate) || p.getCreated().isBefore(localDate))).toList();
        List<PaymentCheck> Pay730Day = pcs.stream().filter(p -> (p.getCreated().isEqual(firstDayOf1YearAgo) || p.getCreated().isAfter(firstDayOf1YearAgo)) && (p.getCreated().isEqual(localDate.minusYears(1)) || p.getCreated().isBefore(localDate.minusYears(1)))).toList();


        //        ОПЛАТЫ Сумма всех выплат за 1-2-7-14-30-60-90-360-730 дней
        BigDecimal sum1Pay = Pay1Day.stream().map(PaymentCheck::getSum).reduce(BigDecimal.ZERO, BigDecimal::add); // первая сумма
        BigDecimal sum7Pay = Pay7Day.stream().map(PaymentCheck::getSum).reduce(BigDecimal.ZERO, BigDecimal::add); // первая сумма
        BigDecimal sum30Pay = Pay30Day.stream().map(PaymentCheck::getSum).reduce(BigDecimal.ZERO, BigDecimal::add); // первая сумма
        BigDecimal sum365Pay = Pay365Day.stream().map(PaymentCheck::getSum).reduce(BigDecimal.ZERO, BigDecimal::add); // первая сумма

        BigDecimal sum2Pay = Pay2Day.stream().map(PaymentCheck::getSum).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sum14Pay = Pay14Day.stream().map(PaymentCheck::getSum).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sum60Pay = Pay60Day.stream().map(PaymentCheck::getSum).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sum730Pay = Pay730Day.stream().map(PaymentCheck::getSum).reduce(BigDecimal.ZERO, BigDecimal::add);


        //        ОПЛАТЫ Сумма всех заказов за 30-60-90 дней
        BigDecimal sumCount1MonthPay = BigDecimal.valueOf(Pay30Day.size()); // 1 сумма
        BigDecimal sumCount2MonthPay = BigDecimal.valueOf(Pay60Day.size()); // 2 сумма
        BigDecimal sumCount3MonthPay = BigDecimal.valueOf(Pay90Day.size()); // 3 сумма


        //        ЗП Разбивка на списки 1-2-7-14-30-60-90-360-730 дней от текущей даты
        List<Zp> zpPay1Day = zps.stream().filter(z -> z.getCreated().isEqual(localDate.minusDays(1))).toList();
        List<Zp> zpPay2Day = zps.stream().filter(z -> z.getCreated().isEqual(localDate.minusDays(2))).toList();
        List<Zp> zpPay7Day = zps.stream().filter(z -> z.getCreated().isEqual(localDate.minusDays(7)) || z.getCreated().isAfter(localDate.minusDays(7))).toList();
        List<Zp> zpPay14Day = zps.stream().filter(z -> (z.getCreated().isEqual(localDate.minusDays(14)) || z.getCreated().isAfter(localDate.minusDays(14))) && z.getCreated().isBefore(localDate.minusDays(7))).toList();
        List<Zp> zpPay30Day = zps.stream().filter(z -> (z.getCreated().isEqual(firstDayOfMonth) || z.getCreated().isAfter(firstDayOfMonth)) && (z.getCreated().isEqual(lastDayOfMonth) || z.getCreated().isBefore(lastDayOfMonth))).toList();
        List<Zp> zpPay60Day = zps.stream().filter(z -> (z.getCreated().isEqual(firstDayOfMonthAgo) || z.getCreated().isAfter(firstDayOfMonthAgo)) && (z.getCreated().isEqual(lastDayOfMonthAgo) || z.getCreated().isBefore(lastDayOfMonthAgo))).toList();
        List<Zp> zpPay90Day = zps.stream().filter(z -> (z.getCreated().isEqual(firstDayOf3MonthAgo) || z.getCreated().isAfter(firstDayOf3MonthAgo)) && (z.getCreated().isEqual(lastDayOf3MonthAgo) || z.getCreated().isBefore(lastDayOf3MonthAgo))).toList();
        List<Zp> zpPay365Day = zps.stream().filter(z -> (z.getCreated().isEqual(firstDayOfYear) || z.getCreated().isAfter(firstDayOfYear)) && (z.getCreated().isEqual(localDate) || z.getCreated().isBefore(localDate))).toList();
        List<Zp> zpPay730Day = zps.stream().filter(z -> (z.getCreated().isEqual(firstDayOf1YearAgo) || z.getCreated().isAfter(firstDayOf1YearAgo)) && (z.getCreated().isEqual(localDate.minusYears(1)) || z.getCreated().isBefore(localDate.minusYears(1)))).toList();



        //        ЗП Сумма всех выплат за 1-2-7-14-30-60-90-360-730 дней
        BigDecimal sum1 = zpPay1Day.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add); // первая сумма
        BigDecimal sum7 = zpPay7Day.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add); // первая сумма
        BigDecimal sum30 = zpPay30Day.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add); // первая сумма
        BigDecimal sum365 = zpPay365Day.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add); // первая сумма
        BigDecimal sum2 = zpPay2Day.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sum14 = zpPay14Day.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sum60 = zpPay60Day.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sum730 = zpPay730Day.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add);

        //        ЗП Сумма всех заказов за 30-60-90 дней
        BigDecimal sumCount1Month = BigDecimal.valueOf(zpPay30Day.size()); // 1 сумма
        BigDecimal sumCount2Month = BigDecimal.valueOf(zpPay60Day.size()); // 2 сумма
        BigDecimal sumCount3Month = BigDecimal.valueOf(zpPay90Day.size()); // 3 сумма

        Long imageId = 1L;
        StatDTO statDTO = new StatDTO();
        statDTO.setOrderPayMap(getJSON(getDailySalarySumMap(localDate, pcs)));
        statDTO.setOrderPayMapMonth(getJSONMonth(getYearlyMonthlySalarySumMap(pcs)));
        statDTO.setZpPayMapMonth(getJSONMonth(getYearlyMonthlyZpSumMap(zps)));

        statDTO.setSum1DayPay(sum1Pay.intValue());
        statDTO.setSum1WeekPay(sum7Pay.intValue());
        statDTO.setSum1MonthPay(sum30Pay.intValue());
        statDTO.setSum1YearPay(sum365Pay.intValue());
        statDTO.setSumOrders1MonthPay(Pay30Day.size());
        statDTO.setSumOrders2MonthPay(Pay60Day.size());
        statDTO.setPercent1DayPay(calculatePercentageDifference(sum1Pay, sum2Pay).intValue());
        statDTO.setPercent1WeekPay(calculatePercentageDifference(sum7Pay, sum14Pay).intValue());
        statDTO.setPercent1MonthPay(calculatePercentageDifference(sum30Pay, sum60Pay).intValue());
        statDTO.setPercent1YearPay(calculatePercentageDifference(sum365Pay, sum730Pay).intValue());
        statDTO.setPercent1MonthOrdersPay(calculatePercentageDifference(sumCount1MonthPay, sumCount2MonthPay).intValue());
        statDTO.setPercent2MonthOrdersPay(calculatePercentageDifference(sumCount2MonthPay, sumCount3MonthPay).intValue());
        statDTO.setNewLeads(newleadList.size());
        statDTO.setLeadsInWork(inWorkleadList.size());
        statDTO.setPercent1NewLeadsPay(calculatePercentageDifference(newleadList.size(), newleadList2Month.size()));
        statDTO.setPercent2InWorkLeadsPay(calculatePercentageDifference(inWorkleadList.size(), inWorkleadList2Month.size()));

        statDTO.setZpPayMap(getJSON(calculateDailyZpSumForMonth(localDate, zps)));
        statDTO.setSum1Day(sum1.intValue());
        statDTO.setSum1Week(sum7.intValue());
        statDTO.setSum1Month(sum30.intValue());
        statDTO.setSum1Year(sum365.intValue());
        statDTO.setSumOrders1Month(zpPay30Day.size());
        statDTO.setSumOrders2Month(zpPay60Day.size());
        statDTO.setPercent1Day(calculatePercentageDifference(sum1, sum2).intValue());
        statDTO.setPercent1Week(calculatePercentageDifference(sum7, sum14).intValue());
        statDTO.setPercent1Month(calculatePercentageDifference(sum30, sum60).intValue());
        statDTO.setPercent1Year(calculatePercentageDifference(sum365, sum730).intValue());
        statDTO.setPercent1MonthOrders(calculatePercentageDifference(sumCount1Month, sumCount2Month).intValue());
        statDTO.setPercent2MonthOrders(calculatePercentageDifference(sumCount2Month, sumCount3Month).intValue());
        return statDTO;
    }
//    ============================================ PERSONAL STAT END ===================================================



//=========================================== ВЗЯТИЕ СУММ ЗП И ЧЕКОВ ===================================================

    private List<PaymentCheck> getPaymentChecks(LocalDate localDate, String role, Set<Manager> managerList) {
        return checkRoleAndExecute(role, () -> paymentCheckService.findAllToDate(localDate), owner -> paymentCheckService.findAllToDateByOwner(localDate, owner), managerList);
    }

    private List<Zp> getZarplataChecks(LocalDate localDate, String role, Set<Manager> managerList) {
        return checkRoleAndExecute(role, () -> zpService.findAllToDate(localDate), owner -> zpService.findAllToDateByOwner(localDate, owner), managerList);
    }

    private List<Long> getInWorkLeadList(String role, LocalDate localDate, Set<Manager> managerList) {
        return checkRoleAndExecute(role, () -> leadService.getAllLeadsByDateAndStatus(localDate, "В работе"), owner -> leadService.getAllLeadsByDateAndStatusToOwner(localDate, "В работе", owner), managerList);
    }

    private List<Long> getNewLeadList(String role, LocalDate localDate, Set<Manager> managerList) {
        return checkRoleAndExecute(role, () -> leadService.getAllLeadsByDate(localDate), owner -> leadService.getAllLeadsByDateToOwner(localDate, owner), managerList);
    }

    private <T> List<T> checkRoleAndExecute(String role, Supplier<List<T>> adminFunction, Function<Set<Manager>, List<T>> ownerFunction, Set<Manager> managerList) {
        if (ROLE_ADMIN.equals(role)) {
            return adminFunction.get();
        }
        if (ROLE_OWNER.equals(role)) {
            return ownerFunction.apply(managerList);
        }
        return adminFunction.get();
    }
//======================================= ВЗЯТИЕ СУММ ЗП И ЧЕКОВ -  КОНЕЦ ==============================================

    //========================================= ПЕРЕВОД В МАПУ ЗП И ЧЕКОВ ==================================================
    private Map<Integer, BigDecimal> calculateDailyZpSumForMonth(LocalDate targetMonthDate, List<Zp> zps) { //Создание мапы день-сумма зп
        Map<Integer, BigDecimal> dailyZpSumMap = initializeDailyZpSumMap(targetMonthDate);
        return updateDailyZpSumMap(dailyZpSumMap, zps, targetMonthDate.getMonth(), targetMonthDate.getYear());
    }

    private Map<Integer, BigDecimal> initializeDailyZpSumMap(LocalDate date) {
        return IntStream.rangeClosed(1, date.lengthOfMonth())
                .boxed()
                .collect(Collectors.toMap(i -> i, i -> BigDecimal.ZERO, (existing, replacement) -> existing, LinkedHashMap::new));
    }

    private Map<Integer, BigDecimal> updateDailyZpSumMap(Map<Integer, BigDecimal> dailyZpSumMap, List<Zp> zps, Month targetMonth, int year) {
        zps.stream()
                .filter(zp -> zp.getCreated().getMonth() == targetMonth)
                .filter(zp -> zp.getCreated().getYear() == year)
                .forEach(zp -> {
                    int dayOfMonth = zp.getCreated().getDayOfMonth();
                    dailyZpSumMap.computeIfPresent(dayOfMonth, (k, currentSum) -> currentSum.add(zp.getSum()));
                });
        return dailyZpSumMap;
    } //Создание мапы день-сумма зп


    private Map<Integer, BigDecimal> getDailySalarySumMap(LocalDate desiredDate, List<PaymentCheck> pcs) {
        if (pcs == null || pcs.isEmpty()) {
            // Возвращаем пустую карту, если список чеков пустой или null
            return IntStream.rangeClosed(1, desiredDate.lengthOfMonth())
                    .boxed()
                    .collect(Collectors.toMap(Function.identity(), day -> BigDecimal.ZERO));
        }

        // Создаем карту день -> сумма и сразу заполняем её значениями 0
        Map<Integer, BigDecimal> dailySalarySumMap = IntStream.rangeClosed(1, desiredDate.lengthOfMonth())
                .boxed()
                .collect(Collectors.toMap(Function.identity(), day -> BigDecimal.ZERO));

        // Фильтруем и добавляем суммы
        pcs.stream()
                .filter(pc -> pc.getCreated().getYear() == desiredDate.getYear()) // Фильтр по году
                .filter(pc -> pc.getCreated().getMonth() == desiredDate.getMonth()) // Фильтр по месяцу
                .forEach(pc -> {
                    int dayOfMonth = pc.getCreated().getDayOfMonth();
                    dailySalarySumMap.merge(dayOfMonth, pc.getSum(), BigDecimal::add); // Удобное обновление суммы
                });

        return dailySalarySumMap;
    }


//    private Map<Integer, BigDecimal> getDailySalarySumMap(LocalDate desiredDate, List<PaymentCheck> pcs) { //Создание мапы день-сумма Чеки
//        Map<Integer, BigDecimal> dailySalarySumMap = IntStream.rangeClosed(1, desiredDate.lengthOfMonth())
//                .boxed()
//                .collect(Collectors.toMap(Function.identity(), day -> BigDecimal.ZERO));
//
//        pcs.stream()
//                .filter(pc -> pc.getCreated().getMonth() == desiredDate.getMonth())
//                .forEach(pc -> {
//                    int dayOfMonth = pc.getCreated().getDayOfMonth();
//                    dailySalarySumMap.computeIfPresent(dayOfMonth, (day, currentSum) -> currentSum.add(pc.getSum()));
//                });
//
//        return dailySalarySumMap;
//    } // Создание мапы день-сумма Чеки

//===================================== ПЕРЕВОД В МАПУ ЗП И ЧЕКОВ -  КОНЕЦ =============================================



    private Map<Integer, Map<Integer, BigDecimal>> getYearlyMonthlySalarySumMap(List<PaymentCheck> pcs) {
        Map<Integer, Map<Integer, BigDecimal>> yearlyMonthlySalarySumMap = new HashMap<>();

        pcs.forEach(pc -> {
            LocalDate createdDate = pc.getCreated();
            int year = createdDate.getYear();
            int month = createdDate.getMonthValue();

            yearlyMonthlySalarySumMap.putIfAbsent(year, new HashMap<>());
            Map<Integer, BigDecimal> monthlySumMap = yearlyMonthlySalarySumMap.get(year);
            monthlySumMap.merge(month, pc.getSum(), BigDecimal::add);
        });

        return yearlyMonthlySalarySumMap;
    }

    private Map<Integer, Map<Integer, BigDecimal>> getYearlyMonthlyZpSumMap(List<Zp> zps) {
        Map<Integer, Map<Integer, BigDecimal>> yearlyMonthlyZpSumMap = new HashMap<>();

        zps.forEach(zp -> {
            LocalDate createdDate = zp.getCreated();
            int year = createdDate.getYear();
            int month = createdDate.getMonthValue();

            yearlyMonthlyZpSumMap.putIfAbsent(year, new HashMap<>());
            Map<Integer, BigDecimal> monthlySumMap = yearlyMonthlyZpSumMap.get(year);
            monthlySumMap.merge(month, zp.getSum(), BigDecimal::add);
        });

        return yearlyMonthlyZpSumMap;
    }





    private Map<Integer, Map<Integer, BigDecimal>> getYearlyMonthlySalarySumMapZp(List<Zp> pcs) {
        Map<Integer, Map<Integer, BigDecimal>> yearlyMonthlySalarySumMap = new HashMap<>();

        pcs.forEach(pc -> {
            LocalDate createdDate = pc.getCreated();
            int year = createdDate.getYear();
            int month = createdDate.getMonthValue();

            yearlyMonthlySalarySumMap.putIfAbsent(year, new HashMap<>());
            Map<Integer, BigDecimal> monthlySumMap = yearlyMonthlySalarySumMap.get(year);
            monthlySumMap.merge(month, pc.getSum(), BigDecimal::add);
        });

        return yearlyMonthlySalarySumMap;
    }


    //========================================== ПЕРЕВОД ГРАФИКА В JSON ====================================================
    private String getJSON(Map<Integer, BigDecimal> zpPayMapOnMonth) { // Перевод мапы в JSON
        // Инициализируем ObjectMapper
        ObjectMapper objectMapper = new ObjectMapper();
        try {
            // Преобразовываем Map в JSON-строку
            return objectMapper.writeValueAsString(zpPayMapOnMonth);
        } catch (JsonProcessingException exception) {
            throw new RuntimeException("Произошла ошибка при преобразовании карты в JSON", exception);
        }
    } // Перевод мапы в JSON

    private String getJSONMonth(Map<Integer, Map<Integer, BigDecimal>> zpPayMapOnMonth) {
        ObjectMapper objectMapper = new ObjectMapper();
        try {
            return objectMapper.writeValueAsString(zpPayMapOnMonth);
        } catch (JsonProcessingException exception) {
            throw new RuntimeException("Произошла ошибка при преобразовании карты в JSON", exception);
        }
    }

//====================================== ПЕРЕВОД ГРАФИКА В JSON -  КОНЕЦ ===============================================





    //==================================== ОЦЕНКА РАЗНИЦЫ 2Х ЧИСЕЛ В ПРОЦЕНТАХ =============================================
    private BigDecimal calculatePercentageDifference(BigDecimal sum1, BigDecimal sum2) {
        // Обработка граничных условий, когда любая сумма равна нулю
        if (isZero(sum1) || isZero(sum2)) {
            return handleZeroValues(sum1, sum2);
        }

        // Обработка общего случая
        BigDecimal difference = sum1.subtract(sum2); //разница между суммами
        BigDecimal baseValue = difference.compareTo(BigDecimal.ZERO) > 0 ? sum1 : sum2;

        return difference.divide(baseValue, 2, RoundingMode.HALF_UP).multiply(ONE_HUNDRED);
    }

    private boolean isZero(BigDecimal value) {
        return value.compareTo(BigDecimal.ZERO) == 0;
    }

    private BigDecimal handleZeroValues(BigDecimal sum1, BigDecimal sum2) {
        if (isZero(sum1) && !isZero(sum2)) {
            return ONE_HUNDRED.negate(); // не нужно делить и умножать на 100, так как оно всегда равно -100
        } else if (!isZero(sum1) && isZero(sum2)) {
            return ONE_HUNDRED; // не нужно делить и умножать на 100, так как оно всегда равно 100
        } else {
            return BigDecimal.ZERO;
        }
    }

//================================= ОЦЕНКА РАЗНИЦЫ 2Х ЧИСЕЛ В ПРОЦЕНТАХ - КОНЕЦ ========================================

//========================================= РАЗНИЦА МЕЖДУ ДВУМЯ СУММАМИ ================================================


    // Метод, который вычисляет процент разницы между двумя целочисленными значениями с учетом того, является ли оно отрицательным или положительным.
    private int calculatePercentageDifference(int sum1, int sum2) {
        if (sum1 == 0 || sum2 == 0) {
            return handleZeroSumCase(sum1, sum2);
        } else {
            return computePercentageDifference(sum1, sum2);
        }
    }

    //
    //Вычисляет разницу между суммой1 и суммой2 и определяет процентную разницу.
    private int computePercentageDifference(int sum1, int sum2) {
        int difference = sum1 - sum2;

        if (difference != 0) {
            int denominator = (difference > 0) ? sum1 : sum2;
            return difference * PERCENT_MULTIPLIER / Math.abs(denominator);
        } else {
            return 0;
        }
    }

    // Обрабатывает особый случай, когда либо sum1, либо sum2, либо обе равны нулю.
    private int handleZeroSumCase(int sum1, int sum2) {
        if (sum1 == 0 && sum2 == 0) {
            return 0;
        }
        if (sum1 == 0) {
            return sum2 > 0 ? -PERCENT_MULTIPLIER : 0;
        } else {
            return PERCENT_MULTIPLIER;
        }
    }

//===================================== РАЗНИЦА МЕЖДУ ДВУМЯ СУММАМИ - КОНЕЦ ============================================


    public UserStatDTO getWorkerReviews(User user, LocalDate localDate) {
        List<Zp> zps = zpService.findAllToDateByUser(localDate, user.getId());
        //        выбираем даты месяца
        LocalDate firstDayOfMonth = localDate.withDayOfMonth(1);
        LocalDate firstDayOfMonthAgo = firstDayOfMonth.minusMonths(1).withDayOfMonth(1);
        LocalDate firstDayOf3MonthAgo = firstDayOfMonth.minusMonths(2).withDayOfMonth(1);
        LocalDate lastDayOfMonth = localDate.withDayOfMonth(localDate.lengthOfMonth());
        LocalDate lastDayOfMonthAgo = firstDayOfMonthAgo.withDayOfMonth(firstDayOfMonthAgo.lengthOfMonth());
        LocalDate lastDayOf3MonthAgo = firstDayOf3MonthAgo.withDayOfMonth(firstDayOf3MonthAgo.lengthOfMonth());
        //        выбираем даты текущего года
        LocalDate firstDayOfYear = localDate.withDayOfYear(1);
        //        выбираем даты прошлого года
        LocalDate firstDayOf1YearAgo = localDate.minusYears(1).withDayOfYear(1);
        LocalDate lastDayOf1YearAgo = localDate.minusYears(1).withMonth(12).withDayOfMonth(31);

        //        ЗП Разбивка на списки 1-2-7-14-30-60-90-360-730 дней от текущей даты
        List<Zp> zpPay1Day = zps.stream().filter(z -> z.getCreated().isEqual(localDate.minusDays(1))).toList();
        List<Zp> zpPay2Day = zps.stream().filter(z -> z.getCreated().isEqual(localDate.minusDays(2))).toList();
        List<Zp> zpPay7Day = zps.stream().filter(z -> z.getCreated().isEqual(localDate.minusDays(7)) || z.getCreated().isAfter(localDate.minusDays(7))).toList();
        List<Zp> zpPay14Day = zps.stream().filter(z -> (z.getCreated().isEqual(localDate.minusDays(14)) || z.getCreated().isAfter(localDate.minusDays(14))) && z.getCreated().isBefore(localDate.minusDays(7))).toList();
        List<Zp> zpPay30Day = zps.stream().filter(z -> (z.getCreated().isEqual(firstDayOfMonth) || z.getCreated().isAfter(firstDayOfMonth)) && (z.getCreated().isEqual(lastDayOfMonth) || z.getCreated().isBefore(lastDayOfMonth))).toList();
        List<Zp> zpPay60Day = zps.stream().filter(z -> (z.getCreated().isEqual(firstDayOfMonthAgo) || z.getCreated().isAfter(firstDayOfMonthAgo)) && (z.getCreated().isEqual(lastDayOfMonthAgo) || z.getCreated().isBefore(lastDayOfMonthAgo))).toList();
        List<Zp> zpPay90Day = zps.stream().filter(z -> (z.getCreated().isEqual(firstDayOf3MonthAgo) || z.getCreated().isAfter(firstDayOf3MonthAgo)) && (z.getCreated().isEqual(lastDayOf3MonthAgo) || z.getCreated().isBefore(lastDayOf3MonthAgo))).toList();
        List<Zp> zpPay365Day = zps.stream().filter(z -> (z.getCreated().isEqual(firstDayOfYear) || z.getCreated().isAfter(firstDayOfYear)) && (z.getCreated().isEqual(localDate) || z.getCreated().isBefore(localDate))).toList();
        List<Zp> zpPay730Day = zps.stream().filter(z -> (z.getCreated().isEqual(firstDayOf1YearAgo) || z.getCreated().isAfter(firstDayOf1YearAgo)) && (z.getCreated().isEqual(localDate.minusYears(1)) || z.getCreated().isBefore(localDate.minusYears(1)))).toList();


        //        ЗП Сумма всех выплат за 1-2-7-14-30-60-90-360-730 дней
        BigDecimal sum1 = zpPay1Day.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add); // первая сумма
        BigDecimal sum7 = zpPay7Day.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add); // первая сумма
        BigDecimal sum30 = zpPay30Day.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add); // первая сумма
        BigDecimal sum365 = zpPay365Day.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add); // первая сумма
        BigDecimal sum2 = zpPay2Day.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sum14 = zpPay14Day.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sum60 = zpPay60Day.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sum730 = zpPay730Day.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add);

        //        ЗП Сумма всех заказов за 30-60-90 дней
        BigDecimal sumCount1Month = BigDecimal.valueOf(zpPay30Day.size()); // 1 сумма
        BigDecimal sumCount2Month = BigDecimal.valueOf(zpPay60Day.size()); // 2 сумма
        BigDecimal sumCount3Month = BigDecimal.valueOf(zpPay90Day.size()); // 3 сумма


        Long imageId = user.getImage() != null ? user.getImage().getId() : 1L;
        UserStatDTO userStatDTO = new UserStatDTO();
        //  Общая информация
        userStatDTO.setId(user.getId());
        userStatDTO.setImageId(imageId);
        userStatDTO.setFio(user.getFio());
        userStatDTO.setCoefficient(user.getCoefficient());

        userStatDTO.setZpPayMap(getJSON(calculateDailyZpSumForMonth(localDate, zps)));
        userStatDTO.setZpPayMapMonth(getJSONMonth(getYearlyMonthlyZpSumMap(zps)));
        //  Заработанные суммы за период
        userStatDTO.setSum1Day(sum1.intValue());
        userStatDTO.setSum1Week(sum7.intValue());
        userStatDTO.setSum1Month(sum30.intValue());
        userStatDTO.setSum1Year(sum365.intValue());
        userStatDTO.setSumOrders1Month(zpPay30Day.size());
        userStatDTO.setSumOrders2Month(zpPay60Day.size());

        userStatDTO.setPercent1Day(calculatePercentageDifference(sum1, sum2).intValue());
        userStatDTO.setPercent1Week(calculatePercentageDifference(sum7, sum14).intValue());
        userStatDTO.setPercent1Month(calculatePercentageDifference(sum30, sum60).intValue());
        userStatDTO.setPercent1Year(calculatePercentageDifference(sum365, sum730).intValue());
        userStatDTO.setPercent1MonthOrders(calculatePercentageDifference(sumCount1Month, sumCount2Month).intValue());
        userStatDTO.setPercent2MonthOrders(calculatePercentageDifference(sumCount2Month, sumCount3Month).intValue());
        return userStatDTO;
    }

//===================================== РАЗНИЦА МЕЖДУ ДВУМЯ СУММАМИ - КОНЕЦ ============================================



//    ========================================== PERSONAL LIST START ==================================================
    public List<ManagersListDTO> getManagers(){
        return managerService.getAllManagers().stream().map(this::toManagersListDTO).collect(Collectors.toList());
    }
    public List<MarketologsListDTO> getMarketologs(){
        return marketologService.getAllMarketologs().stream().map(this::toMarketologsListDTO).collect(Collectors.toList());
    }
    public List<WorkersListDTO> gerWorkers(){
        return workerService.getAllWorkers().stream().map(this::toWorkersListDTO).collect(Collectors.toList());
    }
    public List<OperatorsListDTO> gerOperators(){
        return operatorService.getAllOperators().stream().map(this::toOperatorsListDTO).collect(Collectors.toList());
    }

    public List<ManagersListDTO> getManagersToManager(Principal principal){
        return managerService.getAllManagers().stream().filter(p -> Objects.equals(p.getUser().getUsername(), principal.getName())).map(this::toManagersListDTO).collect(Collectors.toList());
    }
    public List<MarketologsListDTO> getMarketologsToManager(Manager manager){
        return marketologService.getAllMarketologs().stream().map(this::toMarketologsListDTO).collect(Collectors.toList());
    }
    public List<WorkersListDTO> gerWorkersToManager(Manager manager){
        return workerService.getAllWorkersToManager(manager).stream().map(this::toWorkersListDTO).collect(Collectors.toList());
    }
    public List<OperatorsListDTO> gerOperatorsToManager(Manager manager){
        return operatorService.getAllOperatorsToManager(manager).stream().map(this::toOperatorsListDTO).collect(Collectors.toList());
    }

    @Override
    public List<Manager> findAllManagersWorkers(List<Manager> managerList) {
        return managerService.findAllManagersWorkers(managerList);
    }

    public List<ManagersListDTO> getManagersToOwner(List<Manager> managers){
        return managers.stream().map(this::toManagersListDTO).toList();
//        return managerService.getAllManagersToOwner(managers).stream().map(this::toManagersListDTO).collect(Collectors.toList());
//        return managerService.getAllManagers().stream().filter(p -> Objects.equals(p.getUser().getUsername(), principal.getName())).map(this::toManagersListDTO).collect(Collectors.toList());
    }
    public List<MarketologsListDTO> getMarketologsToOwner(List<Marketolog> allMarketologs){
        return allMarketologs.stream().map(this::toMarketologsListDTO).toList();
    }

    @Override
    public List<OperatorsListDTO> gerOperatorsToOwner(List<Operator> allOperators) {
        return allOperators.stream().map(this::toOperatorsListDTO).toList();
    }

    public List<WorkersListDTO> getWorkersToOwner(List<Worker> allWorkers){
        return allWorkers.stream().map(this::toWorkersListDTO).toList();
    }
    public List<OperatorsListDTO> gerOperatorsToOwner(Manager manager){
        return operatorService.getAllOperatorsToManager(manager).stream().map(this::toOperatorsListDTO).collect(Collectors.toList());
    }


    @Transactional
    public List<ManagersListDTO> getManagersAndCount(){
        return managerService.getAllManagers().stream().map(this::toManagersListDTOAndCount).collect(Collectors.toList());
    }
    public List<MarketologsListDTO> getMarketologsAndCount(){
        return marketologService.getAllMarketologs().stream().map(this::toMarketologsListDTOAndCount).collect(Collectors.toList());
    }
    @Transactional
    public List<WorkersListDTO> gerWorkersToAndCount(){
        return workerService.getAllWorkers().stream().map(this::toWorkersListDTOAndCount).collect(Collectors.toList());
    }
    public List<OperatorsListDTO> gerOperatorsAndCount(){
        return operatorService.getAllOperators().stream().map(this::toOperatorsListDTOAndCount).collect(Collectors.toList());
    }


    public Map<String, UserData> getPersonalsAndCountToMap() {
        LocalDate localDate = LocalDate.now();
        LocalDate firstDayOfMonth = localDate.withDayOfMonth(1);
        LocalDate lastDayOfMonth = localDate.withDayOfMonth(localDate.lengthOfMonth());

        // Получаем данные о зарплатах
        Map<String, Pair<String, Long>> zps = zpService.getAllZpToMonthToTelegram(firstDayOfMonth, lastDayOfMonth);
        // Получаем данные о платежах
        Map<String, Pair<Long, Long>> pcs = paymentCheckService.getAllPaymentToMonth(firstDayOfMonth, lastDayOfMonth);
        // Получаем данные о новых компаниях
        Map<String, Long> newCompanies = companyService.getAllNewCompanies(firstDayOfMonth, lastDayOfMonth);
        // Получаем данные о новых заказах
        Map<String, Pair<Long, Long>> newOrders = orderService.getNewOrderAll("Новый", "Коррекция");
        // Получаем данные о заказах в публикации
        Map<String, Pair<Long, Long>> inPublishAndVigul = reviewService.getAllPublishAndVigul(firstDayOfMonth, localDate);
        // Получаем данные о всех заказах с разными данными
        Map<String, Map<String, Long>>  allOrders = orderService.getAllOrdersToMonthByStatus(firstDayOfMonth, lastDayOfMonth, "Новый", "В проверку", "На проверке", "Коррекция", "Опубликовано", "Выставлен счет", "Напоминание", "Не оплачено");
        Map<String, Long> leadsNewAndInWork = leadService.getAllLeadsToMonthToManager("Новый", firstDayOfMonth, lastDayOfMonth);
        System.out.println(leadsNewAndInWork);
        Long zpTotal = zps.values().stream()
                .mapToLong(Pair::getSecond) // Извлекаем сумму зарплаты
                .sum();

        // Создаем карту для результатов
        Map<String, UserData> result = new HashMap<>();

        // Параллельная обработка данных и сохранение в карту
        zps.entrySet().parallelStream().forEach(entry -> {
            String fio = entry.getKey();
            Pair<String, Long> pair = entry.getValue();
            Long totalSum = pcs.getOrDefault(fio, Pair.of(0L, 0L)).getFirst();
            Long newCompanyCount = newCompanies.getOrDefault(fio, 0L);
//            Long newOrderCount = newOrders.getOrDefault(fio, Pair.of(0L, 0L)).getFirst();
//            Long correctOrders = newOrders.getOrDefault(fio, Pair.of(0L, 0L)).getSecond();
            Long inVigul = inPublishAndVigul.getOrDefault(fio, Pair.of(0L, 0L)).getFirst();
            Long inPublishCount = inPublishAndVigul.getOrDefault(fio, Pair.of(0L, 0L)).getSecond();
            Long leadsNew = leadsNewAndInWork.getOrDefault(fio,  0L);
            String role = pair.getFirst();
            // Счетчики заказов
            Map<String, Long> orders = allOrders.getOrDefault(fio, new HashMap<>());
            Long orderInNew = orders.getOrDefault("Новый", 0L);
            Long orderToCheck = orders.getOrDefault("В проверку", 0L);
            Long orderInCheck = orders.getOrDefault("На проверке", 0L);
            Long orderInCorrect = orders.getOrDefault("Коррекция", 0L);
            Long orderInPublished = orders.getOrDefault("Опубликовано", 0L);
            Long orderInWaitingPay1 = orders.getOrDefault("Выставлен счет", 0L);
            Long orderInWaitingPay2 = orders.getOrDefault("Напоминание", 0L);
            Long orderNoPay = orders.getOrDefault("Не оплачено", 0L);

            // Сохраняем все данные в карту
            result.put(fio, new UserData(fio, role, pair.getSecond(), totalSum, zpTotal, newCompanyCount, orderInNew, orderInCorrect, inVigul, inPublishCount, 1L,0L, 0L, 0L, leadsNew, null, null,
                    orderInNew, orderToCheck, orderInCheck, orderInCorrect, orderInPublished, orderInWaitingPay1, orderInWaitingPay2, orderNoPay));
        });

//        System.out.println(result);
        // Возвращаем результат
//        System.out.println(displayResult(result));
        return result;
    }



    public String displayResult(Map<String, UserData> result) {
        StringBuilder resultBuilder = new StringBuilder();

        // Сортируем сначала менеджеров по totalSum, затем остальных по salary
        List<Map.Entry<String, UserData>> sortedEntries = result.entrySet().stream()
                .sorted((entry1, entry2) -> {
                    UserData user1 = entry1.getValue();
                    UserData user2 = entry2.getValue();

                    boolean isManager1 = user1.getRole().equals("ROLE_MANAGER");
                    boolean isManager2 = user2.getRole().equals("ROLE_MANAGER");

                    if (isManager1 && isManager2) {
                        // Оба менеджеры → сортируем по убыванию суммы чеков
                        return Long.compare(user2.getTotalSum(), user1.getTotalSum());
                    } else if (!isManager1 && !isManager2) {
                        // Оба не менеджеры → сортируем по убыванию зарплаты
                        return Long.compare(user2.getSalary(), user1.getSalary());
                    }
                    // Менеджеры идут первыми
                    return Boolean.compare(isManager2, isManager1);
                })
                .collect(Collectors.toList());

        // Вычисляем общую выручку всех менеджеров
        long totalManagerRevenue = result.values().stream()
                .filter(user -> user.getRole().equals("ROLE_MANAGER"))
                .mapToLong(UserData::getTotalSum)
                .sum();

        // Фильтруем мапу, чтобы учесть только двух конкретных менеджеров
        long totalSpecificManagersRevenue = result.values().stream()
                .filter(user -> user.getRole().equals("ROLE_MANAGER") &&
                        (user.getFio().equals("Звуков Андрей") || user.getFio().equals("Анжелика Б.")))
                .mapToLong(UserData::getTotalSum)
                .sum();

        // Фильтруем мапу, чтобы учесть только двух конкретных менеджеров
        long totalZp = result.values().stream().findFirst().get().getZpTotal();


//        System.out.println(totalZp);


        // Вычисляем количество новых компаний
        long totalNewCompanies = result.values().stream()
                .filter(user -> user.getRole().equals("ROLE_MANAGER"))
                .mapToLong(UserData::getNewCompanies)
                .sum();

        // Общая выручка и новые компании
        resultBuilder.append("Выручка за месяц всей компании: ").append(totalManagerRevenue).append(" руб. ( ")
                .append(totalSpecificManagersRevenue).append(" руб. )\n")
                .append("Общие затраты по ЗП: ").append(totalZp).append(" руб. \n")
                .append("Новых компаний за месяц: ").append(totalNewCompanies).append("\n\n");

        // Выручка менеджеров
        resultBuilder.append("Выручка менеджеров:\n");
        sortedEntries.stream()
                .filter(entry -> "ROLE_MANAGER".equals(entry.getValue().getRole()))
                .forEach(entry -> {
                    String fio = entry.getKey();
                    UserData userData = entry.getValue();
                    resultBuilder.append(fio).append(": ").append(userData.getTotalSum()).append(" руб. Новых: ")
                            .append(userData.getNewCompanies()).append("\n");
                });

        // ЗП менеджеров
        resultBuilder.append("\nМенеджеры:\n\n");
        sortedEntries.stream()
                .filter(entry -> "ROLE_MANAGER".equals(entry.getValue().getRole()))
                .forEach(entry -> {
                    String fio = entry.getKey();
                    UserData userData = entry.getValue();
//                    String orderStatus = "Новые: " + userData.getOrderInNew() + " В проверку: " + userData.getOrderToCheck() + " На проверке: " + userData.getOrderInCheck()
//                            + " Коррекция: " + userData.getCorrectOrders() + " Опубликовано: " + userData.getOrderInPublished() + " Выставлен счет: " + userData.getOrderInWaitingPay1()
//                            + " Напоминание: " + userData.getOrderInWaitingPay2() + " Не оплачено: " + userData.getOrderNoPay();
                    String orderStatus = "Лиды: " + userData.getLeadsNew() +" В проверку: " + userData.getOrderToCheck() + " На проверке: " + userData.getOrderInCheck()
                            + " Опубликовано: " + userData.getOrderInPublished() + " Выставлен счет: " + userData.getOrderInWaitingPay1()
                            + " Напоминание: " + userData.getOrderInWaitingPay2() + " Не оплачено: " + userData.getOrderNoPay();
                    String orderStatsForWorkers = "Новых - " + userData.getNewOrders() + " Коррекция - " + userData.getCorrectOrders()
                            + " Выгул - " + userData.getInVigul() + " Публикация - " + userData.getInPublish();
                    resultBuilder.append(fio).append(": ").append(userData.getSalary()).append(" руб. \n")
                            .append(orderStatus)
                            .append("\n")
//                            .append("\nСтатусы Заказов: ")
                            .append(orderStatsForWorkers)
                            .append("\n\n");
                });

        // ЗП Работников
        resultBuilder.append("\nЗП Работников:\n");
        sortedEntries.stream()
                .filter(entry -> "ROLE_WORKER".equals(entry.getValue().getRole()))
                .forEach(entry -> {
                    String fio = entry.getKey();
                    UserData userData = entry.getValue();
//                    String orderStats = "н-" + userData.getNewOrders() + "к-" + userData.getCorrectOrders()
//                            + "в-" + userData.getInVigul() + "п-" + userData.getInPublish();
                    String orderStats = "Новые - " + userData.getNewOrders() + " В коррекции - " + userData.getCorrectOrders()
                            + " В выгуле - " + userData.getInVigul() + " На публикации - " + userData.getInPublish();
                    resultBuilder.append(fio).append(": ").append(userData.getSalary())
                            .append(" руб.  ").append("\n").append(orderStats).append("\n\n");
                });

        return resultBuilder.toString();
    }

    public String displayResultToTelegramAdmin(Map<String, UserData> result) {
        StringBuilder resultBuilder = new StringBuilder();

        // Сортируем сначала менеджеров по totalSum, затем остальных по salary
        List<Map.Entry<String, UserData>> sortedEntries = result.entrySet().stream()
                .sorted((entry1, entry2) -> {
                    UserData user1 = entry1.getValue();
                    UserData user2 = entry2.getValue();

                    boolean isManager1 = user1.getRole().equals("ROLE_MANAGER");
                    boolean isManager2 = user2.getRole().equals("ROLE_MANAGER");

                    if (isManager1 && isManager2) {
                        // Оба менеджеры → сортируем по убыванию суммы чеков
                        return Long.compare(user2.getTotalSum(), user1.getTotalSum());
                    } else if (!isManager1 && !isManager2) {
                        // Оба не менеджеры → сортируем по убыванию зарплаты
                        return Long.compare(user2.getSalary(), user1.getSalary());
                    }
                    // Менеджеры идут первыми
                    return Boolean.compare(isManager2, isManager1);
                })
                .collect(Collectors.toList());

        // Вычисляем общую выручку всех менеджеров
        long totalManagerRevenue = result.values().stream()
                .filter(user -> user.getRole().equals("ROLE_MANAGER"))
                .mapToLong(UserData::getTotalSum)
                .sum();

        // Фильтруем мапу, чтобы учесть только двух конкретных менеджеров
        long totalSpecificManagersRevenue = result.values().stream()
                .filter(user -> user.getRole().equals("ROLE_MANAGER") &&
                        (user.getFio().equals("Звуков Андрей") || user.getFio().equals("Анжелика Б.")))
                .mapToLong(UserData::getTotalSum)
                .sum();

        // Фильтруем мапу, чтобы учесть только двух конкретных менеджеров
        long totalZp = result.values().stream().findFirst().get().getZpTotal();


//        System.out.println(totalZp);


        // Вычисляем количество новых компаний
        long totalNewCompanies = result.values().stream()
                .filter(user -> user.getRole().equals("ROLE_MANAGER"))
                .mapToLong(UserData::getNewCompanies)
                .sum();

        // Общая выручка и новые компании
        resultBuilder.append("Выручка за месяц всей компании: ").append(totalManagerRevenue).append(" руб. ( ")
                .append(totalSpecificManagersRevenue).append(" руб. )\n")
                .append("Общие затраты по ЗП: ").append(totalZp).append(" руб. \n")
                .append("Новых компаний за месяц: ").append(totalNewCompanies).append("\n\n");

        // Выручка менеджеров
        resultBuilder.append("Выручка менеджеров:\n");
        sortedEntries.stream()
                .filter(entry -> "ROLE_MANAGER".equals(entry.getValue().getRole()))
                .forEach(entry -> {
                    String fio = entry.getKey();
                    UserData userData = entry.getValue();
                    resultBuilder.append(fio).append(": ").append(userData.getTotalSum()).append(" руб. Новых: ")
                            .append(userData.getNewCompanies()).append("\n");
                });

        // ЗП менеджеров
        resultBuilder.append("\nМенеджеры:\n\n");
        sortedEntries.stream()
                .filter(entry -> "ROLE_MANAGER".equals(entry.getValue().getRole()))
                .forEach(entry -> {
                    String fio = entry.getKey();
                    UserData userData = entry.getValue();
//                    String orderStatus = "Новые: " + userData.getOrderInNew() + " В проверку: " + userData.getOrderToCheck() + " На проверке: " + userData.getOrderInCheck()
//                            + " Коррекция: " + userData.getCorrectOrders() + " Опубликовано: " + userData.getOrderInPublished() + " Выставлен счет: " + userData.getOrderInWaitingPay1()
//                            + " Напоминание: " + userData.getOrderInWaitingPay2() + " Не оплачено: " + userData.getOrderNoPay();
                    String orderStatus = "Лиды: " + userData.getLeadsNew() +" В проверку: " + userData.getOrderToCheck() + " На проверке: " + userData.getOrderInCheck()
                            + " Опубликовано: " + userData.getOrderInPublished() + " Выставлен счет: " + userData.getOrderInWaitingPay1()
                            + " Напоминание: " + userData.getOrderInWaitingPay2() + " Не оплачено: " + userData.getOrderNoPay();
                    String orderStatsForWorkers = "Новых - " + userData.getNewOrders() + " Коррекция - " + userData.getCorrectOrders()
                            + " Выгул - " + userData.getInVigul() + " Публикация - " + userData.getInPublish();
                    resultBuilder.append(fio).append(": ").append(userData.getSalary()).append(" руб. \n")
                            .append(orderStatus)
                            .append("\n")
//                            .append("\nСтатусы Заказов: ")
                            .append(orderStatsForWorkers)
                            .append("\n\n");
                });

        // ЗП Работников
//        resultBuilder.append("\nЗП Работников:\n");
//        sortedEntries.stream()
//                .filter(entry -> "ROLE_WORKER".equals(entry.getValue().getRole()))
//                .forEach(entry -> {
//                    String fio = entry.getKey();
//                    UserData userData = entry.getValue();
////                    String orderStats = "н-" + userData.getNewOrders() + "к-" + userData.getCorrectOrders()
////                            + "в-" + userData.getInVigul() + "п-" + userData.getInPublish();
//                    String orderStats = "Новые - " + userData.getNewOrders() + " В коррекции - " + userData.getCorrectOrders()
//                            + " В выгуле - " + userData.getInVigul() + " На публикации - " + userData.getInPublish();
//                    resultBuilder.append(fio).append(": ").append(userData.getSalary())
//                            .append(" руб.  ").append("\n").append(orderStats).append("\n\n");
//                });

        return resultBuilder.toString();
    }



    public List<UserData> getPersonalsAndCountToScore(LocalDate localDate) {
        LocalDate firstDayOfMonth = localDate.withDayOfMonth(1);
        LocalDate lastDayOfMonth = localDate.withDayOfMonth(localDate.lengthOfMonth());

        // Получаем данные о зарплатах
        Map<String, Quadruple<String, Long, Long, Long>> zps = zpService.getAllZpToMonth(firstDayOfMonth, lastDayOfMonth);
        // Получаем данные о платежах
        Map<String, Pair<Long, Long>> pcs = paymentCheckService.getAllPaymentToMonth(firstDayOfMonth, lastDayOfMonth);
        // Получаем данные о новых компаниях
        Map<String, Long> newCompanies = companyService.getAllNewCompanies(firstDayOfMonth, lastDayOfMonth);
        // Получаем данные о новых заказах
        Map<String, Pair<Long, Long>> newOrders = orderService.getNewOrderAll("Новый", "Коррекция");
        // Получаем данные о заказах в публикации
        Map<String, Pair<Long, Long>> inPublishAndVigul = reviewService.getAllPublishAndVigul(firstDayOfMonth, localDate);
        // Получаем данные о заказах
//        Map<String, Long> orders = orderService.getAllOrdersToMonth("Оплачено", firstDayOfMonth, lastDayOfMonth);
        // Получаем данные об отзывах
//        Map<String, Long> reviews = reviewService.getAllReviewsToMonth(firstDayOfMonth, lastDayOfMonth);
        // Получаем данные о аватарках
        Map<String, Pair<Long, Long>> imagesIds = imageService.getAllImages();
        // Получаем данные о лидах
        Map<String, Pair<Long, Long>> leadsNewAndInWork = leadService.getAllLeadsToMonth("В работе", firstDayOfMonth, lastDayOfMonth);

        // Создаем карту для результатов
        List<UserData> result = new ArrayList<>();

        // Параллельная обработка данных и сохранение в карту
        zps.entrySet().parallelStream().forEach(entry -> {
            String fio = entry.getKey();
            Quadruple<String, Long, Long, Long> pair = entry.getValue();

            Long totalSum = pcs.getOrDefault(fio, Pair.of(0L, 0L)).getFirst();
            Long zpTotal = zps.values().stream()
                    .mapToLong(Quadruple::getSecond) // Извлекаем сумму зарплаты
                    .sum();
            Long newCompanyCount = newCompanies.getOrDefault(fio, 0L);
            Long newOrderCount = newOrders.getOrDefault(fio, Pair.of(0L, 0L)).getFirst();
            Long correctOrders = newOrders.getOrDefault(fio, Pair.of(0L, 0L)).getSecond();
            Long inVigul = inPublishAndVigul.getOrDefault(fio, Pair.of(0L, 0L)).getFirst();
            Long inPublishCount = inPublishAndVigul.getOrDefault(fio, Pair.of(0L, 0L)).getSecond();
            Long imageId = imagesIds.getOrDefault(fio,  Pair.of(1L, 0L)).getFirst();
            Long userId = imagesIds.getOrDefault(fio,  Pair.of(1L, 0L)).getSecond();
//            Long ordersCount = orders.getOrDefault(fio, 0L);
            Long ordersCount = pair.getThird();
            Long reviewsCount = pair.getFourth();
//            Long reviewsCount = reviews.getOrDefault(fio, 0L);
            Long leadsNew = leadsNewAndInWork.getOrDefault(fio,  Pair.of(0L, 0L)).getFirst();
            Long leadsInWork = leadsNewAndInWork.getOrDefault(fio,  Pair.of(0L, 0L)).getSecond();
            Long percentInWork = 0L;
            if (leadsNew != 0 || leadsInWork != 0){
                percentInWork = (leadsInWork * 100) / leadsNew;
            };
            String role = pair.getFirst();

            // Сохраняем все данные в карту
            result.add(new UserData(fio, role, pair.getSecond(), totalSum, zpTotal, newCompanyCount, newOrderCount, correctOrders,
                    inVigul, inPublishCount, imageId, userId, ordersCount, reviewsCount, leadsNew, leadsInWork, percentInWork,
                    0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L));
        });

        // Возвращаем результат
//        System.out.println(displayResult(result));
        return result;
    }





    @Transactional
    public Map<String, UserData> getPersonalsAndCountToMapToOwner(Long userId) {
        RegistrationUserDTO user1 = userService.findById(userId);
        assert user1 != null;
        LocalDate localDate = LocalDate.now();
        LocalDate firstDayOfMonth = localDate.withDayOfMonth(1);
        LocalDate lastDayOfMonth = localDate.withDayOfMonth(localDate.lengthOfMonth());

        // Получаем данные о зарплатах
        Map<String, Pair<String, Long>> zps = zpService.getAllZpToMonthToTelegram(firstDayOfMonth, lastDayOfMonth);
        // Получаем данные о платежах
        Map<String, Pair<Long, Long>> pcs = paymentCheckService.getAllPaymentToMonth(firstDayOfMonth, lastDayOfMonth);
        // Получаем данные о новых компаниях
        Map<String, Long> newCompanies = companyService.getAllNewCompanies(firstDayOfMonth, lastDayOfMonth);
        // Получаем данные о новых заказах
        Map<String, Pair<Long, Long>> newOrders = orderService.getNewOrderAll("Новый", "Коррекция");
        // Получаем данные о заказах в публикации
        Map<String, Pair<Long, Long>> inPublishAndVigul = reviewService.getAllPublishAndVigul(firstDayOfMonth, localDate);
        // Получаем данные о всех заказах с разными данными
        Map<String, Map<String, Long>>  allOrders = orderService.getAllOrdersToMonthByStatus(firstDayOfMonth, lastDayOfMonth, "Новый", "В проверку", "На проверке", "Коррекция", "Опубликовано", "Выставлен счет", "Напоминание", "Не оплачено");
        Map<String, Long> leadsNewAndInWork = leadService.getAllLeadsToMonthToManager("Новый", firstDayOfMonth, lastDayOfMonth);
        Long zpTotal = zps.values().stream()
                .mapToLong(Pair::getSecond) // Извлекаем сумму зарплаты
                .sum();

        // Создаем карту для результатов
        Map<String, UserData> result = new HashMap<>();

        // Параллельная обработка данных и сохранение в карту
        zps.entrySet().parallelStream().forEach(entry -> {
            String fio = entry.getKey();
            Pair<String, Long> pair = entry.getValue();
            Long totalSum = pcs.getOrDefault(fio, Pair.of(0L, 0L)).getFirst();
            Long newCompanyCount = newCompanies.getOrDefault(fio, 0L);
//            Long newOrderCount = newOrders.getOrDefault(fio, Pair.of(0L, 0L)).getFirst();
//            Long correctOrders = newOrders.getOrDefault(fio, Pair.of(0L, 0L)).getSecond();
            Long inVigul = inPublishAndVigul.getOrDefault(fio, Pair.of(0L, 0L)).getFirst();
            Long inPublishCount = inPublishAndVigul.getOrDefault(fio, Pair.of(0L, 0L)).getSecond();
            Long leadsNew = leadsNewAndInWork.getOrDefault(fio,  0L);
            String role = pair.getFirst();
            // Счетчики заказов
            Map<String, Long> orders = allOrders.getOrDefault(fio, new HashMap<>());
            Long orderInNew = orders.getOrDefault("Новый", 0L);
            Long orderToCheck = orders.getOrDefault("В проверку", 0L);
            Long orderInCheck = orders.getOrDefault("На проверке", 0L);
            Long orderInCorrect = orders.getOrDefault("Коррекция", 0L);
            Long orderInPublished = orders.getOrDefault("Опубликовано", 0L);
            Long orderInWaitingPay1 = orders.getOrDefault("Выставлен счет", 0L);
            Long orderInWaitingPay2 = orders.getOrDefault("Напоминание", 0L);
            Long orderNoPay = orders.getOrDefault("Не оплачено", 0L);

            // Сохраняем все данные в карту
            result.put(fio, new UserData(fio, role, pair.getSecond(), totalSum, zpTotal, newCompanyCount, orderInNew, orderInCorrect, inVigul, inPublishCount, 1L,0L, 0L, 0L, leadsNew, null, null,
                    orderInNew, orderToCheck, orderInCheck, orderInCorrect, orderInPublished, orderInWaitingPay1, orderInWaitingPay2, orderNoPay));
        });

//        System.out.println(result);
        // Возвращаем результат
//        System.out.println(displayResult(result));

        // Собираем список ФИО менеджеров и их работников, которые закреплены за user1
        Set<String> allowedFio = user1.getManagers().stream()
                .flatMap(manager -> {
                    Stream<String> workersFio = manager.getUser().getWorkers().stream()
                            .map(worker -> worker.getUser().getFio()); // Или как у тебя называется поле с ФИО
                    return Stream.concat(Stream.of(manager.getUser().getFio()), workersFio);
                })
                .collect(Collectors.toSet());

// Фильтруем Map оставляя только нужных людей
        Map<String, UserData> filteredResult = result.entrySet().stream()
                .filter(entry -> allowedFio.contains(entry.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        return filteredResult;

//    return result;
    }

    @Transactional
    public Map<String, UserData> getPersonalsAndCountToMapToManager(Long userId) {
        RegistrationUserDTO user1 = userService.findById(userId);
        assert user1 != null;
        LocalDate localDate = LocalDate.now();
        LocalDate firstDayOfMonth = localDate.withDayOfMonth(1);
        LocalDate lastDayOfMonth = localDate.withDayOfMonth(localDate.lengthOfMonth());

        // Получаем данные о зарплатах
        Map<String, Pair<String, Long>> zps = zpService.getAllZpToMonthToTelegram(firstDayOfMonth, lastDayOfMonth);
        // Получаем данные о платежах
        Map<String, Pair<Long, Long>> pcs = paymentCheckService.getAllPaymentToMonth(firstDayOfMonth, lastDayOfMonth);
        // Получаем данные о новых компаниях
        Map<String, Long> newCompanies = companyService.getAllNewCompanies(firstDayOfMonth, lastDayOfMonth);
        // Получаем данные о новых заказах
        Map<String, Pair<Long, Long>> newOrders = orderService.getNewOrderAll("Новый", "Коррекция");
        // Получаем данные о заказах в публикации
        Map<String, Pair<Long, Long>> inPublishAndVigul = reviewService.getAllPublishAndVigul(firstDayOfMonth, localDate);
        // Получаем данные о всех заказах с разными данными
        Map<String, Map<String, Long>>  allOrders = orderService.getAllOrdersToMonthByStatus(firstDayOfMonth, lastDayOfMonth, "Новый", "В проверку", "На проверке", "Коррекция", "Опубликовано", "Выставлен счет", "Напоминание", "Не оплачено");
        Map<String, Long> leadsNewAndInWork = leadService.getAllLeadsToMonthToManager("Новый", firstDayOfMonth, lastDayOfMonth);
        Long zpTotal = zps.values().stream()
                .mapToLong(Pair::getSecond) // Извлекаем сумму зарплаты
                .sum();

        // Создаем карту для результатов
        Map<String, UserData> result = new HashMap<>();

        // Параллельная обработка данных и сохранение в карту
        zps.entrySet().parallelStream().forEach(entry -> {
            String fio = entry.getKey();
            Pair<String, Long> pair = entry.getValue();
            Long totalSum = pcs.getOrDefault(fio, Pair.of(0L, 0L)).getFirst();
            Long newCompanyCount = newCompanies.getOrDefault(fio, 0L);
//            Long newOrderCount = newOrders.getOrDefault(fio, Pair.of(0L, 0L)).getFirst();
//            Long correctOrders = newOrders.getOrDefault(fio, Pair.of(0L, 0L)).getSecond();
            Long inVigul = inPublishAndVigul.getOrDefault(fio, Pair.of(0L, 0L)).getFirst();
            Long inPublishCount = inPublishAndVigul.getOrDefault(fio, Pair.of(0L, 0L)).getSecond();
            Long leadsNew = leadsNewAndInWork.getOrDefault(fio,  0L);
            String role = pair.getFirst();
            // Счетчики заказов
            Map<String, Long> orders = allOrders.getOrDefault(fio, new HashMap<>());
            Long orderInNew = orders.getOrDefault("Новый", 0L);
            Long orderToCheck = orders.getOrDefault("В проверку", 0L);
            Long orderInCheck = orders.getOrDefault("На проверке", 0L);
            Long orderInCorrect = orders.getOrDefault("Коррекция", 0L);
            Long orderInPublished = orders.getOrDefault("Опубликовано", 0L);
            Long orderInWaitingPay1 = orders.getOrDefault("Выставлен счет", 0L);
            Long orderInWaitingPay2 = orders.getOrDefault("Напоминание", 0L);
            Long orderNoPay = orders.getOrDefault("Не оплачено", 0L);

            // Сохраняем все данные в карту
            result.put(fio, new UserData(fio, role, pair.getSecond(), totalSum, zpTotal, newCompanyCount, orderInNew, orderInCorrect, inVigul, inPublishCount, 1L,0L, 0L, 0L, leadsNew, null, null,
                    orderInNew, orderToCheck, orderInCheck, orderInCorrect, orderInPublished, orderInWaitingPay1, orderInWaitingPay2, orderNoPay));
        });

//        System.out.println(result);
        // Возвращаем результат
//        System.out.println(displayResult(result));

        // Собираем список ФИО менеджеров и их работников, которые закреплены за user1
        // Собираем список ФИО: user1 + его работники
        Set<String> allowedFio = Stream.concat(
                Stream.of(user1.getFio()), // Сам менеджер
                user1.getWorkers().stream()
                        .map(worker -> worker.getUser().getFio()) // Работники
        ).collect(Collectors.toSet());

// Фильтруем Map и оставляем только user1 и его работников
        Map<String, UserData> filteredResult = result.entrySet().stream()
                .filter(entry -> allowedFio.contains(entry.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        return filteredResult;


//    return result;
    }

    public String displayResultToManager(Map<String, UserData> result) {
        StringBuilder resultBuilder = new StringBuilder();

        // Сортируем сначала менеджеров по totalSum, затем остальных по salary
        List<Map.Entry<String, UserData>> sortedEntries = result.entrySet().stream()
                .sorted((entry1, entry2) -> {
                    UserData user1 = entry1.getValue();
                    UserData user2 = entry2.getValue();

                    boolean isManager1 = user1.getRole().equals("ROLE_MANAGER");
                    boolean isManager2 = user2.getRole().equals("ROLE_MANAGER");

                    if (isManager1 && isManager2) {
                        // Оба менеджеры → сортируем по убыванию суммы чеков
                        return Long.compare(user2.getTotalSum(), user1.getTotalSum());
                    } else if (!isManager1 && !isManager2) {
                        // Оба не менеджеры → сортируем по убыванию зарплаты
                        return Long.compare(user2.getSalary(), user1.getSalary());
                    }
                    // Менеджеры идут первыми
                    return Boolean.compare(isManager2, isManager1);
                })
                .collect(Collectors.toList());

        // Вычисляем общую выручку всех менеджеров
        long totalManagerRevenue = result.values().stream()
                .filter(user -> user.getRole().equals("ROLE_MANAGER"))
                .mapToLong(UserData::getTotalSum)
                .sum();

        // Фильтруем мапу, чтобы учесть только двух конкретных менеджеров
        long totalZp = result.values().stream().findFirst().get().getZpTotal();


//        System.out.println(totalZp);


        // Вычисляем количество новых компаний
        long totalNewCompanies = result.values().stream()
                .filter(user -> user.getRole().equals("ROLE_MANAGER"))
                .mapToLong(UserData::getNewCompanies)
                .sum();

        // ЗП менеджеров

        sortedEntries.stream()
                .filter(entry -> "ROLE_MANAGER".equals(entry.getValue().getRole()))
                .forEach(entry -> {
                    String fio = entry.getKey();
                    UserData userData = entry.getValue();
//                    String orderStatus = "Новые: " + userData.getOrderInNew() + " В проверку: " + userData.getOrderToCheck() + " На проверке: " + userData.getOrderInCheck()
//                            + " Коррекция: " + userData.getCorrectOrders() + " Опубликовано: " + userData.getOrderInPublished() + " Выставлен счет: " + userData.getOrderInWaitingPay1()
//                            + " Напоминание: " + userData.getOrderInWaitingPay2() + " Не оплачено: " + userData.getOrderNoPay();
                    String orderStatus = "Лиды: " + userData.getLeadsNew() +" В проверку: " + userData.getOrderToCheck() + " На проверке: " + userData.getOrderInCheck()
                            + " Опубликовано: " + userData.getOrderInPublished() + " Выставлен счет: " + userData.getOrderInWaitingPay1()
                            + " Напоминание: " + userData.getOrderInWaitingPay2() + " Не оплачено: " + userData.getOrderNoPay();
                    String orderStatsForWorkers = "\n"  + "Новых - " + userData.getNewOrders() + " Коррекция - " + userData.getCorrectOrders()
                            + " Выгул - " + userData.getInVigul() + " Публикация - " + userData.getInPublish();
                    resultBuilder.append(fio).append(": ").append(userData.getSalary()).append(" руб. ")
                            .append("\n\n")
                            .append(orderStatus)
//                            .append("\n\n")
                            .append("\n")
//                            .append("\nСтатусы Заказов: ")
                            .append(orderStatsForWorkers)
                            .append("\n\n");
                });

        // ЗП Работников
        resultBuilder.append("\nСпециалисты:\n");
        sortedEntries.stream()
                .filter(entry -> "ROLE_WORKER".equals(entry.getValue().getRole()))
                .forEach(entry -> {
                    String fio = entry.getKey();
                    UserData userData = entry.getValue();
                    String orderStats = "Новые - " + userData.getNewOrders() + " В коррекции - " + userData.getCorrectOrders()
                            + " В выгуле - " + userData.getInVigul() + " На публикации - " + userData.getInPublish();
                    resultBuilder.append(fio).append("\n")
                            .append(orderStats).append("\n\n");
                });

        return resultBuilder.toString();
    }



    @Transactional
    public Map<String, UserData> getPersonalsAndCountToMapToWorker(Long userId) {
        RegistrationUserDTO user1 = userService.findById(userId);
        assert user1 != null;
        LocalDate localDate = LocalDate.now();
        LocalDate firstDayOfMonth = localDate.withDayOfMonth(1);
        LocalDate lastDayOfMonth = localDate.withDayOfMonth(localDate.lengthOfMonth());

        // Получаем данные о зарплатах
        Map<String, Pair<String, Long>> zps = zpService.getAllZpToMonthToTelegram(firstDayOfMonth, lastDayOfMonth);
        // Получаем данные о платежах
        Map<String, Pair<Long, Long>> pcs = paymentCheckService.getAllPaymentToMonth(firstDayOfMonth, lastDayOfMonth);
        // Получаем данные о новых компаниях
        Map<String, Long> newCompanies = companyService.getAllNewCompanies(firstDayOfMonth, lastDayOfMonth);
        // Получаем данные о новых заказах
        Map<String, Pair<Long, Long>> newOrders = orderService.getNewOrderAll("Новый", "Коррекция");
        // Получаем данные о заказах в публикации
        Map<String, Pair<Long, Long>> inPublishAndVigul = reviewService.getAllPublishAndVigul(firstDayOfMonth, localDate);
        // Получаем данные о всех заказах с разными данными
        Map<String, Map<String, Long>>  allOrders = orderService.getAllOrdersToMonthByStatus(firstDayOfMonth, lastDayOfMonth, "Новый", "В проверку", "На проверке", "Коррекция", "Опубликовано", "Выставлен счет", "Напоминание", "Не оплачено");
        Map<String, Long> leadsNewAndInWork = leadService.getAllLeadsToMonthToManager("Новый", firstDayOfMonth, lastDayOfMonth);
        Long zpTotal = zps.values().stream()
                .mapToLong(Pair::getSecond) // Извлекаем сумму зарплаты
                .sum();

        // Создаем карту для результатов
        Map<String, UserData> result = new HashMap<>();

        // Параллельная обработка данных и сохранение в карту
        zps.entrySet().parallelStream().forEach(entry -> {
            String fio = entry.getKey();
            Pair<String, Long> pair = entry.getValue();
            Long totalSum = pcs.getOrDefault(fio, Pair.of(0L, 0L)).getFirst();
            Long newCompanyCount = newCompanies.getOrDefault(fio, 0L);
//            Long newOrderCount = newOrders.getOrDefault(fio, Pair.of(0L, 0L)).getFirst();
//            Long correctOrders = newOrders.getOrDefault(fio, Pair.of(0L, 0L)).getSecond();
            Long inVigul = inPublishAndVigul.getOrDefault(fio, Pair.of(0L, 0L)).getFirst();
            Long inPublishCount = inPublishAndVigul.getOrDefault(fio, Pair.of(0L, 0L)).getSecond();
            Long leadsNew = leadsNewAndInWork.getOrDefault(fio,  0L);
            String role = pair.getFirst();
            // Счетчики заказов
            Map<String, Long> orders = allOrders.getOrDefault(fio, new HashMap<>());
            Long orderInNew = orders.getOrDefault("Новый", 0L);
            Long orderToCheck = orders.getOrDefault("В проверку", 0L);
            Long orderInCheck = orders.getOrDefault("На проверке", 0L);
            Long orderInCorrect = orders.getOrDefault("Коррекция", 0L);
            Long orderInPublished = orders.getOrDefault("Опубликовано", 0L);
            Long orderInWaitingPay1 = orders.getOrDefault("Выставлен счет", 0L);
            Long orderInWaitingPay2 = orders.getOrDefault("Напоминание", 0L);
            Long orderNoPay = orders.getOrDefault("Не оплачено", 0L);

            // Сохраняем все данные в карту
            result.put(fio, new UserData(fio, role, pair.getSecond(), totalSum, zpTotal, newCompanyCount, orderInNew, orderInCorrect, inVigul, inPublishCount, 1L,0L, 0L, 0L, leadsNew, null, null,
                    orderInNew, orderToCheck, orderInCheck, orderInCorrect, orderInPublished, orderInWaitingPay1, orderInWaitingPay2, orderNoPay));
        });

//        System.out.println(result);
        // Возвращаем результат
//        System.out.println(displayResult(result));

        // Собираем список ФИО менеджеров и их работников, которые закреплены за user1
        // Собираем список ФИО: user1 + его работники
        Set<String> allowedFio = Stream.concat(
                Stream.of(user1.getFio()), // Сам менеджер
                user1.getWorkers().stream()
                        .map(worker -> worker.getUser().getFio()) // Работники
        ).collect(Collectors.toSet());

// Фильтруем Map и оставляем только user1 и его работников
        Map<String, UserData> filteredResult = result.entrySet().stream()
                .filter(entry -> allowedFio.contains(entry.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        return filteredResult;


//    return result;
    }

    public String displayResultToWorker(Map<String, UserData> result) {
        StringBuilder resultBuilder = new StringBuilder();

        // Сортируем сначала менеджеров по totalSum, затем остальных по salary
        List<Map.Entry<String, UserData>> sortedEntries = result.entrySet().stream()
                .sorted((entry1, entry2) -> {
                    UserData user1 = entry1.getValue();
                    UserData user2 = entry2.getValue();

                    boolean isManager1 = user1.getRole().equals("ROLE_MANAGER");
                    boolean isManager2 = user2.getRole().equals("ROLE_MANAGER");

                    if (isManager1 && isManager2) {
                        // Оба менеджеры → сортируем по убыванию суммы чеков
                        return Long.compare(user2.getTotalSum(), user1.getTotalSum());
                    } else if (!isManager1 && !isManager2) {
                        // Оба не менеджеры → сортируем по убыванию зарплаты
                        return Long.compare(user2.getSalary(), user1.getSalary());
                    }
                    // Менеджеры идут первыми
                    return Boolean.compare(isManager2, isManager1);
                })
                .collect(Collectors.toList());

        // Вычисляем общую выручку всех менеджеров
        long totalManagerRevenue = result.values().stream()
                .filter(user -> user.getRole().equals("ROLE_MANAGER"))
                .mapToLong(UserData::getTotalSum)
                .sum();

        // Фильтруем мапу, чтобы учесть только двух конкретных менеджеров
        long totalZp = result.values().stream().findFirst().get().getZpTotal();


//        System.out.println(totalZp);


        // Вычисляем количество новых компаний
        long totalNewCompanies = result.values().stream()
                .filter(user -> user.getRole().equals("ROLE_MANAGER"))
                .mapToLong(UserData::getNewCompanies)
                .sum();

        // ЗП менеджеров

        sortedEntries.stream()
                .filter(entry -> "ROLE_MANAGER".equals(entry.getValue().getRole()))
                .forEach(entry -> {
                    String fio = entry.getKey();
                    UserData userData = entry.getValue();
//                    String orderStatus = "Новые: " + userData.getOrderInNew() + " В проверку: " + userData.getOrderToCheck() + " На проверке: " + userData.getOrderInCheck()
//                            + " Коррекция: " + userData.getCorrectOrders() + " Опубликовано: " + userData.getOrderInPublished() + " Выставлен счет: " + userData.getOrderInWaitingPay1()
//                            + " Напоминание: " + userData.getOrderInWaitingPay2() + " Не оплачено: " + userData.getOrderNoPay();
                    String orderStatus = "Лиды: " + userData.getLeadsNew() +" В проверку: " + userData.getOrderToCheck() + " На проверке: " + userData.getOrderInCheck()
                            + " Опубликовано: " + userData.getOrderInPublished() + " Выставлен счет: " + userData.getOrderInWaitingPay1()
                            + " Напоминание: " + userData.getOrderInWaitingPay2() + " Не оплачено: " + userData.getOrderNoPay();
                    String orderStatsForWorkers = "\n"  + "Новых - " + userData.getNewOrders() + " Коррекция - " + userData.getCorrectOrders()
                            + " Выгул - " + userData.getInVigul() + " Публикация - " + userData.getInPublish();
                    resultBuilder.append(fio).append(": ").append(userData.getSalary()).append(" руб. ")
                            .append("\n\n")
                            .append(orderStatus)
//                            .append("\n\n")
                            .append("\n")
//                            .append("\nСтатусы Заказов: ")
                            .append(orderStatsForWorkers)
                            .append("\n\n");
                });

        // ЗП Работников
        sortedEntries.stream()
                .filter(entry -> "ROLE_WORKER".equals(entry.getValue().getRole()))
                .forEach(entry -> {
                    String fio = entry.getKey();
                    UserData userData = entry.getValue();
                    String orderStats = "Новые - " + userData.getNewOrders() + " В коррекции - " + userData.getCorrectOrders()
                            + " В выгуле - " + userData.getInVigul() + " На публикации - " + userData.getInPublish();
                    resultBuilder.append(fio).append(": ").append(userData.getSalary())
                            .append(" руб.  ")
                            .append("\n\n")
                            .append("Статусы заказов:")
                            .append("\n")
                            .append(orderStats).append("\n\n");
                });

        return resultBuilder.toString();
    }







// Списки менеджеров со статичтикой и счетчиками для Владельцев.
    public List<ManagersListDTO> getManagersAndCountToOwner(List<Manager> managers) {
        return managers.stream().map(this::toManagersListDTOAndCount).toList();
    }
    public List<MarketologsListDTO> getMarketologsAndCountToOwner(List<Marketolog> allMarketologs){
        return allMarketologs.stream().map(this::toMarketologsListDTOAndCount).toList();
    }
    public List<WorkersListDTO> getWorkersToAndCountToOwner(List<Worker> allWorkers) {
        return allWorkers.stream().map(this::toWorkersListDTOAndCount).toList();
    }
    public List<OperatorsListDTO> getOperatorsAndCountToOwner(List<Operator> allOperators) {
        return allOperators.stream().map(this::toOperatorsListDTOAndCount).toList();
    }




    private ManagersListDTO toManagersListDTO(Manager manager){
        Long imageId = manager.getUser().getImage() != null ? manager.getUser().getImage().getId() : 1L;
        return ManagersListDTO.builder()
                .id(manager.getId())
                .userId(manager.getUser().getId())
                .fio(manager.getUser().getFio())
                .login(manager.getUser().getUsername())
                .imageId(imageId)
                .build();
    }

    private MarketologsListDTO toMarketologsListDTO(Marketolog marketolog){
        Long imageId = marketolog.getUser().getImage() != null ? marketolog.getUser().getImage().getId() : 1L;
        return MarketologsListDTO.builder()
                .id(marketolog.getId())
                .userId(marketolog.getUser().getId())
                .fio(marketolog.getUser().getFio())
                .login(marketolog.getUser().getUsername())
                .imageId(imageId)
                .build();
    }

    private WorkersListDTO toWorkersListDTO(Worker worker){
        Long imageId = worker.getUser().getImage() != null ? worker.getUser().getImage().getId() : 1L;
        return WorkersListDTO.builder()
                .id(worker.getId())
                .userId(worker.getUser().getId())
                .fio(worker.getUser().getFio())
                .login(worker.getUser().getUsername())
                .imageId(imageId)
                .build();
    }

    private OperatorsListDTO toOperatorsListDTO(Operator operator){
        Long imageId = operator.getUser().getImage() != null ? operator.getUser().getImage().getId() : 1L;
        return OperatorsListDTO.builder()
                .id(operator.getId())
                .userId(operator.getUser().getId())
                .fio(operator.getUser().getFio())
                .login(operator.getUser().getUsername())
                .imageId(imageId)
                .build();
    }

    private ManagersListDTO toManagersListDTOAndCount(Manager manager){
        LocalDate localDate = LocalDate.now();
        LocalDate firstDayOfMonth = localDate.withDayOfMonth(1);
        LocalDate lastDayOfMonth = localDate.withDayOfMonth(localDate.lengthOfMonth());
        List<Zp> zps = zpService.getAllWorkerZp(manager.getUser().getUsername());
        BigDecimal sum30 = zps.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add); // сумма ЗП
        List<PaymentCheck> pcs = paymentCheckService.getAllWorkerPaymentToDate(manager.getUser().getId(), firstDayOfMonth, lastDayOfMonth);
        BigDecimal sum30Payments = pcs.stream().map(PaymentCheck::getSum).reduce(BigDecimal.ZERO, BigDecimal::add); // сумма Выручки
        Long imageId = manager.getUser().getImage() != null ? manager.getUser().getImage().getId() : 1L;
        Set<Manager> managerList = new HashSet<>();
        managerList.add(manager);
        List<Long> inWorkleadList = leadService.getAllLeadsByDateAndStatusToOwnerForTelegram(localDate, "В работе", managerList);// берем всех лидов за текущий месяц + статус
        System.out.println(inWorkleadList.size());
        return ManagersListDTO.builder()
                .id(manager.getId())
                .userId(manager.getUser().getId())
                .fio(manager.getUser().getFio())
                .login(manager.getUser().getUsername())
                .imageId(imageId)
                .sum1Month(sum30.intValue())
                .order1Month(zps.size())
                .review1Month(zps.stream().mapToInt(Zp::getAmount).sum())
                .payment1Month(sum30Payments.intValue())
                .leadsInWorkInMonth(inWorkleadList.size())
                .build();
    }

    private MarketologsListDTO toMarketologsListDTOAndCount(Marketolog marketolog){
        LocalDate localDate = LocalDate.now();
        List<Zp> zps = zpService.getAllWorkerZp(marketolog.getUser().getUsername());
        BigDecimal sum30 = zps.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add); // первая сумма
        Long imageId = marketolog.getUser().getImage() != null ? marketolog.getUser().getImage().getId() : 1L;
        Long newListLeadsToMarketolog = leadService.findAllByLidListNew(marketolog);
        Long inWorkListLeadsToMarketolog = leadService.findAllByLidListStatusInWork(marketolog);
        Long percentInWork = 0L;
        if (newListLeadsToMarketolog != 0 && inWorkListLeadsToMarketolog != 0){
            percentInWork = (inWorkListLeadsToMarketolog * 100) / newListLeadsToMarketolog;
        }
        return MarketologsListDTO.builder()
                .id(marketolog.getId())
                .userId(marketolog.getUser().getId())
                .fio(marketolog.getUser().getFio())
                .login(marketolog.getUser().getUsername())
                .imageId(imageId)
                .sum1Month(sum30.intValue())
                .order1Month(zps.size())
                .review1Month(zps.stream().mapToInt(Zp::getAmount).sum())
                .leadsNew(newListLeadsToMarketolog)
                .leadsInWork(inWorkListLeadsToMarketolog)
                .percentInWork(percentInWork)
                .build();
    }

    private WorkersListDTO toWorkersListDTOAndCount(Worker worker){
        LocalDate localDate = LocalDate.now();
        List<Zp> zps = zpService.getAllWorkerZp(worker.getUser().getUsername());
        BigDecimal sum30 = zps.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add); // первая сумма
        Long imageId = worker.getUser().getImage() != null ? worker.getUser().getImage().getId() : 1L;
        int newOrderInt = orderService.countOrdersByWorkerAndStatus(worker, "Новый");
        int inCorrectInt = orderService.countOrdersByWorkerAndStatus(worker, "Коррекция");
        int inVigulInt = reviewService.countOrdersByWorkerAndStatusVigul(worker, localDate);
        int inPublishInt = reviewService.countOrdersByWorkerAndStatusPublish(worker, localDate);
        return WorkersListDTO.builder()
                .id(worker.getId())
                .userId(worker.getUser().getId())
                .fio(worker.getUser().getFio())
                .login(worker.getUser().getUsername())
                .imageId(imageId)
                .sum1Month(sum30.intValue())
                .order1Month(zps.size())
                .review1Month(zps.stream().mapToInt(Zp::getAmount).sum())
                .newOrder(newOrderInt)
                .inCorrect(inCorrectInt)
                .intVigul(inVigulInt)
                .publish(inPublishInt)
                .build();
    }




    private OperatorsListDTO toOperatorsListDTOAndCount(Operator operator){
        LocalDate localDate = LocalDate.now();
        List<Zp> zps = zpService.getAllWorkerZp(operator.getUser().getUsername());
        BigDecimal sum30 = zps.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add); // первая сумма
        Long imageId = operator.getUser().getImage() != null ? operator.getUser().getImage().getId() : 1L;
        Long newListLeadsToOperators = leadService.findAllByLidListNew(operator);
        Long inWorkListLeadsToOperators = leadService.findAllByLidListStatusInWork(operator);
        Long percentInWork = 0L;
        if (newListLeadsToOperators != 0 && inWorkListLeadsToOperators != 0){
            percentInWork = (inWorkListLeadsToOperators * 100) / newListLeadsToOperators;
        }
        return OperatorsListDTO.builder()
                .id(operator.getId())
                .userId(operator.getUser().getId())
                .fio(operator.getUser().getFio())
                .login(operator.getUser().getUsername())
                .imageId(imageId)
                .sum1Month(sum30.intValue())
                .order1Month(zps.size())
                .review1Month(zps.stream().mapToInt(Zp::getAmount).sum())
                .leadsNew(newListLeadsToOperators)
                .leadsInWork(inWorkListLeadsToOperators)
                .percentInWork(percentInWork)
                .build();
    }







//


    public List<ManagersListDTO> getManagersAndCountToDate(LocalDate localdate){
        return managerService.getAllManagers().stream().map(manager -> toManagersListDTOAndCountToDate(manager, localdate)).collect(Collectors.toList());
    }
    public List<MarketologsListDTO> getMarketologsAndCountToDate(LocalDate localdate){
        return marketologService.getAllMarketologs().stream().map(marketolog -> toMarketologsListDTOAndCountToDate(marketolog, localdate)).collect(Collectors.toList());
    }
    public List<WorkersListDTO> gerWorkersToAndCountToDate(LocalDate localdate){
        return workerService.getAllWorkers().stream().map(worker -> toWorkersListDTOAndCountToDate(worker, localdate)).collect(Collectors.toList());
    }
    public List<OperatorsListDTO> gerOperatorsAndCountToDate(LocalDate localdate){
        return operatorService.getAllOperators().stream().map(operator -> toOperatorsListDTOAndCountToDate(operator, localdate)).collect(Collectors.toList());
    }

    public List<ManagersListDTO> getManagersAndCountToDateToOwner(List<Manager> managerList, LocalDate localdate){
        return managerList.stream().map(manager -> toManagersListDTOAndCountToDate(manager, localdate)).collect(Collectors.toList());
    }
    public List<MarketologsListDTO> getMarketologsAndCountToDateToOwner(List<Marketolog> marketologList, LocalDate localdate){
        return marketologList.stream().map(marketolog -> toMarketologsListDTOAndCountToDate(marketolog, localdate)).collect(Collectors.toList());
    }
    public List<WorkersListDTO> gerWorkersToAndCountToDateToOwner(List<Worker> workerList, LocalDate localdate){
        return workerList.stream().map(worker -> toWorkersListDTOAndCountToDate(worker, localdate)).collect(Collectors.toList());
    }
    public List<OperatorsListDTO> gerOperatorsAndCountToDateToOwner(List<Operator> operatorList, LocalDate localdate){
        return operatorList.stream().map(operator -> toOperatorsListDTOAndCountToDate(operator, localdate)).collect(Collectors.toList());
    }

    private ManagersListDTO toManagersListDTOAndCountToDate(Manager manager, LocalDate localDate){
        LocalDate firstDayOfMonth = localDate.withDayOfMonth(1);
        LocalDate lastDayOfMonth = localDate.withDayOfMonth(localDate.lengthOfMonth());
        List<Zp> zps = zpService.getAllWorkerZpToDate(manager.getUser().getUsername(), firstDayOfMonth, lastDayOfMonth);
        BigDecimal sum30 = zps.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add); // сумма ЗП
        List<PaymentCheck> pcs = paymentCheckService.getAllWorkerPaymentToDate(manager.getUser().getId(), firstDayOfMonth, localDate);
        BigDecimal sum30Payments = pcs.stream().map(PaymentCheck::getSum).reduce(BigDecimal.ZERO, BigDecimal::add); // сумма Выручки
        Long imageId = manager.getUser().getImage() != null ? manager.getUser().getImage().getId() : 1L;
        return ManagersListDTO.builder()
                .id(manager.getId())
                .userId(manager.getUser().getId())
                .fio(manager.getUser().getFio())
                .login(manager.getUser().getUsername())
                .imageId(imageId)
                .sum1Month(sum30.intValue())
                .order1Month(zps.size())
                .review1Month(zps.stream().mapToInt(Zp::getAmount).sum())
                .payment1Month(sum30Payments.intValue())
                .build();
    }

    private MarketologsListDTO toMarketologsListDTOAndCountToDate(Marketolog marketolog, LocalDate localDate){
//        List<Zp> zps = zpService.getAllWorkerZpToDate(marketolog.getUser().getUsername(), localDate);
        LocalDate firstDayOfMonth = localDate.withDayOfMonth(1);
        LocalDate lastDayOfMonth = localDate.withDayOfMonth(localDate.lengthOfMonth());
        List<Zp> zps = zpService.getAllWorkerZpToDate(marketolog.getUser().getUsername(), firstDayOfMonth, lastDayOfMonth);
        BigDecimal sum30 = zps.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add); // первая сумма
        Long imageId = marketolog.getUser().getImage() != null ? marketolog.getUser().getImage().getId() : 1L;
        Long newListLeadsToMarketolog = leadService.findAllByLidListNewToDate(marketolog, localDate);
        Long inWorkListLeadsToMarketolog = leadService.findAllByLidListStatusInWorkToDate(marketolog, localDate);
        Long percentInWork = 0L;
        if (newListLeadsToMarketolog != 0 || inWorkListLeadsToMarketolog != 0){
            percentInWork = (inWorkListLeadsToMarketolog * 100) / newListLeadsToMarketolog;
        }
        return MarketologsListDTO.builder()
                .id(marketolog.getId())
                .userId(marketolog.getUser().getId())
                .fio(marketolog.getUser().getFio())
                .login(marketolog.getUser().getUsername())
                .imageId(imageId)
                .sum1Month(sum30.intValue())
                .order1Month(zps.size())
                .review1Month(zps.stream().mapToInt(Zp::getAmount).sum())
                .leadsNew(newListLeadsToMarketolog)
                .leadsInWork(inWorkListLeadsToMarketolog)
                .percentInWork(percentInWork)
                .build();
    }

    private WorkersListDTO toWorkersListDTOAndCountToDate(Worker worker, LocalDate localDate){
//        List<Zp> zps = zpService.getAllWorkerZpToDate(worker.getUser().getUsername(), localDate);
        LocalDate firstDayOfMonth = localDate.withDayOfMonth(1);
        LocalDate lastDayOfMonth = localDate.withDayOfMonth(localDate.lengthOfMonth());
        List<Zp> zps = zpService.getAllWorkerZpToDate(worker.getUser().getUsername(), firstDayOfMonth, lastDayOfMonth);
        BigDecimal sum30 = zps.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add); // первая сумма
        Long imageId = worker.getUser().getImage() != null ? worker.getUser().getImage().getId() : 1L;
        return WorkersListDTO.builder()
                .id(worker.getId())
                .userId(worker.getUser().getId())
                .fio(worker.getUser().getFio())
                .login(worker.getUser().getUsername())
                .imageId(imageId)
                .sum1Month(sum30.intValue())
                .order1Month(zps.size())
                .review1Month(zps.stream().mapToInt(Zp::getAmount).sum())
                .build();
    }

    private OperatorsListDTO toOperatorsListDTOAndCountToDate(Operator operator, LocalDate localDate){
//        List<Zp> zps = zpService.getAllWorkerZpToDate(operator.getUser().getUsername(), localDate);
        LocalDate firstDayOfMonth = localDate.withDayOfMonth(1);
        LocalDate lastDayOfMonth = localDate.withDayOfMonth(localDate.lengthOfMonth());
        List<Zp> zps = zpService.getAllWorkerZpToDate(operator.getUser().getUsername(), firstDayOfMonth, lastDayOfMonth);
        BigDecimal sum30 = zps.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add); // первая сумма
        Long imageId = operator.getUser().getImage() != null ? operator.getUser().getImage().getId() : 1L;
        Long newListLeadsToOperators = leadService.findAllByLidListNewToDate(operator, localDate);
        Long inWorkListLeadsToOperators = leadService.findAllByLidListStatusInWorkToDate(operator, localDate);
        Long percentInWork = 0L;
        if (newListLeadsToOperators != 0 || inWorkListLeadsToOperators != 0){
            percentInWork = (inWorkListLeadsToOperators * 100) / newListLeadsToOperators;
        }
        return OperatorsListDTO.builder()
                .id(operator.getId())
                .userId(operator.getUser().getId())
                .fio(operator.getUser().getFio())
                .login(operator.getUser().getUsername())
                .imageId(imageId)
                .sum1Month(sum30.intValue())
                .order1Month(zps.size())
                .review1Month(zps.stream().mapToInt(Zp::getAmount).sum())
                .leadsNew(newListLeadsToOperators)
                .leadsInWork(inWorkListLeadsToOperators)
                .percentInWork(percentInWork)
                .build();
    }


    //    ========================================== PERSONAL LIST FINISH ==================================================
    private String getRole(Principal principal){
        // Получите текущий объект аутентификации
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        // Получите имя текущего пользователя (пользователя, не роль)
        String username = principal.getName();
        // Получите роль пользователя (предположим, что она хранится в поле "role" в объекте User)
        return ((UserDetails) authentication.getPrincipal()).getAuthorities().iterator().next().getAuthority();
    } // Берем роль пользователя
}



//    private BigDecimal calculatePercentageDifference(BigDecimal sum1, BigDecimal sum2) { // Оценка разницы 2х чисел в процентах
//
//        if (sum1.compareTo(BigDecimal.ZERO) <= 0 && sum2.compareTo(BigDecimal.ZERO) > 0) {
//            // Обработка случая, когда sum1 равно нулю
//            return sum2.divide(sum2, 2, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)).negate();
//        }
//        if (sum1.compareTo(BigDecimal.ZERO) > 0 && sum2.compareTo(BigDecimal.ZERO) <= 0) {
//            // Обработка случая, когда sum2 равно нулю
//            return sum1.divide(sum1, 2, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
//        }
//        if (sum1.compareTo(BigDecimal.ZERO) == 0 && sum2.compareTo(BigDecimal.ZERO) == 0) {
//            // Обработка случая, когда sum2 равно нулю
//            return BigDecimal.ZERO;
//        }
//        else {
//            BigDecimal difference = sum1.subtract(sum2); // разница между суммами
//            if (difference.compareTo(BigDecimal.ZERO) > 0){
//                return difference.divide(sum1, 2, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
//            }
//            if (difference.compareTo(BigDecimal.ZERO) < 0){
//                return difference.divide(sum2, 2, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
//            }
//            else return BigDecimal.ZERO;
//        }
//    } // Оценка разницы 2х чисел в процентах




//    private int percentageComparisonInt(int sum1, int sum2) {
//        if (sum1 <= 0 && sum2 > 0) {
//            // Обработка случая, когда sum1 равно нулю
//            return (int) (((double) sum2 / sum2) * 100) * -1;
//        }
//        if (sum1 > 0 && sum2 <= 0) {
//            // Обработка случая, когда sum2 равно нулю
//            return (int) (((double) sum1 / sum1) * 100);
//        }
//        if (sum1 == 0 && sum2 == 0) {
//            // Обработка случая, когда оба значения равны нулю
//            return 0;
//        } else {
//            int difference = sum1 - sum2; // разница между суммами
//            if (difference > 0) {
//                return (int) (((double) difference / sum1) * 100);
//            }
//            if (difference < 0) {
//                return (int) (((double) difference / sum2) * 100);
//            } else {
//                return 0;
//            }
//        }
//    }

//    private BigDecimal getPrice(Product product){
//        return product.getPrice();
//    }



//    ========================================== PERSONAL STAT START ==================================================

//    public StatDTO getStats() {
//        LocalDate localDate = LocalDate.now();
//        LocalDate date30DaysAgo = localDate.minusDays(30);
//        LocalDate firstDayOfMonth = localDate.withDayOfMonth(1);
//        LocalDate lowerBound = date30DaysAgo.isAfter(firstDayOfMonth) ? date30DaysAgo : firstDayOfMonth;
//        System.out.println(localDate);
//        //        ОПЛАТЫ Разбивка на списки 1-2-7-14-30-60-90-360-730 дней от текущей даты
//        List<PaymentCheck> pcs = paymentCheckService.findAll();
//        List<PaymentCheck> Pay1Day = pcs.stream().filter(p -> p.getCreated().isEqual(localDate.minusDays(1))).toList();
//        List<PaymentCheck> Pay7Day = pcs.stream().filter(p -> p.getCreated().isAfter(localDate.minusDays(7))).toList();
//        List<PaymentCheck> Pay30Day = pcs.stream().filter(p -> p.getCreated().isAfter(lowerBound)).toList();
//        List<PaymentCheck> Pay365Day = pcs.stream().filter(p -> p.getCreated().isAfter(localDate.minusDays(365))).toList();
//        List<PaymentCheck> Pay2Day = pcs.stream().filter(p -> p.getCreated().isEqual(localDate.minusDays(2))).toList();
//        List<PaymentCheck> Pay14Day = pcs.stream().filter(p -> p.getCreated().isAfter(localDate.minusDays(14)) && p.getCreated().isBefore(localDate.minusDays(7))).toList();
//        List<PaymentCheck> Pay60Day = pcs.stream().filter(p -> p.getCreated().isAfter(localDate.minusDays(60)) && p.getCreated().isBefore(localDate.minusDays(30))).toList();
//        List<PaymentCheck> Pay90Day = pcs.stream().filter(p -> p.getCreated().isAfter(localDate.minusDays(90)) && p.getCreated().isBefore(localDate.minusDays(60))).toList();
//        List<PaymentCheck> Pay730Day = pcs.stream().filter(p -> p.getCreated().isAfter(localDate.minusDays(730)) && p.getCreated().isBefore(localDate.minusDays(365))).toList();
//
//        //        ОПЛАТЫ Сумма всех выплат за 1-2-7-14-30-60-90-360-730 дней
//        BigDecimal sum1Pay = Pay1Day.stream().map(PaymentCheck::getSum).reduce(BigDecimal.ZERO, BigDecimal::add); // первая сумма
//        BigDecimal sum7Pay = Pay7Day.stream().map(PaymentCheck::getSum).reduce(BigDecimal.ZERO, BigDecimal::add); // первая сумма
//        BigDecimal sum30Pay = Pay30Day.stream().map(PaymentCheck::getSum).reduce(BigDecimal.ZERO, BigDecimal::add); // первая сумма
//        BigDecimal sum365Pay = Pay365Day.stream().map(PaymentCheck::getSum).reduce(BigDecimal.ZERO, BigDecimal::add); // первая сумма
//        BigDecimal sum2Pay = Pay2Day.stream().map(PaymentCheck::getSum).reduce(BigDecimal.ZERO, BigDecimal::add);
//        BigDecimal sum14Pay = Pay14Day.stream().map(PaymentCheck::getSum).reduce(BigDecimal.ZERO, BigDecimal::add);
//        BigDecimal sum60Pay = Pay60Day.stream().map(PaymentCheck::getSum).reduce(BigDecimal.ZERO, BigDecimal::add);
//        BigDecimal sum730Pay = Pay730Day.stream().map(PaymentCheck::getSum).reduce(BigDecimal.ZERO, BigDecimal::add);
//
//        //        ОПЛАТЫ Сумма всех заказов за 30-60-90 дней
//        BigDecimal sumCount1MonthPay = BigDecimal.valueOf(Pay30Day.size()); // 1 сумма
//        BigDecimal sumCount2MonthPay = BigDecimal.valueOf(Pay60Day.size()); // 2 сумма
//        BigDecimal sumCount3MonthPay = BigDecimal.valueOf(Pay90Day.size()); // 3 сумма
//
//
//
//        //      СТАТИСТИКА новых лидов и тех, что поступили в работу
//        List<Long> newleadList = leadService.getAllLeadsByDate(localDate); // берем всех лидов за текущий месяц
//        List<Long> inWorkleadList = leadService.getAllLeadsByDateAndStatus(localDate, "В работе"); // берем всех лидов за текущий месяц + статус
//        List<Long> newleadList2Month = leadService.getAllLeadsByDate2Month(localDate); // берем всех лидов за текущий месяц
//        List<Long> inWorkleadList2Month = leadService.getAllLeadsByDateAndStatus2Month(localDate, "В работе"); // берем всех лидов за текущий месяц + статус
//
//
//
//        //        ЗП Разбивка на списки 1-2-7-14-30-60-90-360-730 дней от текущей даты
//        List<Zp> zps = zpService.findAll();
//        List<Zp> zpPay1Day = zps.stream().filter(z -> z.getCreated().isEqual(localDate.minusDays(1))).toList();
//        List<Zp> zpPay7Day = zps.stream().filter(z -> z.getCreated().isAfter(localDate.minusDays(7))).toList();
//        List<Zp> zpPay30Day = zps.stream().filter(z -> z.getCreated().isAfter(localDate.minusDays(30))).toList();
//        List<Zp> zpPay365Day = zps.stream().filter(z -> z.getCreated().isAfter(localDate.minusDays(365))).toList();
//        List<Zp> zpPay2Day = zps.stream().filter(z -> z.getCreated().isEqual(localDate.minusDays(2))).toList();
//        List<Zp> zpPay14Day = zps.stream().filter(z -> z.getCreated().isAfter(localDate.minusDays(14)) && z.getCreated().isBefore(localDate.minusDays(7))).toList();
//        List<Zp> zpPay60Day = zps.stream().filter(z -> z.getCreated().isAfter(localDate.minusDays(60)) && z.getCreated().isBefore(localDate.minusDays(30))).toList();
//        List<Zp> zpPay90Day = zps.stream().filter(z -> z.getCreated().isAfter(localDate.minusDays(90)) && z.getCreated().isBefore(localDate.minusDays(60))).toList();
//        List<Zp> zpPay730Day = zps.stream().filter(z -> z.getCreated().isAfter(localDate.minusDays(730)) && z.getCreated().isBefore(localDate.minusDays(365))).toList();
//
//        //        ЗП Сумма всех выплат за 1-2-7-14-30-60-90-360-730 дней
//        BigDecimal sum1 = zpPay1Day.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add); // первая сумма
//        BigDecimal sum7 = zpPay7Day.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add); // первая сумма
//        BigDecimal sum30 = zpPay30Day.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add); // первая сумма
//        BigDecimal sum365 = zpPay365Day.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add); // первая сумма
//        BigDecimal sum2 = zpPay2Day.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add);
//        BigDecimal sum14 = zpPay14Day.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add);
//        BigDecimal sum60 = zpPay60Day.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add);
//        BigDecimal sum730 = zpPay730Day.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add);
//
//        //        ЗП Сумма всех заказов за 30-60-90 дней
//        BigDecimal sumCount1Month = BigDecimal.valueOf(zpPay30Day.size()); // 1 сумма
//        BigDecimal sumCount2Month = BigDecimal.valueOf(zpPay60Day.size()); // 2 сумма
//        BigDecimal sumCount3Month = BigDecimal.valueOf(zpPay90Day.size()); // 3 сумма
//
//
//        Long imageId = 1L;
//        StatDTO statDTO = new StatDTO();
//        statDTO.setOrderPayMap(getJSON(getDailySalarySumMap(localDate, pcs)));
//        statDTO.setSum1DayPay(sum1Pay.intValue());
//        statDTO.setSum1WeekPay(sum7Pay.intValue());
//        statDTO.setSum1MonthPay(sum30Pay.intValue());
//        statDTO.setSum1YearPay(sum365Pay.intValue());
//        statDTO.setSumOrders1MonthPay(Pay30Day.size());
//        statDTO.setSumOrders2MonthPay(Pay60Day.size());
//        statDTO.setPercent1DayPay(calculatePercentageDifference(sum1Pay, sum2Pay).intValue());
//        statDTO.setPercent1WeekPay(calculatePercentageDifference(sum7Pay, sum14Pay).intValue());
//        statDTO.setPercent1MonthPay(calculatePercentageDifference(sum30Pay, sum60Pay).intValue());
//        statDTO.setPercent1YearPay(calculatePercentageDifference(sum365Pay, sum730Pay).intValue());
//        statDTO.setPercent1MonthOrdersPay(calculatePercentageDifference(sumCount1MonthPay, sumCount2MonthPay).intValue());
//        statDTO.setPercent2MonthOrdersPay(calculatePercentageDifference(sumCount2MonthPay, sumCount3MonthPay).intValue());
//        statDTO.setNewLeads(newleadList.size());
//        statDTO.setLeadsInWork(inWorkleadList.size());
//        statDTO.setPercent1NewLeadsPay(calculatePercentageDifference(newleadList.size(), newleadList2Month.size()));
//        statDTO.setPercent2InWorkLeadsPay(calculatePercentageDifference(inWorkleadList.size(), inWorkleadList2Month.size()));
//
//        statDTO.setZpPayMap(getJSON(calculateDailyZpSumForMonth(localDate, zps)));
//        statDTO.setSum1Day(sum1.intValue());
//        statDTO.setSum1Week(sum7.intValue());
//        statDTO.setSum1Month(sum30.intValue());
//        statDTO.setSum1Year(sum365.intValue());
//        statDTO.setSumOrders1Month(zpPay30Day.size());
//        statDTO.setSumOrders2Month(zpPay60Day.size());
//        statDTO.setPercent1Day(calculatePercentageDifference(sum1, sum2).intValue());
//        statDTO.setPercent1Week(calculatePercentageDifference(sum7, sum14).intValue());
//        statDTO.setPercent1Month(calculatePercentageDifference(sum30, sum60).intValue());
//        statDTO.setPercent1Year(calculatePercentageDifference(sum365, sum730).intValue());
//        statDTO.setPercent1MonthOrders(calculatePercentageDifference(sumCount1Month, sumCount2Month).intValue());
//        statDTO.setPercent2MonthOrders(calculatePercentageDifference(sumCount2Month, sumCount3Month).intValue());
//        return statDTO;
//    }









//    ========================================== PERSONAL STAT FINISH ==================================================




//        Map<Integer, BigDecimal> zpPayMap = zps.stream()
//                .filter(z -> z.getCreated().isAfter(localDate.minusDays(30)))
//                .collect(Collectors.groupingBy(
//                        z -> z.getCreated().getDayOfMonth(),
//                        Collectors.reducing(BigDecimal.ZERO, Zp::getSum, BigDecimal::add)
//                ));

// Создаем отображение для всех 31 дней текущего месяца
//        Map<Integer, BigDecimal> zpPayMap = new HashMap<>();
//        for (int i = 1; i <= 31; i++) {
//            zpPayMap.put(i, BigDecimal.ZERO);
//        }
//
//        // Обновляем значения, если они присутствуют в вашем списке
//        for (Zp zp : zps) {
//            int dayOfMonth = zp.getCreated().getDayOfMonth();
//            BigDecimal currentSum = zpPayMap.get(dayOfMonth);
//            if (currentSum != null) {
//                zpPayMap.put(dayOfMonth, currentSum.add(zp.getSum()));
//            }
//        }








//    private Map<Integer, BigDecimal> getMapZp1Month(LocalDate desiredDate, List<Zp> zps){ //Создание мапы день-сумма зп
//        Map<Integer, BigDecimal> zpPayMapOnMonth = new HashMap<>();
//        for (int i = 1; i <= desiredDate.lengthOfMonth(); i++) {
//            zpPayMapOnMonth.put(i, BigDecimal.ZERO);
//        }
//
//        for (Zp zp : zps) {
//            if (zp.getCreated().getMonth() == desiredDate.getMonth()) {
//                int dayOfMonth = zp.getCreated().getDayOfMonth();
//                BigDecimal currentSum = zpPayMapOnMonth.get(dayOfMonth);
//                if (currentSum != null) {
//                    zpPayMapOnMonth.put(dayOfMonth, currentSum.add(zp.getSum()));
//                }
//            }
//        }
//        return zpPayMapOnMonth;
//    } //Создание мапы день-сумма зп

//    private Map<Integer, BigDecimal> getMapPay1Month(LocalDate desiredDate, List<PaymentCheck> pcs){ //Создание мапы день-сумма зп
//        Map<Integer, BigDecimal> zpPayMapOnMonth = new HashMap<>();
//        for (int i = 1; i <= desiredDate.lengthOfMonth(); i++) {
//            zpPayMapOnMonth.put(i, BigDecimal.ZERO);
//        }
//
//        for (PaymentCheck paymentCheck : pcs) {
//            if (paymentCheck.getCreated().getMonth() == desiredDate.getMonth()) {
//                int dayOfMonth = paymentCheck.getCreated().getDayOfMonth();
//                BigDecimal currentSum = zpPayMapOnMonth.get(dayOfMonth);
//                if (currentSum != null) {
//                    zpPayMapOnMonth.put(dayOfMonth, currentSum.add(paymentCheck.getSum()));
//                }
//            }
//        }
//        return zpPayMapOnMonth;
//    } //Создание мапы день-сумма зп

//    private ObjectMapper objectMapper;
//
//    public YourClass(ObjectMapper objectMapper){
//        this.objectMapper = objectMapper;
//    }
//
//    public String getJsonFromMap(Map<Integer, BigDecimal> payMapPerMonth) {
//        try {
//            return objectMapper.writeValueAsString(payMapPerMonth);
//        } catch (JsonProcessingException exception) {
//            throw new RuntimeException("Произошла ошибка при преобразовании карты в JSON", exception);
//        }
//    }






//
//    private List<PaymentCheck> getPaymentChecks(LocalDate localDate, String role, Set<Manager> managerList) {
//        if ("ROLE_ADMIN".equals(role)) {
//            return paymentCheckService.findAllToDate(localDate);
//        }
//        if ("ROLE_OWNER".equals(role)) {
//            return paymentCheckService.findAllToDateByOwner(localDate, managerList);
//        }
//        return paymentCheckService.findAllToDate(localDate);
//    }
//
//    private List<Zp> getZarplataChecks(LocalDate localDate, String role, Set<Manager> managerList) {
//        if ("ROLE_ADMIN".equals(role)) {
//            return zpService.findAllToDate(localDate);
//        }
//        if ("ROLE_OWNER".equals(role)) {
//            return zpService.findAllToDateByOwner(localDate, managerList);
//        }
//        return zpService.findAllToDate(localDate);
//    }
//
//    private List<Long> getInWorkLeadList(String role, LocalDate localDate, Set<Manager> managerList) {
//        String status = "В работе";
//        if ("ROLE_ADMIN".equals(role)) {
//            return leadService.getAllLeadsByDateAndStatus(localDate, status);
//        }
//        if ("ROLE_OWNER".equals(role)) {
//            return leadService.getAllLeadsByDateAndStatusToOwner(localDate, status, managerList);
//        }
//        return leadService.getAllLeadsByDateAndStatus(localDate, status);
//    }
//
//    private List<Long> getNewLeadList(String role, LocalDate localDate, Set<Manager> managerList) {
//        if ("ROLE_ADMIN".equals(role)) {
//            return leadService.getAllLeadsByDate(localDate);
//        }
//        if ("ROLE_OWNER".equals(role)) {
//            return leadService.getAllLeadsByDateToOwner(localDate, managerList);
//        }
//        return leadService.getAllLeadsByDate(localDate);
//    }
//

//public UserStatDTO getWorkerReviews2(String login, LocalDate localDate) {
//        //        разбивка на списки 1-2-7-14-30-60-90-360-730 дней от текущей даты
//        LocalDate firstDayOfMonth = localDate.withDayOfMonth(1);
//        LocalDate lastDayOfMonth = localDate.withDayOfMonth(localDate.lengthOfMonth());
//        List<Zp> zps = zpService.getAllWorkerZpToDate(login, firstDayOfMonth, lastDayOfMonth);
////        List<Zp> zps = zpService.getAllWorkerZpToDate(login, localDate);
//        List<Zp> zpPay1Day = zps.stream().filter(z -> z.getCreated().isEqual(localDate.minusDays(1))).toList();
//        List<Zp> zpPay7Day = zps.stream().filter(z -> z.getCreated().isAfter(localDate.minusDays(7))).toList();
//        List<Zp> zpPay30Day = zps.stream().filter(z -> z.getCreated().isAfter(localDate.minusDays(30))).toList();
//        List<Zp> zpPay365Day = zps.stream().filter(z -> z.getCreated().isAfter(localDate.minusDays(365))).toList();
//        List<Zp> zpPay2Day = zps.stream().filter(z -> z.getCreated().isEqual(localDate.minusDays(2))).toList();
//        List<Zp> zpPay14Day = zps.stream().filter(z -> z.getCreated().isAfter(localDate.minusDays(14)) && z.getCreated().isBefore(localDate.minusDays(7))).toList();
//        List<Zp> zpPay60Day = zps.stream().filter(z -> z.getCreated().isAfter(localDate.minusDays(60)) && z.getCreated().isBefore(localDate.minusDays(30))).toList();
//        List<Zp> zpPay90Day = zps.stream().filter(z -> z.getCreated().isAfter(localDate.minusDays(90)) && z.getCreated().isBefore(localDate.minusDays(60))).toList();
//        List<Zp> zpPay730Day = zps.stream().filter(z -> z.getCreated().isAfter(localDate.minusDays(730)) && z.getCreated().isBefore(localDate.minusDays(365))).toList();
//
//        //        Сумма всех выплат за 1-2-7-14-30-60-90-360-730 дней
//        BigDecimal sum1 = zpPay1Day.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add); // первая сумма
//        BigDecimal sum7 = zpPay7Day.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add); // первая сумма
//        BigDecimal sum30 = zpPay30Day.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add); // первая сумма
//        BigDecimal sum365 = zpPay365Day.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add); // первая сумма
//        BigDecimal sum2 = zpPay2Day.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add);
//        BigDecimal sum14 = zpPay14Day.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add);
//        BigDecimal sum60 = zpPay60Day.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add);
//        BigDecimal sum730 = zpPay730Day.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add);
//
//        //        Сумма всех заказов за 30-60-90 дней
//        BigDecimal sumCount1Month = BigDecimal.valueOf(zpPay30Day.size()); // 1 сумма
//        BigDecimal sumCount2Month = BigDecimal.valueOf(zpPay60Day.size()); // 2 сумма
//        BigDecimal sumCount3Month = BigDecimal.valueOf(zpPay90Day.size()); // 3 сумма
//
//
//        User user = userService.findByUserName(login).orElseThrow();
//        Long imageId = user.getImage() != null ? user.getImage().getId() : 1L;
//        UserStatDTO userStatDTO = new UserStatDTO();
//        userStatDTO.setId(user.getId());
//        userStatDTO.setImageId(imageId);
//        userStatDTO.setFio(user.getFio());
//        userStatDTO.setCoefficient(user.getCoefficient());
//        userStatDTO.setZpPayMap(getJSON(calculateDailyZpSumForMonth(localDate, zps)));
//        userStatDTO.setSum1Day(sum1.intValue());
//        userStatDTO.setSum1Week(sum7.intValue());
//        userStatDTO.setSum1Month(sum30.intValue());
//        userStatDTO.setSum1Year(sum365.intValue());
//        userStatDTO.setSumOrders1Month(zpPay30Day.size());
//        userStatDTO.setSumOrders2Month(zpPay60Day.size());
//        userStatDTO.setPercent1Day(calculatePercentageDifference(sum1, sum2).intValue());
//        userStatDTO.setPercent1Week(calculatePercentageDifference(sum7, sum14).intValue());
//        userStatDTO.setPercent1Month(calculatePercentageDifference(sum30, sum60).intValue());
//        userStatDTO.setPercent1Year(calculatePercentageDifference(sum365, sum730).intValue());
//        userStatDTO.setPercent1MonthOrders(calculatePercentageDifference(sumCount1Month, sumCount2Month).intValue());
//        userStatDTO.setPercent2MonthOrders(calculatePercentageDifference(sumCount2Month, sumCount3Month).intValue());
//
//        return userStatDTO;
//    }




