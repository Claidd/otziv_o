package com.hunt.otziv.z_zp.service;

import com.hunt.otziv.l_lead.model.Lead;
import com.hunt.otziv.contractor_payments.model.ContractorRole;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentRuntimeSwitch;
import com.hunt.otziv.contractor_payments.service.ContractorRewardAttributionService;
import com.hunt.otziv.contractor_payments.service.ContractorRewardAttributionSnapshotCodec;
import com.hunt.otziv.contractor_payments.service.ContractorRewardLedgerService;
import com.hunt.otziv.contractor_payments.service.ContractorRewardSourceCodes;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.u_users.model.*;
import com.hunt.otziv.u_users.service.UserService;
import com.hunt.otziv.u_users.service.WorkerService;
import com.hunt.otziv.z_zp.dto.ZpDTO;
import com.hunt.otziv.z_zp.dto.ZpStatView;
import com.hunt.otziv.z_zp.model.Zp;
import com.hunt.otziv.z_zp.repository.ZpRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.util.Pair;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.hunt.otziv.admin.model.Quadruple;

import java.math.BigDecimal;
import java.security.Principal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@Slf4j
@RequiredArgsConstructor
public class ZpServiceImpl implements ZpService{
    private static final BigDecimal LEAD_BONUS = new BigDecimal("1000.00");

    private final ZpRepository zpRepository;
    private final UserService userService;
    private final ContractorRewardAttributionService contractorRewardAttributionService;
    private final ContractorPaymentRuntimeSwitch contractorPaymentRuntimeSwitch;
    private final ContractorRewardLedgerService contractorRewardLedgerService;

    public List<Zp> getAllWorkerZp(String login){
        LocalDate localDate = LocalDate.now();
        Long userId = userService.findByUserName(login).orElseThrow().getId();
        LocalDate start = localDate.withDayOfMonth(1);
        return zpRepository.getAllWorkerZpInPeriod(userId, start, start.plusMonths(1));
    }

    public List<Zp> getAllWorkerZpToDate(String login, LocalDate firstDayOfMonth, LocalDate lastDayOfMonth){
        Long userId = userService.findByUserName(login).orElseThrow().getId();
        return zpRepository.getAllWorkerZp(userId, firstDayOfMonth, lastDayOfMonth);
    }

    public List<Zp> findAll(){
        return zpRepository.findAll();
    }

    public List<Zp> findAllToDate(LocalDate localDate){ // Берем все ЗП для админа
        Pair<LocalDate, LocalDate> period = currentAndPreviousYearPeriod(localDate);
        return zpRepository.findAllToDate(period.getFirst(), period.getSecond());
    }  // Берем все ЗП для админа

    public List<ZpStatView> findStatRowsToDate(LocalDate localDate) {
        Pair<LocalDate, LocalDate> period = currentAndPreviousYearPeriod(localDate);
        return zpRepository.findStatRowsToDate(period.getFirst(), period.getSecond()).stream()
                .map(ZpStatView.class::cast)
                .toList();
    }

    public List<Zp> findAllToDateByUser(LocalDate localDate, Long userId) { // Берем все ЗП для Работника
        Pair<LocalDate, LocalDate> period = currentAndPreviousYearPeriod(localDate);
        return zpRepository.findAllToDateByUser(period.getFirst(), period.getSecond(), userId);
    } // Берем все ЗП для Работника

    @Override
    public BigDecimal sumByUserAndCreated(Long userId, LocalDate created) {
        if (userId == null || created == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal sum = zpRepository.sumByUserAndCreated(userId, created);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    @Override
    public long countByUserAndCreated(Long userId, LocalDate created) {
        if (userId == null || created == null) {
            return 0L;
        }
        return zpRepository.countByUserAndCreated(userId, created);
    }

    /** Берем все ЗП ЗА МЕСЯЦ всех юзеров на сайте для телеграмма**/
    @Override
    public Map<String, Pair<String, Long>> getAllZpToMonthToTelegram(LocalDate firstDayOfMonth, LocalDate lastDayOfMonth) {
        return zpRepository.findAllUsersWithZpToDate(firstDayOfMonth, lastDayOfMonth)
                .stream()
                .filter(obj -> {
                    String role = (String) obj[2];
                    // Фильтруем только по ролям
                    return "ROLE_MANAGER".equals(role) || "ROLE_WORKER".equals(role) || "ROLE_MARKETOLOG".equals(role);
                })
                .sorted(Comparator.comparing((Object[] obj) -> {
                                    String role = (String) obj[2];
                                    return rolePriority(role); // Сортируем сначала по приоритету роли
                                })
                                .thenComparing(obj -> ((Number) obj[1]).longValue(), Comparator.reverseOrder()) // Затем по сумме
                )
                .collect(Collectors.toMap(
                        obj -> (String) obj[0],   // ФИО
                        obj -> Pair.of((String) obj[2], ((Number) obj[1]).longValue()), // Роль + Сумма
                        (e1, e2) -> e1,
                        LinkedHashMap::new // Сохраняем порядок сортировки
                ));
    }
    /** Берем все ЗП ЗА МЕСЯЦ всех юзеров на сайте и распределяем в мапу (фио, роль, сумма зп, кол-во заказов, кол-во отзывов **/
    @Override
    public Map<String, Quadruple<String, Long, Long, Long>> getAllZpToMonth(LocalDate firstDayOfMonth, LocalDate lastDayOfMonth) {
        Map<String, Quadruple<String, Long, Long, Long>> results = zpRepository.findAllUsersWithZpToDate(firstDayOfMonth, lastDayOfMonth)
                .stream()
                .sorted(Comparator.comparing((Object[] obj) -> {
                                    String role = (String) obj[2];
                                    return rolePriority(role);
                                })
                                .thenComparing(obj -> ((Number) obj[1]).longValue(), Comparator.reverseOrder()) // Сортировка по зарплате
                )
                .collect(Collectors.toMap(
                        obj -> (String) obj[0], // ФИО
                        obj -> Quadruple.of(
                                (String) obj[2], // Роль
                                ((Number) obj[1]).longValue(), // Сумма зарплаты
                                ((Number) obj[3]).longValue(), // Сумма выплат (amount)
                                ((Number) obj[4]).longValue() // Количество отзывов
                        ),
                        (e1, e2) -> e1,
                        LinkedHashMap::new // Сохранение порядка сортировки
                ));
//        System.out.println(results);
        return results;
    }


//    @Override
//    public Map<String, Pair<String, Long>, Pair<Long, Long>> getAllZpToMonth(LocalDate firstDayOfMonth, LocalDate lastDayOfMonth) {
//        return zpRepository.findAllUsersWithZpToDate(firstDayOfMonth, lastDayOfMonth)
//                .stream()
//                .sorted(Comparator.comparing((Object[] obj) -> {
//                                    String role = (String) obj[2];
//                                    return rolePriority(role); // Сортируем сначала по приоритету роли
//                                })
//                                .thenComparing(obj -> ((BigDecimal) obj[1]).longValue(), Comparator.reverseOrder()) // Затем по сумме
//                )
//                .collect(Collectors.toMap(
//                        obj -> (String) obj[0],   // ФИО
//                        obj -> Pair.of((String) obj[2], ((BigDecimal) obj[1]).longValue()), // Роль + Сумма
//                        (e1, e2) -> e1,
//                        LinkedHashMap::new // Сохраняем порядок сортировки
//                ));
//    }


    // Метод для присваивания приоритета ролям
    private int rolePriority(String role) {
        if ("ROLE_MANAGER".equals(role)) return 1; // Менеджеры первыми
        if ("ROLE_WORKER".equals(role)) return 2;  // Потом воркеры
        if ("ROLE_OPERATOR".equals(role)) return 3;  // Потом воркеры
        if ("ROLE_MARKETOLOG".equals(role)) return 4;  // Потом воркеры
        return 5; // Все остальные в конце
    }





    public List<Zp> findAllToDateByOwner(LocalDate localDate, Set<Manager> managerList) { // Берем все ЗП для всех менеджеров Владельца
        Set<Long> peopleIds = getPeopleIdToZp(managerList);
        if (peopleIds.isEmpty()) {
            return List.of();
        }
        Pair<LocalDate, LocalDate> period = currentAndPreviousYearPeriod(localDate);
        return zpRepository.findAllToDateByOwner(period.getFirst(), period.getSecond(), peopleIds);
    } // Берем все ЗП для всех менеджеров Владельца

    public List<ZpStatView> findStatRowsToDateByOwner(LocalDate localDate, Set<Manager> managerList) {
        Set<Long> peopleIds = getPeopleIdToZp(managerList);
        if (peopleIds.isEmpty()) {
            return List.of();
        }
        Pair<LocalDate, LocalDate> period = currentAndPreviousYearPeriod(localDate);
        return zpRepository.findStatRowsToDateByOwner(period.getFirst(), period.getSecond(), peopleIds).stream()
                .map(ZpStatView.class::cast)
                .toList();
    }

    public List<Zp> findAllByOwner(Set<Manager> managerList) {
        Set<Long> peopleIds = getPeopleIdToZp(managerList);
        if (peopleIds.isEmpty()) {
            return List.of();
        }
        return zpRepository.findAllByOwner(peopleIds);
    }

    private Set<Long> getPeopleIdToZp(Set<Manager> managerList) { // Составление списка ид всех менеджеров и их работников Владельца
        Set<Long> managerIds = managerList.stream().map(Manager::getUser).map(User::getId).collect(Collectors.toSet());
        Set<Long> workersIds = managerList.stream().map(Manager::getUser).map(User::getWorkers).flatMap(workers -> workers.stream().map(Worker::getUser)).map(User::getId).collect(Collectors.toSet());
        Set<Long> operatorIds = managerList.stream().map(Manager::getUser).map(User::getOperators).flatMap(operators -> operators.stream().map(Operator::getUser)).map(User::getId).collect(Collectors.toSet());
        Set<Long> marketologIds = managerList.stream().map(Manager::getUser).map(User::getMarketologs).flatMap(marketologs -> marketologs.stream().map(Marketolog::getUser)).map(User::getId).collect(Collectors.toSet());
        Set<Long> peopleId;
        return peopleId = Stream.of(managerIds, operatorIds, marketologIds, workersIds)
                .flatMap(Set::stream)
                .collect(Collectors.toSet());
    } // Составление списка ид всех менеджеров и их работников Владельца

    private Pair<LocalDate, LocalDate> currentAndPreviousYearPeriod(LocalDate localDate) {
        LocalDate anchor = localDate == null ? LocalDate.now() : localDate;
        LocalDate start = anchor.minusYears(1).withDayOfYear(1);
        LocalDate endExclusive = anchor.plusDays(1);
        return Pair.of(start, endExclusive);
    }

    public List<ZpDTO> getAllZpDTO(){
        return toDTOList(zpRepository.findAll());
    }

    @Transactional
    public boolean save(Order order) { // Сохранить ЗП и Чек в БД
        BigDecimal sum = order != null && order.getSum() != null ? order.getSum() : BigDecimal.ZERO;
        int amount = order != null ? order.getAmount() : 0;
        return save(order, sum, amount);
    }// Сохранить ЗП и Чек в БД

    @Transactional
    public boolean save(Order order, BigDecimal sum, int amount) { // Сохранить ЗП и Чек в БД
        try {
            saveZpManager(order, sum, amount);
            if (liveRewardAttributionEnabled()) {
                saveZpWorkers(order, sum);
            } else {
                saveZpWorker(order, sum, amount);
            }
            return true;
        } catch (Exception e) {
            log.error("Ошибка при сохранении начислений и чека в БД", e);
            throw new RuntimeException("Ошибка при сохранении начислений и чека в БД", e); // выбрасываем исключение, чтобы откатить транзакцию
        }
    }// Сохранить ЗП и Чек в БД

    @Transactional
    public boolean saveLeadZp(Lead lead){ // Сохранить ЗП за Лида
        try {
            saveZpMarketolog(lead);
            saveZpOperator(lead);
            return true;
        }
        catch (Exception e){
            throw new RuntimeException("Ошибка при сохранении начислений и чека в БД", e); // выбрасываем исключение, чтобы откатить транзакцию
        }
    } // Сохранить ЗП за Лида

    @Transactional
    protected void saveZpManager(Order order){ // Сохранить ЗП Менеджера в БД
        BigDecimal sum = order != null && order.getSum() != null ? order.getSum() : BigDecimal.ZERO;
        int amount = order != null ? order.getAmount() : 0;
        saveZpManager(order, sum, amount);
    } // Сохранить ЗП Менеджера в БД

    @Transactional
    protected void saveZpManager(Order order, BigDecimal sum, int amount){ // Сохранить ЗП Менеджера в БД
        try {
            Zp managerZp = new Zp();
            managerZp.setFio(order.getManager().getUser().getFio());
            managerZp.setSum(sum.multiply(order.getManager().getUser().getCoefficient()));
            managerZp.setOrderId(order.getId());
            managerZp.setPaymentStatusGuardId(order.getStatus() == null ? null : order.getStatus().getId());
            managerZp.setUserId(order.getManager().getUser().getId());
            managerZp.setProfessionId(order.getManager().getId());
            managerZp.setAmount(amount);
            managerZp.setActive(true);
            managerZp.setSource(managerRewardSource());
            managerZp.setContractorRole(ContractorRole.MANAGER);
            Zp saved = zpRepository.save(managerZp);
            contractorRewardLedgerService.synchronizeSourcesSafely(List.of(saved));
        } catch (Exception e){
            throw new RuntimeException("Ошибка при сохранении начислений и чека в БД", e); // выбрасываем исключение, чтобы откатить транзакцию
        }

    } // Сохранить ЗП Менеджера в БД
    @Transactional
    protected void saveZpWorker(Order order){ // Сохранить ЗП Работника в БД
        BigDecimal sum = order != null && order.getSum() != null ? order.getSum() : BigDecimal.ZERO;
        int amount = order != null ? order.getAmount() : 0;
        saveZpWorker(order, sum, amount);
    } // Сохранить ЗП Работника в БД

    @Transactional
    protected void saveZpWorker(Order order, BigDecimal sum, int amount){ // Сохранить ЗП Работника в БД
        saveZpWorker(order, sum, amount, false);
    }

    private void saveZpWorker(Order order, BigDecimal sum, int amount, boolean attributionFinal) {
        try {
            Zp workerZp = new Zp();
            workerZp.setFio(order.getWorker().getUser().getFio());
            workerZp.setSum(sum.multiply(order.getWorker().getUser().getCoefficient()));
            workerZp.setOrderId(order.getId());
            workerZp.setPaymentStatusGuardId(order.getStatus() == null ? null : order.getStatus().getId());
            workerZp.setUserId(order.getWorker().getUser().getId());
            workerZp.setProfessionId(order.getWorker().getId());
            workerZp.setAmount(amount);
            workerZp.setActive(true);
            workerZp.setSource(specialistRewardSource(attributionFinal));
            workerZp.setContractorRole(ContractorRole.SPECIALIST);
            workerZp.setAttributionFinal(attributionFinal);
            workerZp.setRewardBasis(sum);
            if (!attributionFinal) {
                workerZp.setAttributionSnapshot(legacyAttributionSnapshot(order, sum, amount));
            }
            Zp saved = zpRepository.save(workerZp);
            contractorRewardLedgerService.synchronizeSourcesSafely(List.of(saved));
        } catch (Exception e){
            throw new RuntimeException("Ошибка при сохранении начислений и чека в БД", e); // выбрасываем исключение, чтобы откатить транзакцию
        }

    } // Сохранить ЗП Работника в БД

    /**
     * The attribution snapshot feeds only the contractor test ledger. It must
     * never become a new failure condition for the legacy reward write.
     */
    private String legacyAttributionSnapshot(Order order, BigDecimal rewardBasis, int amount) {
        try {
            String snapshot = ContractorRewardAttributionSnapshotCodec.encode(
                    contractorRewardAttributionService.attributeRecordedWork(order)
            );
            if (snapshot != null) {
                return snapshot;
            }
        } catch (RuntimeException exception) {
            log.warn(
                    "Снимок распределения начисления сформирован по текущему специалисту: orderId={}, failure={}",
                    order == null ? null : order.getId(),
                    exception.getClass().getSimpleName()
            );
        }

        try {
            Worker worker = order == null ? null : order.getWorker();
            if (worker == null
                    || worker.getId() == null
                    || worker.getUser() == null
                    || worker.getUser().getId() == null) {
                return null;
            }
            return ContractorRewardAttributionSnapshotCodec.encode(List.of(
                    new ContractorRewardAttributionService.SpecialistShare(
                            worker.getUser(),
                            worker.getId(),
                            rewardBasis == null ? BigDecimal.ZERO : rewardBasis,
                            Math.max(0, amount)
                    )
            ));
        } catch (RuntimeException exception) {
            log.warn(
                    "Снимок распределения начисления пропущен без отмены основного начисления: orderId={}, failure={}",
                    order == null ? null : order.getId(),
                    exception.getClass().getSimpleName()
            );
            return null;
        }
    }

    @Transactional
    protected void saveZpWorkers(Order order, BigDecimal payableSum) {
        List<ContractorRewardAttributionService.SpecialistShare> shares =
                contractorRewardAttributionService.attribute(order, payableSum);
        if (shares.isEmpty()) {
            // The LIVE attribution decision is durable even when attribution
            // legitimately falls back to the current specialist. Otherwise a
            // later flag toggle could re-split this already-issued source.
            saveZpWorker(order, payableSum, order == null ? 0 : order.getAmount(), true);
            return;
        }
        for (ContractorRewardAttributionService.SpecialistShare share : shares) {
            User user = share.user();
            BigDecimal coefficient = user.getCoefficient() == null ? BigDecimal.ZERO : user.getCoefficient();
            Zp workerZp = new Zp();
            workerZp.setFio(user.getFio());
            workerZp.setSum(share.grossAmount().multiply(coefficient));
            workerZp.setOrderId(order.getId());
            workerZp.setPaymentStatusGuardId(order.getStatus() == null ? null : order.getStatus().getId());
            workerZp.setUserId(user.getId());
            workerZp.setProfessionId(share.workerId());
            workerZp.setAmount(share.workUnits());
            workerZp.setActive(true);
            workerZp.setSource(ContractorRewardSourceCodes.ORDER_COMPLETION_SPECIALIST);
            workerZp.setContractorRole(ContractorRole.SPECIALIST);
            workerZp.setAttributionFinal(true);
            workerZp.setRewardBasis(share.grossAmount());
            Zp saved = zpRepository.save(workerZp);
            contractorRewardLedgerService.synchronizeSourcesSafely(List.of(saved));
        }
    }

    private boolean liveRewardAttributionEnabled() {
        return contractorPaymentRuntimeSwitch.rewardAttributionLiveEnabled();
    }

    private String managerRewardSource() {
        return liveRewardAttributionEnabled()
                ? ContractorRewardSourceCodes.ORDER_COMPLETION_MANAGER
                : ContractorRewardSourceCodes.LEGACY_ORDER_MANAGER;
    }

    private String specialistRewardSource(boolean attributionFinal) {
        return attributionFinal || liveRewardAttributionEnabled()
                ? ContractorRewardSourceCodes.ORDER_COMPLETION_SPECIALIST
                : ContractorRewardSourceCodes.LEGACY_ORDER_SPECIALIST;
    }
    @Transactional
    protected void saveZpMarketolog(Lead lead){ // Сохранить ЗП Маркетолога в БД
        if (lead == null || lead.getMarketolog() == null || lead.getMarketolog().getUser() == null) {
            log.debug("Вознаграждение маркетолога за лид не начислено: у лида не указан маркетолог");
            return;
        }

        try {
            Marketolog marketolog = lead.getMarketolog();
            User user = marketolog.getUser();
            Zp marketologZp = new Zp();
            marketologZp.setFio(user.getFio());
            marketologZp.setSum(LEAD_BONUS.multiply(user.getCoefficient()));
            marketologZp.setUserId(user.getId());
            marketologZp.setOrderId(0L);
            marketologZp.setProfessionId(marketolog.getId());
            marketologZp.setAmount(1);
            marketologZp.setActive(true);
            zpRepository.save(marketologZp);
        } catch (Exception e){
            throw new RuntimeException("Ошибка при сохранении начислений и чека в БД", e); // выбрасываем исключение, чтобы откатить транзакцию
        }

    } // Сохранить ЗП Маркетолога в БД

    @Transactional
    protected void saveZpOperator(Lead lead){ // Сохранить ЗП Оператора в БД
        if (lead == null || lead.getOperator() == null || lead.getOperator().getUser() == null) {
            log.debug("Вознаграждение оператора за лид не начислено: у лида не указан оператор");
            return;
        }

        try {
            Operator operator = lead.getOperator();
            User user = operator.getUser();
            Zp operatorZp = new Zp();
            operatorZp.setFio(user.getFio());
            operatorZp.setSum(LEAD_BONUS.multiply(user.getCoefficient()));
            operatorZp.setUserId(user.getId());
            operatorZp.setProfessionId(operator.getId());
            operatorZp.setOrderId(0L);
            operatorZp.setAmount(1);
            operatorZp.setActive(true);
            zpRepository.save(operatorZp);
        } catch (Exception e){
            throw new RuntimeException("Ошибка при сохранении начислений и чека в БД", e); // выбрасываем исключение, чтобы откатить транзакцию
        }

    } // Сохранить ЗП Оператора в БД

    private List<ZpDTO> toDTOList(List<Zp> zpList) { // Метод для преобразования из сущности Zp в ZpDTO
        return zpList.stream().map(this::toDTO).collect(Collectors.toList());
    } // Метод для преобразования из сущности Zp в ZpDTO

    private ZpDTO toDTO(Zp zp) { // Метод для преобразования из сущности Zp в ZpDTO
        ZpDTO zpDTO = new ZpDTO();
        zpDTO.setId(zp.getId());
        zpDTO.setFio(zp.getFio());
        zpDTO.setUserId(zp.getUserId());
        zpDTO.setProfessionId(zp.getProfessionId());
        zpDTO.setOrderId(zp.getOrderId());
        zpDTO.setCreated(zp.getCreated());
        zpDTO.setActive(zp.isActive());
        zpDTO.setAmount(zp.getAmount());
        zpDTO.setSum(zp.getSum());
        return zpDTO;
    } // Метод для преобразования из сущности Zp в ZpDTO


    private Zp toEntity(ZpDTO zpDTO) { // Метод для преобразования из ZpDTO в сущность Zp
        Zp zp = new Zp();
        zp.setFio(zpDTO.getFio());
        zp.setUserId(zpDTO.getUserId());
        zp.setOrderId(zpDTO.getOrderId());
        zp.setProfessionId(zpDTO.getProfessionId());
        zp.setCreated(zpDTO.getCreated());
        zp.setActive(zpDTO.isActive());
        zp.setAmount(zpDTO.getAmount());
        zp.setSum(zpDTO.getSum());
        return zp;
    } // Метод для преобразования из ZpDTO в сущность Zp

}
