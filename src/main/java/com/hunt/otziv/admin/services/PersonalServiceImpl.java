package com.hunt.otziv.admin.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.admin.dto.personal_stat.StatDTO;
import com.hunt.otziv.admin.dto.personal_stat.UserLKDTO;
import com.hunt.otziv.admin.dto.personal_stat.UserStatDTO;
import com.hunt.otziv.admin.dto.presonal.ManagersListDTO;
import com.hunt.otziv.admin.dto.presonal.MarketologsListDTO;
import com.hunt.otziv.admin.dto.presonal.OperatorsListDTO;
import com.hunt.otziv.admin.dto.presonal.WorkersListDTO;
import com.hunt.otziv.l_lead.model.Lead;
import com.hunt.otziv.l_lead.services.LeadService;
import com.hunt.otziv.p_products.model.Product;
import com.hunt.otziv.r_review.services.ReviewService;
import com.hunt.otziv.u_users.model.*;
import com.hunt.otziv.u_users.services.service.*;
import com.hunt.otziv.z_zp.model.PaymentCheck;
import com.hunt.otziv.z_zp.model.Zp;
import com.hunt.otziv.z_zp.services.PaymentCheckService;
import com.hunt.otziv.z_zp.services.ZpService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.Principal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

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

    public StatDTO getStats() {
        LocalDate localDate = LocalDate.now();

        //        ОПЛАТЫ Разбивка на списки 1-2-7-14-30-60-90-360-730 дней от текущей даты
        List<PaymentCheck> pcs = paymentCheckService.findAll();
        List<PaymentCheck> Pay1Day = pcs.stream().filter(p -> p.getCreated().isEqual(localDate.minusDays(1))).toList();
        List<PaymentCheck> Pay7Day = pcs.stream().filter(p -> p.getCreated().isAfter(localDate.minusDays(7))).toList();
        List<PaymentCheck> Pay30Day = pcs.stream().filter(p -> p.getCreated().isAfter(localDate.minusDays(30))).toList();
        List<PaymentCheck> Pay365Day = pcs.stream().filter(p -> p.getCreated().isAfter(localDate.minusDays(365))).toList();
        List<PaymentCheck> Pay2Day = pcs.stream().filter(p -> p.getCreated().isEqual(localDate.minusDays(2))).toList();
        List<PaymentCheck> Pay14Day = pcs.stream().filter(p -> p.getCreated().isAfter(localDate.minusDays(14)) && p.getCreated().isBefore(localDate.minusDays(7))).toList();
        List<PaymentCheck> Pay60Day = pcs.stream().filter(p -> p.getCreated().isAfter(localDate.minusDays(60)) && p.getCreated().isBefore(localDate.minusDays(30))).toList();
        List<PaymentCheck> Pay90Day = pcs.stream().filter(p -> p.getCreated().isAfter(localDate.minusDays(90)) && p.getCreated().isBefore(localDate.minusDays(60))).toList();
        List<PaymentCheck> Pay730Day = pcs.stream().filter(p -> p.getCreated().isAfter(localDate.minusDays(730)) && p.getCreated().isBefore(localDate.minusDays(365))).toList();

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



        //      СТАТИСТИКА новых лидов и тех, что поступили в работу
        List<Long> newleadList = leadService.getAllLeadsByDate(localDate); // берем всех лидов за текущий месяц
        List<Long> inWorkleadList = leadService.getAllLeadsByDateAndStatus(localDate, "В работе"); // берем всех лидов за текущий месяц + статус
        List<Long> newleadList2Month = leadService.getAllLeadsByDate2Month(localDate); // берем всех лидов за текущий месяц
        List<Long> inWorkleadList2Month = leadService.getAllLeadsByDateAndStatus2Month(localDate, "В работе"); // берем всех лидов за текущий месяц + статус



        //        ЗП Разбивка на списки 1-2-7-14-30-60-90-360-730 дней от текущей даты
        List<Zp> zps = zpService.findAll();
        List<Zp> zpPay1Day = zps.stream().filter(z -> z.getCreated().isEqual(localDate.minusDays(1))).toList();
        List<Zp> zpPay7Day = zps.stream().filter(z -> z.getCreated().isAfter(localDate.minusDays(7))).toList();
        List<Zp> zpPay30Day = zps.stream().filter(z -> z.getCreated().isAfter(localDate.minusDays(30))).toList();
        List<Zp> zpPay365Day = zps.stream().filter(z -> z.getCreated().isAfter(localDate.minusDays(365))).toList();
        List<Zp> zpPay2Day = zps.stream().filter(z -> z.getCreated().isEqual(localDate.minusDays(2))).toList();
        List<Zp> zpPay14Day = zps.stream().filter(z -> z.getCreated().isAfter(localDate.minusDays(14)) && z.getCreated().isBefore(localDate.minusDays(7))).toList();
        List<Zp> zpPay60Day = zps.stream().filter(z -> z.getCreated().isAfter(localDate.minusDays(60)) && z.getCreated().isBefore(localDate.minusDays(30))).toList();
        List<Zp> zpPay90Day = zps.stream().filter(z -> z.getCreated().isAfter(localDate.minusDays(90)) && z.getCreated().isBefore(localDate.minusDays(60))).toList();
        List<Zp> zpPay730Day = zps.stream().filter(z -> z.getCreated().isAfter(localDate.minusDays(730)) && z.getCreated().isBefore(localDate.minusDays(365))).toList();

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
        statDTO.setOrderPayMap(getJSON(getMapPay1Month(localDate, pcs)));
        statDTO.setSum1DayPay(sum1Pay.intValue());
        statDTO.setSum1WeekPay(sum7Pay.intValue());
        statDTO.setSum1MonthPay(sum30Pay.intValue());
        statDTO.setSum1YearPay(sum365Pay.intValue());
        statDTO.setSumOrders1MonthPay(Pay30Day.size());
        statDTO.setSumOrders2MonthPay(Pay60Day.size());
        statDTO.setPercent1DayPay(percentageComparison(sum1Pay, sum2Pay).intValue());
        statDTO.setPercent1WeekPay(percentageComparison(sum7Pay, sum14Pay).intValue());
        statDTO.setPercent1MonthPay(percentageComparison(sum30Pay, sum60Pay).intValue());
        statDTO.setPercent1YearPay(percentageComparison(sum365Pay, sum730Pay).intValue());
        statDTO.setPercent1MonthOrdersPay(percentageComparison(sumCount1MonthPay, sumCount2MonthPay).intValue());
        statDTO.setPercent2MonthOrdersPay(percentageComparison(sumCount2MonthPay, sumCount3MonthPay).intValue());
        statDTO.setNewLeads(newleadList.size());
        statDTO.setLeadsInWork(inWorkleadList.size());
        statDTO.setPercent1NewLeadsPay(percentageComparisonInt(newleadList.size(), newleadList2Month.size()));
        statDTO.setPercent2InWorkLeadsPay(percentageComparisonInt(inWorkleadList.size(), inWorkleadList2Month.size()));

        statDTO.setZpPayMap(getJSON(getMapZp1Month(localDate, zps)));
        statDTO.setSum1Day(sum1.intValue());
        statDTO.setSum1Week(sum7.intValue());
        statDTO.setSum1Month(sum30.intValue());
        statDTO.setSum1Year(sum365.intValue());
        statDTO.setSumOrders1Month(zpPay30Day.size());
        statDTO.setSumOrders2Month(zpPay60Day.size());
        statDTO.setPercent1Day(percentageComparison(sum1, sum2).intValue());
        statDTO.setPercent1Week(percentageComparison(sum7, sum14).intValue());
        statDTO.setPercent1Month(percentageComparison(sum30, sum60).intValue());
        statDTO.setPercent1Year(percentageComparison(sum365, sum730).intValue());
        statDTO.setPercent1MonthOrders(percentageComparison(sumCount1Month, sumCount2Month).intValue());
        statDTO.setPercent2MonthOrders(percentageComparison(sumCount2Month, sumCount3Month).intValue());
        return statDTO;
    }


    public UserStatDTO getWorkerReviews(String login) {
        LocalDate localDate = LocalDate.now();
        //        разбивка на списки 1-2-7-14-30-60-90-360-730 дней от текущей даты
        List<Zp> zps = zpService.getAllWorkerZp(login);
        List<Zp> zpPay1Day = zps.stream().filter(z -> z.getCreated().isEqual(localDate.minusDays(1))).toList();
        List<Zp> zpPay7Day = zps.stream().filter(z -> z.getCreated().isAfter(localDate.minusDays(7))).toList();
        List<Zp> zpPay30Day = zps.stream().filter(z -> z.getCreated().isAfter(localDate.minusDays(30))).toList();
        List<Zp> zpPay365Day = zps.stream().filter(z -> z.getCreated().isAfter(localDate.minusDays(365))).toList();
        List<Zp> zpPay2Day = zps.stream().filter(z -> z.getCreated().isEqual(localDate.minusDays(2))).toList();
        List<Zp> zpPay14Day = zps.stream().filter(z -> z.getCreated().isAfter(localDate.minusDays(14)) && z.getCreated().isBefore(localDate.minusDays(7))).toList();
        List<Zp> zpPay60Day = zps.stream().filter(z -> z.getCreated().isAfter(localDate.minusDays(60)) && z.getCreated().isBefore(localDate.minusDays(30))).toList();
        List<Zp> zpPay90Day = zps.stream().filter(z -> z.getCreated().isAfter(localDate.minusDays(90)) && z.getCreated().isBefore(localDate.minusDays(60))).toList();
        List<Zp> zpPay730Day = zps.stream().filter(z -> z.getCreated().isAfter(localDate.minusDays(730)) && z.getCreated().isBefore(localDate.minusDays(365))).toList();

        //        Сумма всех выплат за 1-2-7-14-30-60-90-360-730 дней
        BigDecimal sum1 = zpPay1Day.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add); // первая сумма
        BigDecimal sum7 = zpPay7Day.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add); // первая сумма
        BigDecimal sum30 = zpPay30Day.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add); // первая сумма
        BigDecimal sum365 = zpPay365Day.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add); // первая сумма
        BigDecimal sum2 = zpPay2Day.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sum14 = zpPay14Day.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sum60 = zpPay60Day.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sum730 = zpPay730Day.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add);

        //        Сумма всех заказов за 30-60-90 дней
        BigDecimal sumCount1Month = BigDecimal.valueOf(zpPay30Day.size()); // 1 сумма
        BigDecimal sumCount2Month = BigDecimal.valueOf(zpPay60Day.size()); // 2 сумма
        BigDecimal sumCount3Month = BigDecimal.valueOf(zpPay90Day.size()); // 3 сумма


        User user = userService.findByUserName(login).orElseThrow();
        Long imageId = user.getImage() != null ? user.getImage().getId() : 1L;
        UserStatDTO userStatDTO = new UserStatDTO();
        userStatDTO.setId(user.getId());
        userStatDTO.setImageId(imageId);
        userStatDTO.setFio(user.getFio());
        userStatDTO.setCoefficient(user.getCoefficient());
        userStatDTO.setZpPayMap(getJSON(getMapZp1Month(localDate, zps)));
        userStatDTO.setSum1Day(sum1.intValue());
        userStatDTO.setSum1Week(sum7.intValue());
        userStatDTO.setSum1Month(sum30.intValue());
        userStatDTO.setSum1Year(sum365.intValue());
        userStatDTO.setSumOrders1Month(zpPay30Day.size());
        userStatDTO.setSumOrders2Month(zpPay60Day.size());
        userStatDTO.setPercent1Day(percentageComparison(sum1, sum2).intValue());
        userStatDTO.setPercent1Week(percentageComparison(sum7, sum14).intValue());
        userStatDTO.setPercent1Month(percentageComparison(sum30, sum60).intValue());
        userStatDTO.setPercent1Year(percentageComparison(sum365, sum730).intValue());
        userStatDTO.setPercent1MonthOrders(percentageComparison(sumCount1Month, sumCount2Month).intValue());
        userStatDTO.setPercent2MonthOrders(percentageComparison(sumCount2Month, sumCount3Month).intValue());

        return userStatDTO;
    }


    private Map<Integer, BigDecimal> getMapZp1Month(LocalDate desiredDate, List<Zp> zps){ //Создание мапы день-сумма зп
        Map<Integer, BigDecimal> zpPayMapOnMonth = new HashMap<>();
        for (int i = 1; i <= desiredDate.lengthOfMonth(); i++) {
            zpPayMapOnMonth.put(i, BigDecimal.ZERO);
        }

        for (Zp zp : zps) {
            if (zp.getCreated().getMonth() == desiredDate.getMonth()) {
                int dayOfMonth = zp.getCreated().getDayOfMonth();
                BigDecimal currentSum = zpPayMapOnMonth.get(dayOfMonth);
                if (currentSum != null) {
                    zpPayMapOnMonth.put(dayOfMonth, currentSum.add(zp.getSum()));
                }
            }
        }
        return zpPayMapOnMonth;
    } //Создание мапы день-сумма зп

    private Map<Integer, BigDecimal> getMapPay1Month(LocalDate desiredDate, List<PaymentCheck> pcs){ //Создание мапы день-сумма зп
        Map<Integer, BigDecimal> zpPayMapOnMonth = new HashMap<>();
        for (int i = 1; i <= desiredDate.lengthOfMonth(); i++) {
            zpPayMapOnMonth.put(i, BigDecimal.ZERO);
        }

        for (PaymentCheck paymentCheck : pcs) {
            if (paymentCheck.getCreated().getMonth() == desiredDate.getMonth()) {
                int dayOfMonth = paymentCheck.getCreated().getDayOfMonth();
                BigDecimal currentSum = zpPayMapOnMonth.get(dayOfMonth);
                if (currentSum != null) {
                    zpPayMapOnMonth.put(dayOfMonth, currentSum.add(paymentCheck.getSum()));
                }
            }
        }
        return zpPayMapOnMonth;
    } //Создание мапы день-сумма зп

    private String getJSON(Map<Integer, BigDecimal> zpPayMapOnMonth) { // Перевод мапы в JSON
        // Инициализируем ObjectMapper
        ObjectMapper objectMapper = new ObjectMapper();
        try {
            // Преобразовываем Map в JSON-строку
            return objectMapper.writeValueAsString(zpPayMapOnMonth);
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
        return null;
    } // Перевод мапы в JSON

    private BigDecimal percentageComparison(BigDecimal sum1, BigDecimal sum2) { // Оценка разницы 2х чисел в процентах

        if (sum1.compareTo(BigDecimal.ZERO) <= 0 && sum2.compareTo(BigDecimal.ZERO) > 0) {
            // Обработка случая, когда sum1 равно нулю
            return sum2.divide(sum2, 2, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)).negate();
        }
        if (sum1.compareTo(BigDecimal.ZERO) > 0 && sum2.compareTo(BigDecimal.ZERO) <= 0) {
            // Обработка случая, когда sum2 равно нулю
            return sum1.divide(sum1, 2, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
        }
        if (sum1.compareTo(BigDecimal.ZERO) == 0 && sum2.compareTo(BigDecimal.ZERO) == 0) {
            // Обработка случая, когда sum2 равно нулю
            return BigDecimal.ZERO;
        }
        else {
            BigDecimal difference = sum1.subtract(sum2); // разница между суммами
            if (difference.compareTo(BigDecimal.ZERO) > 0){
                return difference.divide(sum1, 2, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
            }
            if (difference.compareTo(BigDecimal.ZERO) < 0){
                return difference.divide(sum2, 2, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
            }
            else return BigDecimal.ZERO;
        }
    } // Оценка разницы 2х чисел в процентах

    private int percentageComparisonInt(int sum1, int sum2) {
        if (sum1 <= 0 && sum2 > 0) {
            // Обработка случая, когда sum1 равно нулю
            return (int) (((double) sum2 / sum2) * 100) * -1;
        }
        if (sum1 > 0 && sum2 <= 0) {
            // Обработка случая, когда sum2 равно нулю
            return (int) (((double) sum1 / sum1) * 100);
        }
        if (sum1 == 0 && sum2 == 0) {
            // Обработка случая, когда оба значения равны нулю
            return 0;
        } else {
            int difference = sum1 - sum2; // разница между суммами
            if (difference > 0) {
                return (int) (((double) difference / sum1) * 100);
            }
            if (difference < 0) {
                return (int) (((double) difference / sum2) * 100);
            } else {
                return 0;
            }
        }
    }

    private BigDecimal getPrice(Product product){
        return product.getPrice();
    }




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



    public List<ManagersListDTO> getManagersAndCount(){
        return managerService.getAllManagers().stream().map(this::toManagersListDTOAndCount).collect(Collectors.toList());
    }
    public List<MarketologsListDTO> getMarketologsAndCount(){
        return marketologService.getAllMarketologs().stream().map(this::toMarketologsListDTOAndCount).collect(Collectors.toList());
    }
    public List<WorkersListDTO> gerWorkersToAndCount(){
        return workerService.getAllWorkers().stream().map(this::toWorkersListDTOAndCount).collect(Collectors.toList());
    }
    public List<OperatorsListDTO> gerOperatorsAndCount(){
        return operatorService.getAllOperators().stream().map(this::toOperatorsListDTOAndCount).collect(Collectors.toList());
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
        List<Zp> zps = zpService.getAllWorkerZp(manager.getUser().getUsername());
        BigDecimal sum30 = zps.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add); // первая сумма
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
                .build();
    }

    private MarketologsListDTO toMarketologsListDTOAndCount(Marketolog marketolog){
        LocalDate localDate = LocalDate.now();
        List<Zp> zps = zpService.getAllWorkerZp(marketolog.getUser().getUsername());
        BigDecimal sum30 = zps.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add); // первая сумма
        Long imageId = marketolog.getUser().getImage() != null ? marketolog.getUser().getImage().getId() : 1L;
        Long newListLeadsToMarketolog = leadService.findAllByLidListStatusNew(marketolog);
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

    private OperatorsListDTO toOperatorsListDTOAndCount(Operator operator){
        LocalDate localDate = LocalDate.now();
        List<Zp> zps = zpService.getAllWorkerZp(operator.getUser().getUsername());
        BigDecimal sum30 = zps.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add); // первая сумма
        Long imageId = operator.getUser().getImage() != null ? operator.getUser().getImage().getId() : 1L;
        Long newListLeadsToOperators = leadService.findAllByLidListStatusNew(operator);
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

    public StatDTO getStats2(LocalDate localDate) {
        System.out.println(localDate);

        //        ОПЛАТЫ Разбивка на списки 1-2-7-14-30-60-90-360-730 дней от текущей даты
        List<PaymentCheck> pcs = paymentCheckService.findAllToDate(localDate);
        List<PaymentCheck> Pay1Day = pcs.stream().filter(p -> p.getCreated().isEqual(localDate.minusDays(1))).toList();
        List<PaymentCheck> Pay7Day = pcs.stream().filter(p -> p.getCreated().isAfter(localDate.minusDays(7))).toList();
        List<PaymentCheck> Pay30Day = pcs.stream().filter(p -> p.getCreated().isAfter(localDate.minusDays(30))).toList();
        List<PaymentCheck> Pay365Day = pcs.stream().filter(p -> p.getCreated().isAfter(localDate.minusDays(365))).toList();
        List<PaymentCheck> Pay2Day = pcs.stream().filter(p -> p.getCreated().isEqual(localDate.minusDays(2))).toList();
        List<PaymentCheck> Pay14Day = pcs.stream().filter(p -> p.getCreated().isAfter(localDate.minusDays(14)) && p.getCreated().isBefore(localDate.minusDays(7))).toList();
        List<PaymentCheck> Pay60Day = pcs.stream().filter(p -> p.getCreated().isAfter(localDate.minusDays(60)) && p.getCreated().isBefore(localDate.minusDays(30))).toList();
        List<PaymentCheck> Pay90Day = pcs.stream().filter(p -> p.getCreated().isAfter(localDate.minusDays(90)) && p.getCreated().isBefore(localDate.minusDays(60))).toList();
        List<PaymentCheck> Pay730Day = pcs.stream().filter(p -> p.getCreated().isAfter(localDate.minusDays(730)) && p.getCreated().isBefore(localDate.minusDays(365))).toList();

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



        //      СТАТИСТИКА новых лидов и тех, что поступили в работу
        List<Long> newleadList = leadService.getAllLeadsByDate(localDate); // берем всех лидов за текущий месяц
        List<Long> inWorkleadList = leadService.getAllLeadsByDateAndStatus(localDate, "В работе"); // берем всех лидов за текущий месяц + статус
        List<Long> newleadList2Month = leadService.getAllLeadsByDate2Month(localDate); // берем всех лидов за текущий месяц
        List<Long> inWorkleadList2Month = leadService.getAllLeadsByDateAndStatus2Month(localDate, "В работе"); // берем всех лидов за текущий месяц + статус



        //        ЗП Разбивка на списки 1-2-7-14-30-60-90-360-730 дней от текущей даты
        List<Zp> zps = zpService.findAllToDate(localDate);
        List<Zp> zpPay1Day = zps.stream().filter(z -> z.getCreated().isEqual(localDate.minusDays(1))).toList();
        List<Zp> zpPay7Day = zps.stream().filter(z -> z.getCreated().isAfter(localDate.minusDays(7))).toList();
        List<Zp> zpPay30Day = zps.stream().filter(z -> z.getCreated().isAfter(localDate.minusDays(30))).toList();
        List<Zp> zpPay365Day = zps.stream().filter(z -> z.getCreated().isAfter(localDate.minusDays(365))).toList();
        List<Zp> zpPay2Day = zps.stream().filter(z -> z.getCreated().isEqual(localDate.minusDays(2))).toList();
        List<Zp> zpPay14Day = zps.stream().filter(z -> z.getCreated().isAfter(localDate.minusDays(14)) && z.getCreated().isBefore(localDate.minusDays(7))).toList();
        List<Zp> zpPay60Day = zps.stream().filter(z -> z.getCreated().isAfter(localDate.minusDays(60)) && z.getCreated().isBefore(localDate.minusDays(30))).toList();
        List<Zp> zpPay90Day = zps.stream().filter(z -> z.getCreated().isAfter(localDate.minusDays(90)) && z.getCreated().isBefore(localDate.minusDays(60))).toList();
        List<Zp> zpPay730Day = zps.stream().filter(z -> z.getCreated().isAfter(localDate.minusDays(730)) && z.getCreated().isBefore(localDate.minusDays(365))).toList();

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
        statDTO.setOrderPayMap(getJSON(getMapPay1Month(localDate, pcs)));
        statDTO.setSum1DayPay(sum1Pay.intValue());
        statDTO.setSum1WeekPay(sum7Pay.intValue());
        statDTO.setSum1MonthPay(sum30Pay.intValue());
        statDTO.setSum1YearPay(sum365Pay.intValue());
        statDTO.setSumOrders1MonthPay(Pay30Day.size());
        statDTO.setSumOrders2MonthPay(Pay60Day.size());
        statDTO.setPercent1DayPay(percentageComparison(sum1Pay, sum2Pay).intValue());
        statDTO.setPercent1WeekPay(percentageComparison(sum7Pay, sum14Pay).intValue());
        statDTO.setPercent1MonthPay(percentageComparison(sum30Pay, sum60Pay).intValue());
        statDTO.setPercent1YearPay(percentageComparison(sum365Pay, sum730Pay).intValue());
        statDTO.setPercent1MonthOrdersPay(percentageComparison(sumCount1MonthPay, sumCount2MonthPay).intValue());
        statDTO.setPercent2MonthOrdersPay(percentageComparison(sumCount2MonthPay, sumCount3MonthPay).intValue());
        statDTO.setNewLeads(newleadList.size());
        statDTO.setLeadsInWork(inWorkleadList.size());
        statDTO.setPercent1NewLeadsPay(percentageComparisonInt(newleadList.size(), newleadList2Month.size()));
        statDTO.setPercent2InWorkLeadsPay(percentageComparisonInt(inWorkleadList.size(), inWorkleadList2Month.size()));

        statDTO.setZpPayMap(getJSON(getMapZp1Month(localDate, zps)));
        statDTO.setSum1Day(sum1.intValue());
        statDTO.setSum1Week(sum7.intValue());
        statDTO.setSum1Month(sum30.intValue());
        statDTO.setSum1Year(sum365.intValue());
        statDTO.setSumOrders1Month(zpPay30Day.size());
        statDTO.setSumOrders2Month(zpPay60Day.size());
        statDTO.setPercent1Day(percentageComparison(sum1, sum2).intValue());
        statDTO.setPercent1Week(percentageComparison(sum7, sum14).intValue());
        statDTO.setPercent1Month(percentageComparison(sum30, sum60).intValue());
        statDTO.setPercent1Year(percentageComparison(sum365, sum730).intValue());
        statDTO.setPercent1MonthOrders(percentageComparison(sumCount1Month, sumCount2Month).intValue());
        statDTO.setPercent2MonthOrders(percentageComparison(sumCount2Month, sumCount3Month).intValue());
        System.out.println(statDTO);
        return statDTO;
    }

    public UserStatDTO getWorkerReviews2(String login, LocalDate localDate) {
        //        разбивка на списки 1-2-7-14-30-60-90-360-730 дней от текущей даты
        List<Zp> zps = zpService.getAllWorkerZpToDate(login, localDate);
        List<Zp> zpPay1Day = zps.stream().filter(z -> z.getCreated().isEqual(localDate.minusDays(1))).toList();
        List<Zp> zpPay7Day = zps.stream().filter(z -> z.getCreated().isAfter(localDate.minusDays(7))).toList();
        List<Zp> zpPay30Day = zps.stream().filter(z -> z.getCreated().isAfter(localDate.minusDays(30))).toList();
        List<Zp> zpPay365Day = zps.stream().filter(z -> z.getCreated().isAfter(localDate.minusDays(365))).toList();
        List<Zp> zpPay2Day = zps.stream().filter(z -> z.getCreated().isEqual(localDate.minusDays(2))).toList();
        List<Zp> zpPay14Day = zps.stream().filter(z -> z.getCreated().isAfter(localDate.minusDays(14)) && z.getCreated().isBefore(localDate.minusDays(7))).toList();
        List<Zp> zpPay60Day = zps.stream().filter(z -> z.getCreated().isAfter(localDate.minusDays(60)) && z.getCreated().isBefore(localDate.minusDays(30))).toList();
        List<Zp> zpPay90Day = zps.stream().filter(z -> z.getCreated().isAfter(localDate.minusDays(90)) && z.getCreated().isBefore(localDate.minusDays(60))).toList();
        List<Zp> zpPay730Day = zps.stream().filter(z -> z.getCreated().isAfter(localDate.minusDays(730)) && z.getCreated().isBefore(localDate.minusDays(365))).toList();

        //        Сумма всех выплат за 1-2-7-14-30-60-90-360-730 дней
        BigDecimal sum1 = zpPay1Day.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add); // первая сумма
        BigDecimal sum7 = zpPay7Day.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add); // первая сумма
        BigDecimal sum30 = zpPay30Day.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add); // первая сумма
        BigDecimal sum365 = zpPay365Day.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add); // первая сумма
        BigDecimal sum2 = zpPay2Day.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sum14 = zpPay14Day.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sum60 = zpPay60Day.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sum730 = zpPay730Day.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add);

        //        Сумма всех заказов за 30-60-90 дней
        BigDecimal sumCount1Month = BigDecimal.valueOf(zpPay30Day.size()); // 1 сумма
        BigDecimal sumCount2Month = BigDecimal.valueOf(zpPay60Day.size()); // 2 сумма
        BigDecimal sumCount3Month = BigDecimal.valueOf(zpPay90Day.size()); // 3 сумма


        User user = userService.findByUserName(login).orElseThrow();
        Long imageId = user.getImage() != null ? user.getImage().getId() : 1L;
        UserStatDTO userStatDTO = new UserStatDTO();
        userStatDTO.setId(user.getId());
        userStatDTO.setImageId(imageId);
        userStatDTO.setFio(user.getFio());
        userStatDTO.setCoefficient(user.getCoefficient());
        userStatDTO.setZpPayMap(getJSON(getMapZp1Month(localDate, zps)));
        userStatDTO.setSum1Day(sum1.intValue());
        userStatDTO.setSum1Week(sum7.intValue());
        userStatDTO.setSum1Month(sum30.intValue());
        userStatDTO.setSum1Year(sum365.intValue());
        userStatDTO.setSumOrders1Month(zpPay30Day.size());
        userStatDTO.setSumOrders2Month(zpPay60Day.size());
        userStatDTO.setPercent1Day(percentageComparison(sum1, sum2).intValue());
        userStatDTO.setPercent1Week(percentageComparison(sum7, sum14).intValue());
        userStatDTO.setPercent1Month(percentageComparison(sum30, sum60).intValue());
        userStatDTO.setPercent1Year(percentageComparison(sum365, sum730).intValue());
        userStatDTO.setPercent1MonthOrders(percentageComparison(sumCount1Month, sumCount2Month).intValue());
        userStatDTO.setPercent2MonthOrders(percentageComparison(sumCount2Month, sumCount3Month).intValue());

        return userStatDTO;
    }


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

    private ManagersListDTO toManagersListDTOAndCountToDate(Manager manager, LocalDate localDate){
        List<Zp> zps = zpService.getAllWorkerZpToDate(manager.getUser().getUsername(), localDate);
        BigDecimal sum30 = zps.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add); // первая сумма
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
                .build();
    }

    private MarketologsListDTO toMarketologsListDTOAndCountToDate(Marketolog marketolog, LocalDate localDate){
        List<Zp> zps = zpService.getAllWorkerZpToDate(marketolog.getUser().getUsername(), localDate);
        BigDecimal sum30 = zps.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add); // первая сумма
        Long imageId = marketolog.getUser().getImage() != null ? marketolog.getUser().getImage().getId() : 1L;
        Long newListLeadsToMarketolog = leadService.findAllByLidListStatusNewToDate(marketolog, localDate);
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
        List<Zp> zps = zpService.getAllWorkerZpToDate(worker.getUser().getUsername(), localDate);
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
        List<Zp> zps = zpService.getAllWorkerZpToDate(operator.getUser().getUsername(), localDate);
        BigDecimal sum30 = zps.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add); // первая сумма
        Long imageId = operator.getUser().getImage() != null ? operator.getUser().getImage().getId() : 1L;
        Long newListLeadsToOperators = leadService.findAllByLidListStatusNewToDate(operator, localDate);
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
}
