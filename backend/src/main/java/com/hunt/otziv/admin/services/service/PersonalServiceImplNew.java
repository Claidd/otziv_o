//package com.hunt.otziv.admin.services;
//
//import com.fasterxml.jackson.core.JsonProcessingException;
//import com.fasterxml.jackson.databind.ObjectMapper;
//import com.hunt.otziv.admin.dto.personal_stat.StatDTO;
//import com.hunt.otziv.admin.dto.personal_stat.UserLKDTO;
//import com.hunt.otziv.admin.dto.personal_stat.UserStatDTO;
//import com.hunt.otziv.admin.dto.presonal.*;
//import com.hunt.otziv.admin.model.Quadruple;
//import com.hunt.otziv.c_companies.services.CompanyService;
//import com.hunt.otziv.l_lead.services.serv.LeadService;
//import com.hunt.otziv.p_products.services.service.OrderService;
//import com.hunt.otziv.r_review.services.ReviewService;
//import com.hunt.otziv.u_users.dto.RegistrationUserDTO;
//import com.hunt.otziv.u_users.model.*;
//import com.hunt.otziv.u_users.services.service.*;
//import com.hunt.otziv.z_zp.model.PaymentCheck;
//import com.hunt.otziv.z_zp.model.Zp;
//import com.hunt.otziv.z_zp.services.PaymentCheckService;
//import com.hunt.otziv.z_zp.services.ZpService;
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.data.util.Pair;
//import org.springframework.security.core.Authentication;
//import org.springframework.security.core.GrantedAuthority;
//import org.springframework.security.core.context.SecurityContextHolder;
//import org.springframework.security.core.userdetails.UserDetails;
//import org.springframework.stereotype.Service;
//import org.springframework.transaction.annotation.Transactional;
//
//import java.math.BigDecimal;
//import java.math.RoundingMode;
//import java.security.Principal;
//import java.time.LocalDate;
//import java.util.*;
//import java.util.function.Function;
//import java.util.function.Supplier;
//import java.util.stream.Collectors;
//import java.util.stream.IntStream;
//import java.util.stream.Stream;
//
//@Service
//@Slf4j
//@RequiredArgsConstructor
//public class PersonalServiceImplNew implements PersonalService {
//
//    private final ManagerService managerService;
//    private final MarketologService marketologService;
//    private final WorkerService workerService;
//    private final OperatorService operatorService;
//    private final ZpService zpService;
//    private final PaymentCheckService paymentCheckService;
//    private final UserService userService;
//    private final LeadService leadService;
//    private final ReviewService reviewService;
//    private final OrderService orderService;
//    private final CompanyService companyService;
//    private final ImageService imageService;
//    private final ObjectMapper objectMapper;
//
//    private static final String ROLE_ADMIN = "ROLE_ADMIN";
//    private static final String ROLE_OWNER = "ROLE_OWNER";
//    private static final String ROLE_MANAGER = "ROLE_MANAGER";
//    private static final String ROLE_WORKER = "ROLE_WORKER";
//
//    private static final String STATUS_IN_WORK = "В работе";
//    private static final String STATUS_NEW = "Новый";
//    private static final String STATUS_TO_CHECK = "В проверку";
//    private static final String STATUS_IN_CHECK = "На проверке";
//    private static final String STATUS_CORRECTION = "Коррекция";
//    private static final String STATUS_PUBLISHED = "Опубликовано";
//    private static final String STATUS_BILL_SENT = "Выставлен счет";
//    private static final String STATUS_REMINDER = "Напоминание";
//    private static final String STATUS_NOT_PAID = "Не оплачено";
//
//    private static final long DEFAULT_IMAGE_ID = 1L;
//    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
//    private static final int PERCENT_MULTIPLIER = 100;
//
//    private static final List<String> ORDER_STATUSES = List.of(
//            STATUS_NEW,
//            STATUS_TO_CHECK,
//            STATUS_IN_CHECK,
//            STATUS_CORRECTION,
//            STATUS_PUBLISHED,
//            STATUS_BILL_SENT,
//            STATUS_REMINDER,
//            STATUS_NOT_PAID
//    );
//
//    @Override
//    public UserLKDTO getUserLK(Principal principal) {
//        User user = userService.findByUserName(principal.getName()).orElseThrow();
//        UserLKDTO userLKDTO = new UserLKDTO();
//        userLKDTO.setUsername(user.getUsername());
//        userLKDTO.setRole(extractShortRole(user));
//        userLKDTO.setImage(resolveImageId(user));
//        userLKDTO.setLeadCount(leadService.findAllByLidListStatus(principal.getName()).size());
//        userLKDTO.setReviewCount(reviewService.findAllByReviewListStatus(principal.getName()));
//        return userLKDTO;
//    }
//
//    // ========================================== PERSONAL STAT START ==================================================
//
//    @Transactional
//    public StatDTO getStats(LocalDate localDate, User user, String role) {
//        Set<Manager> managerList = user.getManagers() != null ? user.getManagers() : Collections.emptySet();
//
//        List<PaymentCheck> pcs = getPaymentChecks(localDate, role, managerList);
//        List<Zp> zps = getZarplataChecks(localDate, role, managerList);
//
//        List<Long> newLeadList = getNewLeadList(role, localDate, managerList);
//        List<Long> inWorkLeadList = getInWorkLeadList(role, localDate, managerList);
//
//        List<Long> newLeadListPrevMonth = getNewLeadList(role, localDate.minusMonths(1), managerList);
//        List<Long> inWorkLeadListPrevMonth = getInWorkLeadList(role, localDate.minusMonths(1), managerList);
//
//        PeriodMetrics paymentMetrics = calculatePeriodMetrics(
//                pcs,
//                PaymentCheck::getCreated,
//                PaymentCheck::getSum,
//                localDate
//        );
//
//        PeriodMetrics zpMetrics = calculatePeriodMetrics(
//                zps,
//                Zp::getCreated,
//                Zp::getSum,
//                localDate
//        );
//
//        StatDTO statDTO = new StatDTO();
//
//        statDTO.setOrderPayMap(toJson(getDailySumMap(localDate, pcs, PaymentCheck::getCreated, PaymentCheck::getSum)));
//        statDTO.setOrderPayMapMonth(toJson(getYearlyMonthlySumMap(pcs, PaymentCheck::getCreated, PaymentCheck::getSum)));
//        statDTO.setZpPayMapMonth(toJson(getYearlyMonthlySumMap(zps, Zp::getCreated, Zp::getSum)));
//
//        statDTO.setSum1DayPay(paymentMetrics.getDay1Sum().intValue());
//        statDTO.setSum1WeekPay(paymentMetrics.getWeek1Sum().intValue());
//        statDTO.setSum1MonthPay(paymentMetrics.getMonth1Sum().intValue());
//        statDTO.setSum1YearPay(paymentMetrics.getYear1Sum().intValue());
//
//        statDTO.setSumOrders1MonthPay(paymentMetrics.getMonth1Count());
//        statDTO.setSumOrders2MonthPay(paymentMetrics.getMonth2Count());
//
//        statDTO.setPercent1DayPay(calculatePercentageDifference(paymentMetrics.getDay1Sum(), paymentMetrics.getDay2Sum()).intValue());
//        statDTO.setPercent1WeekPay(calculatePercentageDifference(paymentMetrics.getWeek1Sum(), paymentMetrics.getWeek2Sum()).intValue());
//        statDTO.setPercent1MonthPay(calculatePercentageDifference(paymentMetrics.getMonth1Sum(), paymentMetrics.getMonth2Sum()).intValue());
//        statDTO.setPercent1YearPay(calculatePercentageDifference(paymentMetrics.getYear1Sum(), paymentMetrics.getYear2Sum()).intValue());
//        statDTO.setPercent1MonthOrdersPay(calculatePercentageDifference(paymentMetrics.getMonth1Count(), paymentMetrics.getMonth2Count()));
//        statDTO.setPercent2MonthOrdersPay(calculatePercentageDifference(paymentMetrics.getMonth2Count(), paymentMetrics.getMonth3Count()));
//
//        statDTO.setNewLeads(newLeadList.size());
//        statDTO.setLeadsInWork(inWorkLeadList.size());
//        statDTO.setPercent1NewLeadsPay(calculatePercentageDifference(newLeadList.size(), newLeadListPrevMonth.size()));
//        statDTO.setPercent2InWorkLeadsPay(calculatePercentageDifference(inWorkLeadList.size(), inWorkLeadListPrevMonth.size()));
//
//        statDTO.setZpPayMap(toJson(getDailySumMap(localDate, zps, Zp::getCreated, Zp::getSum)));
//        statDTO.setSum1Day(zpMetrics.getDay1Sum().intValue());
//        statDTO.setSum1Week(zpMetrics.getWeek1Sum().intValue());
//        statDTO.setSum1Month(zpMetrics.getMonth1Sum().intValue());
//        statDTO.setSum1Year(zpMetrics.getYear1Sum().intValue());
//
//        statDTO.setSumOrders1Month(zpMetrics.getMonth1Count());
//        statDTO.setSumOrders2Month(zpMetrics.getMonth2Count());
//
//        statDTO.setPercent1Day(calculatePercentageDifference(zpMetrics.getDay1Sum(), zpMetrics.getDay2Sum()).intValue());
//        statDTO.setPercent1Week(calculatePercentageDifference(zpMetrics.getWeek1Sum(), zpMetrics.getWeek2Sum()).intValue());
//        statDTO.setPercent1Month(calculatePercentageDifference(zpMetrics.getMonth1Sum(), zpMetrics.getMonth2Sum()).intValue());
//        statDTO.setPercent1Year(calculatePercentageDifference(zpMetrics.getYear1Sum(), zpMetrics.getYear2Sum()).intValue());
//        statDTO.setPercent1MonthOrders(calculatePercentageDifference(zpMetrics.getMonth1Count(), zpMetrics.getMonth2Count()));
//        statDTO.setPercent2MonthOrders(calculatePercentageDifference(zpMetrics.getMonth2Count(), zpMetrics.getMonth3Count()));
//
//        return statDTO;
//    }
//
//    // ============================================ PERSONAL STAT END ===================================================
//
//
//    // =========================================== ВЗЯТИЕ СУММ ЗП И ЧЕКОВ ===============================================
//
//    private List<PaymentCheck> getPaymentChecks(LocalDate localDate, String role, Set<Manager> managerList) {
//        return checkRoleAndExecute(
//                role,
//                () -> paymentCheckService.findAllToDate(localDate),
//                owner -> paymentCheckService.findAllToDateByOwner(localDate, owner),
//                managerList
//        );
//    }
//
//    private List<Zp> getZarplataChecks(LocalDate localDate, String role, Set<Manager> managerList) {
//        return checkRoleAndExecute(
//                role,
//                () -> zpService.findAllToDate(localDate),
//                owner -> zpService.findAllToDateByOwner(localDate, owner),
//                managerList
//        );
//    }
//
//    private List<Long> getInWorkLeadList(String role, LocalDate localDate, Set<Manager> managerList) {
//        return checkRoleAndExecute(
//                role,
//                () -> leadService.getAllLeadsByDateAndStatus(localDate, STATUS_IN_WORK),
//                owner -> leadService.getAllLeadsByDateAndStatusToOwner(localDate, STATUS_IN_WORK, owner),
//                managerList
//        );
//    }
//
//    private List<Long> getNewLeadList(String role, LocalDate localDate, Set<Manager> managerList) {
//        return checkRoleAndExecute(
//                role,
//                () -> leadService.getAllLeadsByDate(localDate),
//                owner -> leadService.getAllLeadsByDateToOwner(localDate, owner),
//                managerList
//        );
//    }
//
//    private <T> List<T> checkRoleAndExecute(
//            String role,
//            Supplier<List<T>> adminFunction,
//            Function<Set<Manager>, List<T>> ownerFunction,
//            Set<Manager> managerList
//    ) {
//        if (ROLE_ADMIN.equals(role)) {
//            return adminFunction.get();
//        }
//        if (ROLE_OWNER.equals(role)) {
//            return ownerFunction.apply(managerList);
//        }
//        return adminFunction.get();
//    }
//
//    // ======================================= ВЗЯТИЕ СУММ ЗП И ЧЕКОВ - КОНЕЦ ==========================================
//
//
//    // ===================================== ПЕРЕВОД В МАПУ ЗП И ЧЕКОВ =================================================
//
//    private <T> Map<Integer, BigDecimal> getDailySumMap(
//            LocalDate targetDate,
//            List<T> items,
//            Function<T, LocalDate> dateExtractor,
//            Function<T, BigDecimal> sumExtractor
//    ) {
//        Map<Integer, BigDecimal> result = IntStream.rangeClosed(1, targetDate.lengthOfMonth())
//                .boxed()
//                .collect(Collectors.toMap(
//                        Function.identity(),
//                        day -> BigDecimal.ZERO,
//                        (a, b) -> a,
//                        LinkedHashMap::new
//                ));
//
//        if (items == null || items.isEmpty()) {
//            return result;
//        }
//
//        for (T item : items) {
//            LocalDate created = dateExtractor.apply(item);
//            if (created.getYear() == targetDate.getYear() && created.getMonth() == targetDate.getMonth()) {
//                int dayOfMonth = created.getDayOfMonth();
//                result.merge(dayOfMonth, sumExtractor.apply(item), BigDecimal::add);
//            }
//        }
//
//        return result;
//    }
//
//    private Map<Integer, BigDecimal> calculateDailyZpSumForMonth(LocalDate targetMonthDate, List<Zp> zps) {
//        return getDailySumMap(targetMonthDate, zps, Zp::getCreated, Zp::getSum);
//    }
//
//    private Map<Integer, BigDecimal> getDailySalarySumMap(LocalDate desiredDate, List<PaymentCheck> pcs) {
//        return getDailySumMap(desiredDate, pcs, PaymentCheck::getCreated, PaymentCheck::getSum);
//    }
//
//    // ===================================== ПЕРЕВОД В МАПУ ЗП И ЧЕКОВ - КОНЕЦ =========================================
//
//
//    private <T> Map<Integer, Map<Integer, BigDecimal>> getYearlyMonthlySumMap(
//            List<T> items,
//            Function<T, LocalDate> dateExtractor,
//            Function<T, BigDecimal> sumExtractor
//    ) {
//        Map<Integer, Map<Integer, BigDecimal>> result = new HashMap<>();
//
//        if (items == null || items.isEmpty()) {
//            return result;
//        }
//
//        for (T item : items) {
//            LocalDate createdDate = dateExtractor.apply(item);
//            int year = createdDate.getYear();
//            int month = createdDate.getMonthValue();
//
//            result.computeIfAbsent(year, y -> new HashMap<>())
//                    .merge(month, sumExtractor.apply(item), BigDecimal::add);
//        }
//
//        return result;
//    }
//
//    private Map<Integer, Map<Integer, BigDecimal>> getYearlyMonthlySalarySumMap(List<PaymentCheck> pcs) {
//        return getYearlyMonthlySumMap(pcs, PaymentCheck::getCreated, PaymentCheck::getSum);
//    }
//
//    private Map<Integer, Map<Integer, BigDecimal>> getYearlyMonthlyZpSumMap(List<Zp> zps) {
//        return getYearlyMonthlySumMap(zps, Zp::getCreated, Zp::getSum);
//    }
//
//    // ========================================== ПЕРЕВОД ГРАФИКА В JSON ===============================================
//
//    private String getJSON(Map<Integer, BigDecimal> data) {
//        return toJson(data);
//    }
//
//    private String getJSONMonth(Map<Integer, Map<Integer, BigDecimal>> data) {
//        return toJson(data);
//    }
//
//    private String toJson(Object source) {
//        try {
//            return objectMapper.writeValueAsString(source);
//        } catch (JsonProcessingException exception) {
//            throw new RuntimeException("Произошла ошибка при преобразовании данных в JSON", exception);
//        }
//    }
//
//    // ====================================== ПЕРЕВОД ГРАФИКА В JSON - КОНЕЦ ==========================================
//
//
//    // ================================ ОЦЕНКА РАЗНИЦЫ 2Х ЧИСЕЛ В ПРОЦЕНТАХ ============================================
//
//    private BigDecimal calculatePercentageDifference(BigDecimal sum1, BigDecimal sum2) {
//        if (isZero(sum1) || isZero(sum2)) {
//            return handleZeroValues(sum1, sum2);
//        }
//
//        BigDecimal difference = sum1.subtract(sum2);
//        BigDecimal baseValue = difference.compareTo(BigDecimal.ZERO) > 0 ? sum1 : sum2;
//
//        return difference.divide(baseValue, 2, RoundingMode.HALF_UP).multiply(ONE_HUNDRED);
//    }
//
//    private boolean isZero(BigDecimal value) {
//        return value == null || value.compareTo(BigDecimal.ZERO) == 0;
//    }
//
//    private BigDecimal handleZeroValues(BigDecimal sum1, BigDecimal sum2) {
//        if (isZero(sum1) && !isZero(sum2)) {
//            return ONE_HUNDRED.negate();
//        } else if (!isZero(sum1) && isZero(sum2)) {
//            return ONE_HUNDRED;
//        } else {
//            return BigDecimal.ZERO;
//        }
//    }
//
//    // =============================== ОЦЕНКА РАЗНИЦЫ 2Х ЧИСЕЛ В ПРОЦЕНТАХ - КОНЕЦ ====================================
//
//
//    // ===================================== РАЗНИЦА МЕЖДУ ДВУМЯ СУММАМИ ==============================================
//
//    private int calculatePercentageDifference(int sum1, int sum2) {
//        if (sum1 == 0 || sum2 == 0) {
//            return handleZeroSumCase(sum1, sum2);
//        }
//        return computePercentageDifference(sum1, sum2);
//    }
//
//    private int computePercentageDifference(int sum1, int sum2) {
//        int difference = sum1 - sum2;
//
//        if (difference == 0) {
//            return 0;
//        }
//
//        int denominator = (difference > 0) ? sum1 : sum2;
//        return difference * PERCENT_MULTIPLIER / Math.abs(denominator);
//    }
//
//    private int handleZeroSumCase(int sum1, int sum2) {
//        if (sum1 == 0 && sum2 == 0) {
//            return 0;
//        }
//        if (sum1 == 0) {
//            return sum2 > 0 ? -PERCENT_MULTIPLIER : 0;
//        }
//        return PERCENT_MULTIPLIER;
//    }
//
//    // ================================= РАЗНИЦА МЕЖДУ ДВУМЯ СУММАМИ - КОНЕЦ ==========================================
//
//
//    public UserStatDTO getWorkerReviews(User user, LocalDate localDate) {
//        List<Zp> zps = zpService.findAllToDateByUser(localDate, user.getId());
//        PeriodMetrics metrics = calculatePeriodMetrics(
//                zps,
//                Zp::getCreated,
//                Zp::getSum,
//                localDate
//        );
//
//        UserStatDTO userStatDTO = new UserStatDTO();
//
//        userStatDTO.setId(user.getId());
//        userStatDTO.setImageId(resolveImageId(user));
//        userStatDTO.setFio(user.getFio());
//        userStatDTO.setCoefficient(user.getCoefficient());
//
//        userStatDTO.setZpPayMap(getJSON(calculateDailyZpSumForMonth(localDate, zps)));
//        userStatDTO.setZpPayMapMonth(getJSONMonth(getYearlyMonthlyZpSumMap(zps)));
//
//        userStatDTO.setSum1Day(metrics.getDay1Sum().intValue());
//        userStatDTO.setSum1Week(metrics.getWeek1Sum().intValue());
//        userStatDTO.setSum1Month(metrics.getMonth1Sum().intValue());
//        userStatDTO.setSum1Year(metrics.getYear1Sum().intValue());
//
//        userStatDTO.setSumOrders1Month(metrics.getMonth1Count());
//        userStatDTO.setSumOrders2Month(metrics.getMonth2Count());
//
//        userStatDTO.setPercent1Day(calculatePercentageDifference(metrics.getDay1Sum(), metrics.getDay2Sum()).intValue());
//        userStatDTO.setPercent1Week(calculatePercentageDifference(metrics.getWeek1Sum(), metrics.getWeek2Sum()).intValue());
//        userStatDTO.setPercent1Month(calculatePercentageDifference(metrics.getMonth1Sum(), metrics.getMonth2Sum()).intValue());
//        userStatDTO.setPercent1Year(calculatePercentageDifference(metrics.getYear1Sum(), metrics.getYear2Sum()).intValue());
//        userStatDTO.setPercent1MonthOrders(calculatePercentageDifference(metrics.getMonth1Count(), metrics.getMonth2Count()));
//        userStatDTO.setPercent2MonthOrders(calculatePercentageDifference(metrics.getMonth2Count(), metrics.getMonth3Count()));
//
//        return userStatDTO;
//    }
//
//    // ====================================== РАЗНИЦА МЕЖДУ ДВУМЯ СУММАМИ - КОНЕЦ =====================================
//
//
//    // ========================================== PERSONAL LIST START =================================================
//
//    public List<ManagersListDTO> getManagers() {
//        return managerService.getAllManagers().stream()
//                .map(this::toManagersListDTO)
//                .collect(Collectors.toList());
//    }
//
//    public List<MarketologsListDTO> getMarketologs() {
//        return marketologService.getAllMarketologs().stream()
//                .map(this::toMarketologsListDTO)
//                .collect(Collectors.toList());
//    }
//
//    public List<WorkersListDTO> gerWorkers() {
//        return workerService.getAllWorkers().stream()
//                .map(this::toWorkersListDTO)
//                .collect(Collectors.toList());
//    }
//
//    public List<OperatorsListDTO> gerOperators() {
//        return operatorService.getAllOperators().stream()
//                .map(this::toOperatorsListDTO)
//                .collect(Collectors.toList());
//    }
//
//    public List<ManagersListDTO> getManagersToManager(Principal principal) {
//        return managerService.getAllManagers().stream()
//                .filter(p -> Objects.equals(p.getUser().getUsername(), principal.getName()))
//                .map(this::toManagersListDTO)
//                .collect(Collectors.toList());
//    }
//
//    public List<MarketologsListDTO> getMarketologsToManager(Manager manager) {
//        return marketologService.getAllMarketologs().stream()
//                .map(this::toMarketologsListDTO)
//                .collect(Collectors.toList());
//    }
//
//    public List<WorkersListDTO> gerWorkersToManager(Manager manager) {
//        return workerService.getAllWorkersToManager(manager).stream()
//                .map(this::toWorkersListDTO)
//                .collect(Collectors.toList());
//    }
//
//    public List<OperatorsListDTO> gerOperatorsToManager(Manager manager) {
//        return operatorService.getAllOperatorsToManager(manager).stream()
//                .map(this::toOperatorsListDTO)
//                .collect(Collectors.toList());
//    }
//
//    @Override
//    public List<Manager> findAllManagersWorkers(List<Manager> managerList) {
//        return managerService.findAllManagersWorkers(managerList);
//    }
//
//    public List<ManagersListDTO> getManagersToOwner(List<Manager> managers) {
//        return managers.stream().map(this::toManagersListDTO).toList();
//    }
//
//    public List<MarketologsListDTO> getMarketologsToOwner(List<Marketolog> allMarketologs) {
//        return allMarketologs.stream().map(this::toMarketologsListDTO).toList();
//    }
//
//    @Override
//    public List<OperatorsListDTO> gerOperatorsToOwner(List<Operator> allOperators) {
//        return allOperators.stream().map(this::toOperatorsListDTO).toList();
//    }
//
//    public List<WorkersListDTO> getWorkersToOwner(List<Worker> allWorkers) {
//        return allWorkers.stream().map(this::toWorkersListDTO).toList();
//    }
//
//    public List<OperatorsListDTO> gerOperatorsToOwner(Manager manager) {
//        return operatorService.getAllOperatorsToManager(manager).stream()
//                .map(this::toOperatorsListDTO)
//                .collect(Collectors.toList());
//    }
//
//    @Transactional
//    public List<ManagersListDTO> getManagersAndCount() {
//        return managerService.getAllManagers().stream()
//                .map(this::toManagersListDTOAndCount)
//                .collect(Collectors.toList());
//    }
//
//    public List<MarketologsListDTO> getMarketologsAndCount() {
//        return marketologService.getAllMarketologs().stream()
//                .map(this::toMarketologsListDTOAndCount)
//                .collect(Collectors.toList());
//    }
//
//    @Transactional
//    public List<WorkersListDTO> gerWorkersToAndCount() {
//        return workerService.getAllWorkers().stream()
//                .map(this::toWorkersListDTOAndCount)
//                .collect(Collectors.toList());
//    }
//
//    public List<OperatorsListDTO> gerOperatorsAndCount() {
//        return operatorService.getAllOperators().stream()
//                .map(this::toOperatorsListDTOAndCount)
//                .collect(Collectors.toList());
//    }
//
//    public Map<String, UserData> getPersonalsAndCountToMap() {
//        PersonalDataBundle bundle = loadPersonalDataBundle(LocalDate.now());
//        return buildUserDataMap(bundle);
//    }
//
//    public String displayResult(Map<String, UserData> result) {
//        StringBuilder resultBuilder = new StringBuilder();
//        List<Map.Entry<String, UserData>> sortedEntries = sortUserDataEntries(result);
//
//        long totalManagerRevenue = calculateTotalManagerRevenue(result);
//        long totalSpecificManagersRevenue = calculateTotalSpecificManagersRevenue(
//                result,
//                Set.of("Звуков Андрей", "Анжелика Б.")
//        );
//        long totalZp = extractTotalZp(result);
//        long totalNewCompanies = calculateTotalNewCompanies(result);
//
//        resultBuilder.append("Выручка за месяц всей компании: ")
//                .append(totalManagerRevenue)
//                .append(" руб. ( ")
//                .append(totalSpecificManagersRevenue)
//                .append(" руб. )\n")
//                .append("Общие затраты по ЗП: ")
//                .append(totalZp)
//                .append(" руб. \n")
//                .append("Новых компаний за месяц: ")
//                .append(totalNewCompanies)
//                .append("\n\n");
//
//        resultBuilder.append("Выручка менеджеров:\n");
//        sortedEntries.stream()
//                .filter(entry -> ROLE_MANAGER.equals(entry.getValue().getRole()))
//                .forEach(entry -> {
//                    UserData userData = entry.getValue();
//                    resultBuilder.append(entry.getKey())
//                            .append(": ")
//                            .append(userData.getTotalSum())
//                            .append(" руб. Новых: ")
//                            .append(userData.getNewCompanies())
//                            .append("\n");
//                });
//
//        resultBuilder.append("\nМенеджеры:\n\n");
//        sortedEntries.stream()
//                .filter(entry -> ROLE_MANAGER.equals(entry.getValue().getRole()))
//                .forEach(entry -> {
//                    UserData userData = entry.getValue();
//
//                    String orderStatus = "Лиды: " + userData.getLeadsNew()
//                            + " В проверку: " + userData.getOrderToCheck()
//                            + " На проверке: " + userData.getOrderInCheck()
//                            + " Опубликовано: " + userData.getOrderInPublished()
//                            + " Выставлен счет: " + userData.getOrderInWaitingPay1()
//                            + " Напоминание: " + userData.getOrderInWaitingPay2()
//                            + " Не оплачено: " + userData.getOrderNoPay();
//
//                    String orderStatsForWorkers = "Новых - " + userData.getNewOrders()
//                            + " Коррекция - " + userData.getCorrectOrders()
//                            + " Выгул - " + userData.getInVigul()
//                            + " Публикация - " + userData.getInPublish();
//
//                    resultBuilder.append(entry.getKey())
//                            .append(": ")
//                            .append(userData.getSalary())
//                            .append(" руб. \n")
//                            .append(orderStatus)
//                            .append("\n")
//                            .append(orderStatsForWorkers)
//                            .append("\n\n");
//                });
//
//        resultBuilder.append("\nЗП Работников:\n");
//        sortedEntries.stream()
//                .filter(entry -> ROLE_WORKER.equals(entry.getValue().getRole()))
//                .forEach(entry -> {
//                    UserData userData = entry.getValue();
//                    String orderStats = "Новые - " + userData.getNewOrders()
//                            + " В коррекции - " + userData.getCorrectOrders()
//                            + " В выгуле - " + userData.getInVigul()
//                            + " На публикации - " + userData.getInPublish();
//
//                    resultBuilder.append(entry.getKey())
//                            .append(": ")
//                            .append(userData.getSalary())
//                            .append(" руб.  \n")
//                            .append(orderStats)
//                            .append("\n\n");
//                });
//
//        return resultBuilder.toString();
//    }
//
//    public String displayResultToTelegramAdmin(Map<String, UserData> result) {
//        StringBuilder resultBuilder = new StringBuilder();
//        List<Map.Entry<String, UserData>> sortedEntries = sortUserDataEntries(result);
//
//        long totalManagerRevenue = calculateTotalManagerRevenue(result);
//        long totalSpecificManagersRevenue = calculateTotalSpecificManagersRevenue(result, Set.of("Анжелика Б."));
//        long totalZp = extractTotalZp(result);
//        long totalNewCompanies = calculateTotalNewCompanies(result);
//
//        resultBuilder.append("*📊 Отчёт за месяц*\n\n")
//                .append("*Общая выручка:* `").append(totalManagerRevenue).append(" руб.`\n")
//                .append("*Выручка (Иван):* `").append(escapeMarkdown(String.valueOf(totalSpecificManagersRevenue))).append(" руб.`\n")
//                .append("*Общие затраты на ЗП:* `").append(totalZp).append(" руб.`\n")
//                .append("*Новых компаний:* `").append(totalNewCompanies).append("`\n\n");
//
//        resultBuilder.append("*👤 Выручка менеджеров:*\n");
//        sortedEntries.stream()
//                .filter(entry -> ROLE_MANAGER.equals(entry.getValue().getRole()))
//                .forEach(entry -> {
//                    UserData user = entry.getValue();
//                    resultBuilder.append("• ")
//                            .append(escapeMarkdown(entry.getKey()))
//                            .append(": `")
//                            .append(user.getTotalSum())
//                            .append(" руб.`")
//                            .append(" — Новых: `")
//                            .append(user.getNewCompanies())
//                            .append("`\n");
//                });
//
//        resultBuilder.append("\n*💼 Менеджеры и статусы:*\n");
//        sortedEntries.stream()
//                .filter(entry -> ROLE_MANAGER.equals(entry.getValue().getRole()))
//                .forEach(entry -> {
//                    UserData user = entry.getValue();
//                    String fio = escapeMarkdown(entry.getKey());
//
//                    resultBuilder.append("*").append(fio).append("* — `").append(user.getSalary()).append(" руб.`\n");
//
//                    resultBuilder.append("`Лиды:` ").append(user.getLeadsNew()).append("  ")
//                            .append("`В проверку:` ").append(user.getOrderToCheck()).append("  ")
//                            .append("`На проверке:` ").append(user.getOrderInCheck()).append("  ")
//                            .append("`Опубликовано:` ").append(user.getOrderInPublished()).append("\n");
//
//                    resultBuilder.append("`Счёт:` ").append(user.getOrderInWaitingPay1()).append("  ")
//                            .append("`Напоминание:` ").append(user.getOrderInWaitingPay2()).append("  ")
//                            .append("`Не оплачено:` ").append(user.getOrderNoPay()).append("\n");
//
//                    resultBuilder.append("`Новых:` ").append(user.getNewOrders()).append("  ")
//                            .append("`Коррекция:` ").append(user.getCorrectOrders()).append("  ")
//                            .append("`Выгул:` ").append(user.getInVigul()).append("  ")
//                            .append("`Публикация:` ").append(user.getInPublish()).append("\n\n");
//                });
//
//        return resultBuilder.toString();
//    }
//
//    private String escapeMarkdown(String text) {
//        if (text == null) {
//            return "";
//        }
//        return text.replace("_", "\\_")
//                .replace("*", "\\*")
//                .replace("[", "\\[")
//                .replace("`", "\\`");
//    }
//
//    public List<UserData> getPersonalsAndCountToScore(LocalDate localDate) {
//        LocalDate firstDayOfMonth = localDate.withDayOfMonth(1);
//        LocalDate lastDayOfMonth = localDate.withDayOfMonth(localDate.lengthOfMonth());
//
//        Map<String, Quadruple<String, Long, Long, Long>> zps = zpService.getAllZpToMonth(firstDayOfMonth, lastDayOfMonth);
//        Map<String, Pair<Long, Long>> pcs = paymentCheckService.getAllPaymentToMonth(firstDayOfMonth, lastDayOfMonth);
//        Map<String, Long> newCompanies = companyService.getAllNewCompanies(firstDayOfMonth, lastDayOfMonth);
//        Map<String, Pair<Long, Long>> newOrders = orderService.getNewOrderAll(STATUS_NEW, STATUS_CORRECTION);
//        Map<String, Pair<Long, Long>> inPublishAndVigul = reviewService.getAllPublishAndVigul(firstDayOfMonth, localDate);
//        Map<String, Pair<Long, Long>> imagesIds = imageService.getAllImages();
//        Map<String, Pair<Long, Long>> leadsNewAndInWork = leadService.getAllLeadsToMonth(STATUS_IN_WORK, firstDayOfMonth, lastDayOfMonth);
//
//        long zpTotal = zps.values().stream()
//                .mapToLong(Quadruple::getSecond)
//                .sum();
//
//        List<UserData> result = new ArrayList<>();
//
//        for (Map.Entry<String, Quadruple<String, Long, Long, Long>> entry : zps.entrySet()) {
//            String fio = entry.getKey();
//            Quadruple<String, Long, Long, Long> pair = entry.getValue();
//
//            Long totalSum = getFirstOrDefault(pcs, fio, 0L);
//            Long newCompanyCount = newCompanies.getOrDefault(fio, 0L);
//            Long newOrderCount = getFirstOrDefault(newOrders, fio, 0L);
//            Long correctOrders = getSecondOrDefault(newOrders, fio, 0L);
//            Long inVigul = getFirstOrDefault(inPublishAndVigul, fio, 0L);
//            Long inPublishCount = getSecondOrDefault(inPublishAndVigul, fio, 0L);
//            Long imageId = getFirstOrDefault(imagesIds, fio, DEFAULT_IMAGE_ID);
//            Long userId = getSecondOrDefault(imagesIds, fio, 0L);
//
//            Long ordersCount = pair.getThird();
//            Long reviewsCount = pair.getFourth();
//            Long leadsNew = getFirstOrDefault(leadsNewAndInWork, fio, 0L);
//            Long leadsInWork = getSecondOrDefault(leadsNewAndInWork, fio, 0L);
//            Long percentInWork = safePercent(leadsInWork, leadsNew);
//
//            String role = pair.getFirst();
//
//            result.add(new UserData(
//                    fio,
//                    role,
//                    pair.getSecond(),
//                    totalSum,
//                    zpTotal,
//                    newCompanyCount,
//                    newOrderCount,
//                    correctOrders,
//                    inVigul,
//                    inPublishCount,
//                    imageId,
//                    userId,
//                    ordersCount,
//                    reviewsCount,
//                    leadsNew,
//                    leadsInWork,
//                    percentInWork,
//                    0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L
//            ));
//        }
//
//        return result;
//    }
//
//    @Transactional
//    public Map<String, UserData> getPersonalsAndCountToMapToOwner(Long userId) {
//        RegistrationUserDTO user = userService.findById(userId);
//        if (user == null) {
//            return Collections.emptyMap();
//        }
//
//        Map<String, UserData> fullMap = getPersonalsAndCountToMap();
//
//        Set<String> allowedFio = user.getManagers().stream()
//                .flatMap(manager -> {
//                    Stream<String> workersFio = manager.getUser().getWorkers().stream()
//                            .map(worker -> worker.getUser().getFio());
//                    return Stream.concat(Stream.of(manager.getUser().getFio()), workersFio);
//                })
//                .collect(Collectors.toSet());
//
//        return filterUserDataMap(fullMap, allowedFio);
//    }
//
//    @Transactional
//    public Map<String, UserData> getPersonalsAndCountToMapToManager(Long userId) {
//        RegistrationUserDTO user = userService.findById(userId);
//        if (user == null) {
//            return Collections.emptyMap();
//        }
//
//        Map<String, UserData> fullMap = getPersonalsAndCountToMap();
//
//        Set<String> allowedFio = Stream.concat(
//                Stream.of(user.getFio()),
//                user.getWorkers().stream().map(worker -> worker.getUser().getFio())
//        ).collect(Collectors.toSet());
//
//        return filterUserDataMap(fullMap, allowedFio);
//    }
//
//    public String displayResultToManager(Map<String, UserData> result) {
//        StringBuilder resultBuilder = new StringBuilder();
//        List<Map.Entry<String, UserData>> sortedEntries = sortUserDataEntries(result);
//
//        sortedEntries.stream()
//                .filter(entry -> ROLE_MANAGER.equals(entry.getValue().getRole()))
//                .forEach(entry -> {
//                    UserData userData = entry.getValue();
//
//                    String orderStatus = "Лиды: " + userData.getLeadsNew()
//                            + " В проверку: " + userData.getOrderToCheck()
//                            + " На проверке: " + userData.getOrderInCheck()
//                            + " Опубликовано: " + userData.getOrderInPublished()
//                            + " Выставлен счет: " + userData.getOrderInWaitingPay1()
//                            + " Напоминание: " + userData.getOrderInWaitingPay2()
//                            + " Не оплачено: " + userData.getOrderNoPay();
//
//                    String orderStatsForWorkers = "\nНовых - " + userData.getNewOrders()
//                            + " Коррекция - " + userData.getCorrectOrders()
//                            + " Выгул - " + userData.getInVigul()
//                            + " Публикация - " + userData.getInPublish();
//
//                    resultBuilder.append(entry.getKey())
//                            .append(": ")
//                            .append(userData.getSalary())
//                            .append(" руб. ")
//                            .append("\n\n")
//                            .append(orderStatus)
//                            .append("\n")
//                            .append(orderStatsForWorkers)
//                            .append("\n\n");
//                });
//
//        resultBuilder.append("\nСпециалисты:\n");
//        sortedEntries.stream()
//                .filter(entry -> ROLE_WORKER.equals(entry.getValue().getRole()))
//                .forEach(entry -> {
//                    UserData userData = entry.getValue();
//
//                    String orderStats = "Новые - " + userData.getNewOrders()
//                            + " В коррекции - " + userData.getCorrectOrders()
//                            + " В выгуле - " + userData.getInVigul()
//                            + " На публикации - " + userData.getInPublish();
//
//                    resultBuilder.append(entry.getKey())
//                            .append("\n")
//                            .append(orderStats)
//                            .append("\n\n");
//                });
//
//        return resultBuilder.toString();
//    }
//
//    @Transactional
//    public Map<String, UserData> getPersonalsAndCountToMapToWorker(Long userId) {
//        RegistrationUserDTO user = userService.findById(userId);
//        if (user == null) {
//            return Collections.emptyMap();
//        }
//
//        Map<String, UserData> fullMap = getPersonalsAndCountToMap();
//
//        Set<String> allowedFio = Stream.concat(
//                Stream.of(user.getFio()),
//                user.getWorkers().stream().map(worker -> worker.getUser().getFio())
//        ).collect(Collectors.toSet());
//
//        return filterUserDataMap(fullMap, allowedFio);
//    }
//
//    public String displayResultToWorker(Map<String, UserData> result) {
//        StringBuilder resultBuilder = new StringBuilder();
//        List<Map.Entry<String, UserData>> sortedEntries = sortUserDataEntries(result);
//
//        sortedEntries.stream()
//                .filter(entry -> ROLE_MANAGER.equals(entry.getValue().getRole()))
//                .forEach(entry -> {
//                    UserData userData = entry.getValue();
//
//                    String orderStatus = "Лиды: " + userData.getLeadsNew()
//                            + " В проверку: " + userData.getOrderToCheck()
//                            + " На проверке: " + userData.getOrderInCheck()
//                            + " Опубликовано: " + userData.getOrderInPublished()
//                            + " Выставлен счет: " + userData.getOrderInWaitingPay1()
//                            + " Напоминание: " + userData.getOrderInWaitingPay2()
//                            + " Не оплачено: " + userData.getOrderNoPay();
//
//                    String orderStatsForWorkers = "\nНовых - " + userData.getNewOrders()
//                            + " Коррекция - " + userData.getCorrectOrders()
//                            + " Выгул - " + userData.getInVigul()
//                            + " Публикация - " + userData.getInPublish();
//
//                    resultBuilder.append(entry.getKey())
//                            .append(": ")
//                            .append(userData.getSalary())
//                            .append(" руб. ")
//                            .append("\n\n")
//                            .append(orderStatus)
//                            .append("\n")
//                            .append(orderStatsForWorkers)
//                            .append("\n\n");
//                });
//
//        sortedEntries.stream()
//                .filter(entry -> ROLE_WORKER.equals(entry.getValue().getRole()))
//                .forEach(entry -> {
//                    UserData userData = entry.getValue();
//
//                    String orderStats = "Новые - " + userData.getNewOrders()
//                            + " В коррекции - " + userData.getCorrectOrders()
//                            + " В выгуле - " + userData.getInVigul()
//                            + " На публикации - " + userData.getInPublish();
//
//                    resultBuilder.append(entry.getKey())
//                            .append(": ")
//                            .append(userData.getSalary())
//                            .append(" руб.  ")
//                            .append("\n\n")
//                            .append("Статусы заказов:")
//                            .append("\n")
//                            .append(orderStats)
//                            .append("\n\n");
//                });
//
//        return resultBuilder.toString();
//    }
//
//    // Списки менеджеров со статистикой и счетчиками для Владельцев.
//    public List<ManagersListDTO> getManagersAndCountToOwner(List<Manager> managers) {
//        return managers.stream().map(this::toManagersListDTOAndCount).toList();
//    }
//
//    public List<MarketologsListDTO> getMarketologsAndCountToOwner(List<Marketolog> allMarketologs) {
//        return allMarketologs.stream().map(this::toMarketologsListDTOAndCount).toList();
//    }
//
//    public List<WorkersListDTO> getWorkersToAndCountToOwner(List<Worker> allWorkers) {
//        return allWorkers.stream().map(this::toWorkersListDTOAndCount).toList();
//    }
//
//    public List<OperatorsListDTO> getOperatorsAndCountToOwner(List<Operator> allOperators) {
//        return allOperators.stream().map(this::toOperatorsListDTOAndCount).toList();
//    }
//
//    private ManagersListDTO toManagersListDTO(Manager manager) {
//        User user = manager.getUser();
//        return ManagersListDTO.builder()
//                .id(manager.getId())
//                .userId(user.getId())
//                .fio(user.getFio())
//                .login(user.getUsername())
//                .imageId(resolveImageId(user))
//                .build();
//    }
//
//    private MarketologsListDTO toMarketologsListDTO(Marketolog marketolog) {
//        User user = marketolog.getUser();
//        return MarketologsListDTO.builder()
//                .id(marketolog.getId())
//                .userId(user.getId())
//                .fio(user.getFio())
//                .login(user.getUsername())
//                .imageId(resolveImageId(user))
//                .build();
//    }
//
//    private WorkersListDTO toWorkersListDTO(Worker worker) {
//        User user = worker.getUser();
//        return WorkersListDTO.builder()
//                .id(worker.getId())
//                .userId(user.getId())
//                .fio(user.getFio())
//                .login(user.getUsername())
//                .imageId(resolveImageId(user))
//                .build();
//    }
//
//    private OperatorsListDTO toOperatorsListDTO(Operator operator) {
//        User user = operator.getUser();
//        return OperatorsListDTO.builder()
//                .id(operator.getId())
//                .userId(user.getId())
//                .fio(user.getFio())
//                .login(user.getUsername())
//                .imageId(resolveImageId(user))
//                .build();
//    }
//
//    private ManagersListDTO toManagersListDTOAndCount(Manager manager) {
//        LocalDate localDate = LocalDate.now();
//        LocalDate firstDayOfMonth = localDate.withDayOfMonth(1);
//        LocalDate lastDayOfMonth = localDate.withDayOfMonth(localDate.lengthOfMonth());
//
//        List<Zp> zps = zpService.getAllWorkerZp(manager.getUser().getUsername());
//        BigDecimal sum30 = sumZp(zps);
//
//        List<PaymentCheck> pcs = paymentCheckService.getAllWorkerPaymentToDate(
//                manager.getUser().getId(),
//                firstDayOfMonth,
//                lastDayOfMonth
//        );
//        BigDecimal sum30Payments = sumPaymentChecks(pcs);
//
//        Set<Manager> managerList = new HashSet<>();
//        managerList.add(manager);
//
//        List<Long> inWorkLeadList = leadService.getAllLeadsByDateAndStatusToOwnerForTelegram(
//                localDate,
//                STATUS_IN_WORK,
//                managerList
//        );
//
//        return ManagersListDTO.builder()
//                .id(manager.getId())
//                .userId(manager.getUser().getId())
//                .fio(manager.getUser().getFio())
//                .login(manager.getUser().getUsername())
//                .imageId(resolveImageId(manager.getUser()))
//                .sum1Month(sum30.intValue())
//                .order1Month(zps.size())
//                .review1Month(sumZpAmount(zps))
//                .payment1Month(sum30Payments.intValue())
//                .leadsInWorkInMonth(inWorkLeadList.size())
//                .build();
//    }
//
//    private MarketologsListDTO toMarketologsListDTOAndCount(Marketolog marketolog) {
//        LocalDate localDate = LocalDate.now();
//
//        List<Zp> zps = zpService.getAllWorkerZp(marketolog.getUser().getUsername());
//        BigDecimal sum30 = sumZp(zps);
//
//        Long newListLeads = leadService.findAllByLidListNew(marketolog);
//        Long inWorkListLeads = leadService.findAllByLidListStatusInWork(marketolog);
//        Long percentInWork = safePercent(inWorkListLeads, newListLeads);
//
//        return MarketologsListDTO.builder()
//                .id(marketolog.getId())
//                .userId(marketolog.getUser().getId())
//                .fio(marketolog.getUser().getFio())
//                .login(marketolog.getUser().getUsername())
//                .imageId(resolveImageId(marketolog.getUser()))
//                .sum1Month(sum30.intValue())
//                .order1Month(zps.size())
//                .review1Month(sumZpAmount(zps))
//                .leadsNew(newListLeads)
//                .leadsInWork(inWorkListLeads)
//                .percentInWork(percentInWork)
//                .build();
//    }
//
//    private WorkersListDTO toWorkersListDTOAndCount(Worker worker) {
//        LocalDate localDate = LocalDate.now();
//
//        List<Zp> zps = zpService.getAllWorkerZp(worker.getUser().getUsername());
//        BigDecimal sum30 = sumZp(zps);
//
//        int newOrderInt = orderService.countOrdersByWorkerAndStatus(worker, STATUS_NEW);
//        int inCorrectInt = orderService.countOrdersByWorkerAndStatus(worker, STATUS_CORRECTION);
//        int inVigulInt = reviewService.countOrdersByWorkerAndStatusVigul(worker, localDate);
//        int inPublishInt = reviewService.countOrdersByWorkerAndStatusPublish(worker, localDate);
//
//        return WorkersListDTO.builder()
//                .id(worker.getId())
//                .userId(worker.getUser().getId())
//                .fio(worker.getUser().getFio())
//                .login(worker.getUser().getUsername())
//                .imageId(resolveImageId(worker.getUser()))
//                .sum1Month(sum30.intValue())
//                .order1Month(zps.size())
//                .review1Month(sumZpAmount(zps))
//                .newOrder(newOrderInt)
//                .inCorrect(inCorrectInt)
//                .intVigul(inVigulInt)
//                .publish(inPublishInt)
//                .build();
//    }
//
//    private OperatorsListDTO toOperatorsListDTOAndCount(Operator operator) {
//        List<Zp> zps = zpService.getAllWorkerZp(operator.getUser().getUsername());
//        BigDecimal sum30 = sumZp(zps);
//
//        Long newListLeads = leadService.findAllByLidListNew(operator);
//        Long inWorkListLeads = leadService.findAllByLidListStatusInWork(operator);
//        Long percentInWork = safePercent(inWorkListLeads, newListLeads);
//
//        return OperatorsListDTO.builder()
//                .id(operator.getId())
//                .userId(operator.getUser().getId())
//                .fio(operator.getUser().getFio())
//                .login(operator.getUser().getUsername())
//                .imageId(resolveImageId(operator.getUser()))
//                .sum1Month(sum30.intValue())
//                .order1Month(zps.size())
//                .review1Month(sumZpAmount(zps))
//                .leadsNew(newListLeads)
//                .leadsInWork(inWorkListLeads)
//                .percentInWork(percentInWork)
//                .build();
//    }
//
//    public List<ManagersListDTO> getManagersAndCountToDate(LocalDate localdate) {
//        return managerService.getAllManagers().stream()
//                .map(manager -> toManagersListDTOAndCountToDate(manager, localdate))
//                .collect(Collectors.toList());
//    }
//
//    public List<MarketologsListDTO> getMarketologsAndCountToDate(LocalDate localdate) {
//        return marketologService.getAllMarketologs().stream()
//                .map(marketolog -> toMarketologsListDTOAndCountToDate(marketolog, localdate))
//                .collect(Collectors.toList());
//    }
//
//    public List<WorkersListDTO> gerWorkersToAndCountToDate(LocalDate localdate) {
//        return workerService.getAllWorkers().stream()
//                .map(worker -> toWorkersListDTOAndCountToDate(worker, localdate))
//                .collect(Collectors.toList());
//    }
//
//    public List<OperatorsListDTO> gerOperatorsAndCountToDate(LocalDate localdate) {
//        return operatorService.getAllOperators().stream()
//                .map(operator -> toOperatorsListDTOAndCountToDate(operator, localdate))
//                .collect(Collectors.toList());
//    }
//
//    public List<ManagersListDTO> getManagersAndCountToDateToOwner(List<Manager> managerList, LocalDate localdate) {
//        return managerList.stream()
//                .map(manager -> toManagersListDTOAndCountToDate(manager, localdate))
//                .collect(Collectors.toList());
//    }
//
//    public List<MarketologsListDTO> getMarketologsAndCountToDateToOwner(List<Marketolog> marketologList, LocalDate localdate) {
//        return marketologList.stream()
//                .map(marketolog -> toMarketologsListDTOAndCountToDate(marketolog, localdate))
//                .collect(Collectors.toList());
//    }
//
//    public List<WorkersListDTO> gerWorkersToAndCountToDateToOwner(List<Worker> workerList, LocalDate localdate) {
//        return workerList.stream()
//                .map(worker -> toWorkersListDTOAndCountToDate(worker, localdate))
//                .collect(Collectors.toList());
//    }
//
//    public List<OperatorsListDTO> gerOperatorsAndCountToDateToOwner(List<Operator> operatorList, LocalDate localdate) {
//        return operatorList.stream()
//                .map(operator -> toOperatorsListDTOAndCountToDate(operator, localdate))
//                .collect(Collectors.toList());
//    }
//
//    private ManagersListDTO toManagersListDTOAndCountToDate(Manager manager, LocalDate localDate) {
//        LocalDate firstDayOfMonth = localDate.withDayOfMonth(1);
//
//        List<Zp> zps = zpService.getAllWorkerZpToDate(manager.getUser().getUsername(), firstDayOfMonth, localDate.withDayOfMonth(localDate.lengthOfMonth()));
//        BigDecimal sum30 = sumZp(zps);
//
//        List<PaymentCheck> pcs = paymentCheckService.getAllWorkerPaymentToDate(
//                manager.getUser().getId(),
//                firstDayOfMonth,
//                localDate
//        );
//        BigDecimal sum30Payments = sumPaymentChecks(pcs);
//
//        return ManagersListDTO.builder()
//                .id(manager.getId())
//                .userId(manager.getUser().getId())
//                .fio(manager.getUser().getFio())
//                .login(manager.getUser().getUsername())
//                .imageId(resolveImageId(manager.getUser()))
//                .sum1Month(sum30.intValue())
//                .order1Month(zps.size())
//                .review1Month(sumZpAmount(zps))
//                .payment1Month(sum30Payments.intValue())
//                .build();
//    }
//
//    private MarketologsListDTO toMarketologsListDTOAndCountToDate(Marketolog marketolog, LocalDate localDate) {
//        LocalDate firstDayOfMonth = localDate.withDayOfMonth(1);
//        LocalDate lastDayOfMonth = localDate.withDayOfMonth(localDate.lengthOfMonth());
//
//        List<Zp> zps = zpService.getAllWorkerZpToDate(marketolog.getUser().getUsername(), firstDayOfMonth, lastDayOfMonth);
//        BigDecimal sum30 = sumZp(zps);
//
//        Long newListLeads = leadService.findAllByLidListNewToDate(marketolog, localDate);
//        Long inWorkListLeads = leadService.findAllByLidListStatusInWorkToDate(marketolog, localDate);
//        Long percentInWork = safePercent(inWorkListLeads, newListLeads);
//
//        return MarketologsListDTO.builder()
//                .id(marketolog.getId())
//                .userId(marketolog.getUser().getId())
//                .fio(marketolog.getUser().getFio())
//                .login(marketolog.getUser().getUsername())
//                .imageId(resolveImageId(marketolog.getUser()))
//                .sum1Month(sum30.intValue())
//                .order1Month(zps.size())
//                .review1Month(sumZpAmount(zps))
//                .leadsNew(newListLeads)
//                .leadsInWork(inWorkListLeads)
//                .percentInWork(percentInWork)
//                .build();
//    }
//
//    private WorkersListDTO toWorkersListDTOAndCountToDate(Worker worker, LocalDate localDate) {
//        LocalDate firstDayOfMonth = localDate.withDayOfMonth(1);
//        LocalDate lastDayOfMonth = localDate.withDayOfMonth(localDate.lengthOfMonth());
//
//        List<Zp> zps = zpService.getAllWorkerZpToDate(worker.getUser().getUsername(), firstDayOfMonth, lastDayOfMonth);
//        BigDecimal sum30 = sumZp(zps);
//
//        return WorkersListDTO.builder()
//                .id(worker.getId())
//                .userId(worker.getUser().getId())
//                .fio(worker.getUser().getFio())
//                .login(worker.getUser().getUsername())
//                .imageId(resolveImageId(worker.getUser()))
//                .sum1Month(sum30.intValue())
//                .order1Month(zps.size())
//                .review1Month(sumZpAmount(zps))
//                .build();
//    }
//
//    private OperatorsListDTO toOperatorsListDTOAndCountToDate(Operator operator, LocalDate localDate) {
//        LocalDate firstDayOfMonth = localDate.withDayOfMonth(1);
//        LocalDate lastDayOfMonth = localDate.withDayOfMonth(localDate.lengthOfMonth());
//
//        List<Zp> zps = zpService.getAllWorkerZpToDate(operator.getUser().getUsername(), firstDayOfMonth, lastDayOfMonth);
//        BigDecimal sum30 = sumZp(zps);
//
//        Long newListLeads = leadService.findAllByLidListNewToDate(operator, localDate);
//        Long inWorkListLeads = leadService.findAllByLidListStatusInWorkToDate(operator, localDate);
//        Long percentInWork = safePercent(inWorkListLeads, newListLeads);
//
//        return OperatorsListDTO.builder()
//                .id(operator.getId())
//                .userId(operator.getUser().getId())
//                .fio(operator.getUser().getFio())
//                .login(operator.getUser().getUsername())
//                .imageId(resolveImageId(operator.getUser()))
//                .sum1Month(sum30.intValue())
//                .order1Month(zps.size())
//                .review1Month(sumZpAmount(zps))
//                .leadsNew(newListLeads)
//                .leadsInWork(inWorkListLeads)
//                .percentInWork(percentInWork)
//                .build();
//    }
//
//    // ========================================== PERSONAL LIST FINISH ================================================
//
//    private String getRole(Principal principal) {
//        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
//        Object authPrincipal = authentication.getPrincipal();
//
//        if (authPrincipal instanceof UserDetails userDetails) {
//            return userDetails.getAuthorities().stream()
//                    .map(GrantedAuthority::getAuthority)
//                    .findFirst()
//                    .orElse("");
//        }
//
//        return authentication.getAuthorities().stream()
//                .map(GrantedAuthority::getAuthority)
//                .findFirst()
//                .orElse("");
//    }
//
//    // ========================================== PRIVATE HELPERS =====================================================
//
//    private String extractShortRole(User user) {
//        String fullRole = user.getRoles().iterator().next().getAuthority();
//        if (fullRole.startsWith("ROLE_")) {
//            return fullRole.substring("ROLE_".length());
//        }
//        return fullRole;
//    }
//
//    private long resolveImageId(User user) {
//        return user.getImage() != null ? user.getImage().getId() : DEFAULT_IMAGE_ID;
//    }
//
//    private BigDecimal sumZp(List<Zp> zps) {
//        return zps.stream()
//                .map(Zp::getSum)
//                .reduce(BigDecimal.ZERO, BigDecimal::add);
//    }
//
//    private int sumZpAmount(List<Zp> zps) {
//        return zps.stream()
//                .mapToInt(Zp::getAmount)
//                .sum();
//    }
//
//    private BigDecimal sumPaymentChecks(List<PaymentCheck> checks) {
//        return checks.stream()
//                .map(PaymentCheck::getSum)
//                .reduce(BigDecimal.ZERO, BigDecimal::add);
//    }
//
//    private Long safePercent(Long numerator, Long denominator) {
//        if (denominator == null || denominator == 0L) {
//            return 0L;
//        }
//        long safeNumerator = numerator == null ? 0L : numerator;
//        return (safeNumerator * 100) / denominator;
//    }
//
//    private <K> Long getFirstOrDefault(Map<K, Pair<Long, Long>> map, K key, Long defaultValue) {
//        if (map == null) {
//            return defaultValue;
//        }
//        Pair<Long, Long> pair = map.get(key);
//        return pair != null && pair.getFirst() != null ? pair.getFirst() : defaultValue;
//    }
//
//    private <K> Long getSecondOrDefault(Map<K, Pair<Long, Long>> map, K key, Long defaultValue) {
//        if (map == null) {
//            return defaultValue;
//        }
//        Pair<Long, Long> pair = map.get(key);
//        return pair != null && pair.getSecond() != null ? pair.getSecond() : defaultValue;
//    }
//
//    private List<Map.Entry<String, UserData>> sortUserDataEntries(Map<String, UserData> result) {
//        return result.entrySet().stream()
//                .sorted((entry1, entry2) -> {
//                    UserData user1 = entry1.getValue();
//                    UserData user2 = entry2.getValue();
//
//                    boolean isManager1 = ROLE_MANAGER.equals(user1.getRole());
//                    boolean isManager2 = ROLE_MANAGER.equals(user2.getRole());
//
//                    if (isManager1 && isManager2) {
//                        return Long.compare(user2.getTotalSum(), user1.getTotalSum());
//                    }
//
//                    if (!isManager1 && !isManager2) {
//                        return Long.compare(user2.getSalary(), user1.getSalary());
//                    }
//
//                    return Boolean.compare(isManager2, isManager1);
//                })
//                .collect(Collectors.toList());
//    }
//
//    private long calculateTotalManagerRevenue(Map<String, UserData> result) {
//        return result.values().stream()
//                .filter(user -> ROLE_MANAGER.equals(user.getRole()))
//                .mapToLong(UserData::getTotalSum)
//                .sum();
//    }
//
//    private long calculateTotalSpecificManagersRevenue(Map<String, UserData> result, Set<String> managerNames) {
//        return result.values().stream()
//                .filter(user -> ROLE_MANAGER.equals(user.getRole()) && managerNames.contains(user.getFio()))
//                .mapToLong(UserData::getTotalSum)
//                .sum();
//    }
//
//    private long calculateTotalNewCompanies(Map<String, UserData> result) {
//        return result.values().stream()
//                .filter(user -> ROLE_MANAGER.equals(user.getRole()))
//                .mapToLong(UserData::getNewCompanies)
//                .sum();
//    }
//
//    private long extractTotalZp(Map<String, UserData> result) {
//        return result.values().stream()
//                .findFirst()
//                .map(UserData::getZpTotal)
//                .orElse(0L);
//    }
//
//    private Map<String, UserData> filterUserDataMap(Map<String, UserData> source, Set<String> allowedFio) {
//        return source.entrySet().stream()
//                .filter(entry -> allowedFio.contains(entry.getKey()))
//                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
//    }
//
//    private PersonalDataBundle loadPersonalDataBundle(LocalDate localDate) {
//        LocalDate firstDayOfMonth = localDate.withDayOfMonth(1);
//        LocalDate lastDayOfMonth = localDate.withDayOfMonth(localDate.lengthOfMonth());
//
//        Map<String, Pair<String, Long>> zps = zpService.getAllZpToMonthToTelegram(firstDayOfMonth, lastDayOfMonth);
//        Map<String, Pair<Long, Long>> pcs = paymentCheckService.getAllPaymentToMonth(firstDayOfMonth, lastDayOfMonth);
//        Map<String, Long> newCompanies = companyService.getAllNewCompanies(firstDayOfMonth, lastDayOfMonth);
//        Map<String, Pair<Long, Long>> newOrders = orderService.getNewOrderAll(STATUS_NEW, STATUS_CORRECTION);
//        Map<String, Pair<Long, Long>> inPublishAndVigul = reviewService.getAllPublishAndVigul(firstDayOfMonth, localDate);
//        Map<String, Map<String, Long>> allOrders = orderService.getAllOrdersToMonthByStatus(
//                firstDayOfMonth,
//                lastDayOfMonth,
//                STATUS_NEW,
//                STATUS_TO_CHECK,
//                STATUS_IN_CHECK,
//                STATUS_CORRECTION,
//                STATUS_PUBLISHED,
//                STATUS_BILL_SENT,
//                STATUS_REMINDER,
//                STATUS_NOT_PAID
//        );
//        Map<String, Long> leadsNewAndInWork = leadService.getAllLeadsToMonthToManager(STATUS_NEW, firstDayOfMonth, lastDayOfMonth);
//
//        long zpTotal = zps.values().stream()
//                .mapToLong(Pair::getSecond)
//                .sum();
//
//        return new PersonalDataBundle(
//                zps,
//                pcs,
//                newCompanies,
//                newOrders,
//                inPublishAndVigul,
//                allOrders,
//                leadsNewAndInWork,
//                zpTotal
//        );
//    }
//
//    private Map<String, UserData> buildUserDataMap(PersonalDataBundle bundle) {
//        Map<String, UserData> result = new HashMap<>();
//
//        for (Map.Entry<String, Pair<String, Long>> entry : bundle.zps.entrySet()) {
//            String fio = entry.getKey();
//            Pair<String, Long> pair = entry.getValue();
//
//            Long totalSum = getFirstOrDefault(bundle.pcs, fio, 0L);
//            Long newCompanyCount = bundle.newCompanies.getOrDefault(fio, 0L);
//            Long inVigul = getFirstOrDefault(bundle.inPublishAndVigul, fio, 0L);
//            Long inPublishCount = getSecondOrDefault(bundle.inPublishAndVigul, fio, 0L);
//            Long leadsNew = bundle.leadsNewAndInWork.getOrDefault(fio, 0L);
//
//            String role = pair.getFirst();
//
//            Map<String, Long> orders = bundle.allOrders.getOrDefault(fio, Collections.emptyMap());
//            Long orderInNew = orders.getOrDefault(STATUS_NEW, 0L);
//            Long orderToCheck = orders.getOrDefault(STATUS_TO_CHECK, 0L);
//            Long orderInCheck = orders.getOrDefault(STATUS_IN_CHECK, 0L);
//            Long orderInCorrect = orders.getOrDefault(STATUS_CORRECTION, 0L);
//            Long orderInPublished = orders.getOrDefault(STATUS_PUBLISHED, 0L);
//            Long orderInWaitingPay1 = orders.getOrDefault(STATUS_BILL_SENT, 0L);
//            Long orderInWaitingPay2 = orders.getOrDefault(STATUS_REMINDER, 0L);
//            Long orderNoPay = orders.getOrDefault(STATUS_NOT_PAID, 0L);
//
//            result.put(fio, new UserData(
//                    fio,
//                    role,
//                    pair.getSecond(),
//                    totalSum,
//                    bundle.zpTotal,
//                    newCompanyCount,
//                    orderInNew,
//                    orderInCorrect,
//                    inVigul,
//                    inPublishCount,
//                    DEFAULT_IMAGE_ID,
//                    0L,
//                    0L,
//                    0L,
//                    leadsNew,
//                    null,
//                    null,
//                    orderInNew,
//                    orderToCheck,
//                    orderInCheck,
//                    orderInCorrect,
//                    orderInPublished,
//                    orderInWaitingPay1,
//                    orderInWaitingPay2,
//                    orderNoPay
//            ));
//        }
//
//        return result;
//    }
//
//    private <T> PeriodMetrics calculatePeriodMetrics(
//            List<T> items,
//            Function<T, LocalDate> dateExtractor,
//            Function<T, BigDecimal> sumExtractor,
//            LocalDate now
//    ) {
//        PeriodBoundaries boundaries = PeriodBoundaries.of(now);
//        PeriodMetrics metrics = new PeriodMetrics();
//
//        if (items == null || items.isEmpty()) {
//            return metrics;
//        }
//
//        for (T item : items) {
//            LocalDate created = dateExtractor.apply(item);
//            BigDecimal sum = sumExtractor.apply(item);
//
//            if (created.isEqual(boundaries.previousDay)) {
//                metrics.day1Sum = metrics.day1Sum.add(sum);
//            }
//
//            if (created.isEqual(boundaries.twoDaysAgo)) {
//                metrics.day2Sum = metrics.day2Sum.add(sum);
//            }
//
//            if (!created.isBefore(boundaries.last7DaysStart) && !created.isAfter(boundaries.today)) {
//                metrics.week1Sum = metrics.week1Sum.add(sum);
//            }
//
//            if (!created.isBefore(boundaries.previous7DaysStart) && created.isBefore(boundaries.last7DaysStart)) {
//                metrics.week2Sum = metrics.week2Sum.add(sum);
//            }
//
//            if (!created.isBefore(boundaries.currentMonthStart) && !created.isAfter(boundaries.currentMonthEnd)) {
//                metrics.month1Sum = metrics.month1Sum.add(sum);
//                metrics.month1Count++;
//            }
//
//            if (!created.isBefore(boundaries.previousMonthStart) && !created.isAfter(boundaries.previousMonthEnd)) {
//                metrics.month2Sum = metrics.month2Sum.add(sum);
//                metrics.month2Count++;
//            }
//
//            if (!created.isBefore(boundaries.threeMonthsAgoStart) && !created.isAfter(boundaries.threeMonthsAgoEnd)) {
//                metrics.month3Sum = metrics.month3Sum.add(sum);
//                metrics.month3Count++;
//            }
//
//            if (!created.isBefore(boundaries.currentYearStart) && !created.isAfter(boundaries.today)) {
//                metrics.year1Sum = metrics.year1Sum.add(sum);
//            }
//
//            if (!created.isBefore(boundaries.previousYearStart) && !created.isAfter(boundaries.previousYearSameDate)) {
//                metrics.year2Sum = metrics.year2Sum.add(sum);
//            }
//        }
//
//        return metrics;
//    }
//
//    private static final class PeriodBoundaries {
//        private final LocalDate today;
//        private final LocalDate previousDay;
//        private final LocalDate twoDaysAgo;
//        private final LocalDate last7DaysStart;
//        private final LocalDate previous7DaysStart;
//        private final LocalDate currentMonthStart;
//        private final LocalDate currentMonthEnd;
//        private final LocalDate previousMonthStart;
//        private final LocalDate previousMonthEnd;
//        private final LocalDate threeMonthsAgoStart;
//        private final LocalDate threeMonthsAgoEnd;
//        private final LocalDate currentYearStart;
//        private final LocalDate previousYearStart;
//        private final LocalDate previousYearSameDate;
//
//        private PeriodBoundaries(
//                LocalDate today,
//                LocalDate previousDay,
//                LocalDate twoDaysAgo,
//                LocalDate last7DaysStart,
//                LocalDate previous7DaysStart,
//                LocalDate currentMonthStart,
//                LocalDate currentMonthEnd,
//                LocalDate previousMonthStart,
//                LocalDate previousMonthEnd,
//                LocalDate threeMonthsAgoStart,
//                LocalDate threeMonthsAgoEnd,
//                LocalDate currentYearStart,
//                LocalDate previousYearStart,
//                LocalDate previousYearSameDate
//        ) {
//            this.today = today;
//            this.previousDay = previousDay;
//            this.twoDaysAgo = twoDaysAgo;
//            this.last7DaysStart = last7DaysStart;
//            this.previous7DaysStart = previous7DaysStart;
//            this.currentMonthStart = currentMonthStart;
//            this.currentMonthEnd = currentMonthEnd;
//            this.previousMonthStart = previousMonthStart;
//            this.previousMonthEnd = previousMonthEnd;
//            this.threeMonthsAgoStart = threeMonthsAgoStart;
//            this.threeMonthsAgoEnd = threeMonthsAgoEnd;
//            this.currentYearStart = currentYearStart;
//            this.previousYearStart = previousYearStart;
//            this.previousYearSameDate = previousYearSameDate;
//        }
//
//        private static PeriodBoundaries of(LocalDate now) {
//            LocalDate currentMonthStart = now.withDayOfMonth(1);
//            LocalDate previousMonthStart = currentMonthStart.minusMonths(1);
//            LocalDate threeMonthsAgoStart = currentMonthStart.minusMonths(3);
//
//            return new PeriodBoundaries(
//                    now,
//                    now.minusDays(1),
//                    now.minusDays(2),
//                    now.minusDays(7),
//                    now.minusDays(14),
//                    currentMonthStart,
//                    currentMonthStart.withDayOfMonth(currentMonthStart.lengthOfMonth()),
//                    previousMonthStart,
//                    previousMonthStart.withDayOfMonth(previousMonthStart.lengthOfMonth()),
//                    threeMonthsAgoStart,
//                    threeMonthsAgoStart.withDayOfMonth(threeMonthsAgoStart.lengthOfMonth()),
//                    now.withDayOfYear(1),
//                    now.minusYears(1).withDayOfYear(1),
//                    now.minusYears(1)
//            );
//        }
//    }
//
//    private static final class PeriodMetrics {
//        private BigDecimal day1Sum = BigDecimal.ZERO;
//        private BigDecimal day2Sum = BigDecimal.ZERO;
//        private BigDecimal week1Sum = BigDecimal.ZERO;
//        private BigDecimal week2Sum = BigDecimal.ZERO;
//        private BigDecimal month1Sum = BigDecimal.ZERO;
//        private BigDecimal month2Sum = BigDecimal.ZERO;
//        private BigDecimal month3Sum = BigDecimal.ZERO;
//        private BigDecimal year1Sum = BigDecimal.ZERO;
//        private BigDecimal year2Sum = BigDecimal.ZERO;
//
//        private int month1Count = 0;
//        private int month2Count = 0;
//        private int month3Count = 0;
//
//        public BigDecimal getDay1Sum() {
//            return day1Sum;
//        }
//
//        public BigDecimal getDay2Sum() {
//            return day2Sum;
//        }
//
//        public BigDecimal getWeek1Sum() {
//            return week1Sum;
//        }
//
//        public BigDecimal getWeek2Sum() {
//            return week2Sum;
//        }
//
//        public BigDecimal getMonth1Sum() {
//            return month1Sum;
//        }
//
//        public BigDecimal getMonth2Sum() {
//            return month2Sum;
//        }
//
//        public BigDecimal getMonth3Sum() {
//            return month3Sum;
//        }
//
//        public BigDecimal getYear1Sum() {
//            return year1Sum;
//        }
//
//        public BigDecimal getYear2Sum() {
//            return year2Sum;
//        }
//
//        public int getMonth1Count() {
//            return month1Count;
//        }
//
//        public int getMonth2Count() {
//            return month2Count;
//        }
//
//        public int getMonth3Count() {
//            return month3Count;
//        }
//    }
//
//    private static final class PersonalDataBundle {
//        private final Map<String, Pair<String, Long>> zps;
//        private final Map<String, Pair<Long, Long>> pcs;
//        private final Map<String, Long> newCompanies;
//        private final Map<String, Pair<Long, Long>> newOrders;
//        private final Map<String, Pair<Long, Long>> inPublishAndVigul;
//        private final Map<String, Map<String, Long>> allOrders;
//        private final Map<String, Long> leadsNewAndInWork;
//        private final long zpTotal;
//
//        private PersonalDataBundle(
//                Map<String, Pair<String, Long>> zps,
//                Map<String, Pair<Long, Long>> pcs,
//                Map<String, Long> newCompanies,
//                Map<String, Pair<Long, Long>> newOrders,
//                Map<String, Pair<Long, Long>> inPublishAndVigul,
//                Map<String, Map<String, Long>> allOrders,
//                Map<String, Long> leadsNewAndInWork,
//                long zpTotal
//        ) {
//            this.zps = zps;
//            this.pcs = pcs;
//            this.newCompanies = newCompanies;
//            this.newOrders = newOrders;
//            this.inPublishAndVigul = inPublishAndVigul;
//            this.allOrders = allOrders;
//            this.leadsNewAndInWork = leadsNewAndInWork;
//            this.zpTotal = zpTotal;
//        }
//    }
//}
//
//
