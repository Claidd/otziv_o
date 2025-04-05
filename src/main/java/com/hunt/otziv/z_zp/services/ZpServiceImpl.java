package com.hunt.otziv.z_zp.services;

import com.hunt.otziv.l_lead.model.Lead;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.u_users.model.*;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.u_users.services.service.WorkerService;
import com.hunt.otziv.z_zp.dto.ZpDTO;
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

    private final ZpRepository zpRepository;
    private final UserService userService;

    public List<Zp> getAllWorkerZp(String login){
        LocalDate localDate = LocalDate.now();
        Long userId = userService.findByUserName(login).orElseThrow().getId();
        return zpRepository.getAllWorkerZp(userId, localDate);
    }

    public List<Zp> getAllWorkerZpToDate(String login, LocalDate firstDayOfMonth, LocalDate lastDayOfMonth){
        Long userId = userService.findByUserName(login).orElseThrow().getId();
        return zpRepository.getAllWorkerZp(userId, firstDayOfMonth, lastDayOfMonth);
    }

    public List<Zp> findAll(){
        return zpRepository.findAll();
    }

    public List<Zp> findAllToDate(LocalDate localDate){ // Берем все ЗП для админа
        LocalDate localDate2 = localDate.minusYears(1);
        return zpRepository.findAllToDate(localDate, localDate2);
    }  // Берем все ЗП для админа

    public List<Zp> findAllToDateByUser(LocalDate localDate, Long userId) { // Берем все ЗП для Работника
        LocalDate localDate2 = localDate.minusYears(1);
        return zpRepository.findAllToDateByUser(localDate, localDate2, userId);
    } // Берем все ЗП для Работника

    /** Берем все ЗП ЗА МЕСЯЦ всех юзеров на сайте для телеграмма**/
    @Override
    public Map<String, Pair<String, Long>> getAllZpToMonthToTelegram(LocalDate firstDayOfMonth, LocalDate lastDayOfMonth) {
        return zpRepository.findAllUsersWithZpToDate(firstDayOfMonth, lastDayOfMonth)
                .stream()
                .filter(obj -> {
                    String role = (String) obj[2];
                    // Фильтруем только по ролям
                    return "ROLE_MANAGER".equals(role) || "ROLE_WORKER".equals(role);
                })
                .sorted(Comparator.comparing((Object[] obj) -> {
                                    String role = (String) obj[2];
                                    return rolePriority(role); // Сортируем сначала по приоритету роли
                                })
                                .thenComparing(obj -> ((BigDecimal) obj[1]).longValue(), Comparator.reverseOrder()) // Затем по сумме
                )
                .collect(Collectors.toMap(
                        obj -> (String) obj[0],   // ФИО
                        obj -> Pair.of((String) obj[2], ((BigDecimal) obj[1]).longValue()), // Роль + Сумма
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
                                ((Long) obj[4]) // Количество отзывов
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
        LocalDate localDate2 = localDate.minusYears(1);
        return zpRepository.findAllToDateByOwner(localDate, localDate2, getPeopleIdToZp(managerList));
    } // Берем все ЗП для всех менеджеров Владельца

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

    public List<ZpDTO> getAllZpDTO(){
        return toDTOList(zpRepository.findAll());
    }

    @Transactional
    public boolean save(Order order) { // Сохранить ЗП и Чек в БД
        try {
            saveZpManager(order);
            saveZpWorker(order);
            return true;
        } catch (Exception e) {
            log.error("Ошибка при сохранении ЗП и Чека в БД", e);
            throw new RuntimeException("Ошибка при сохранении ЗП и Чека в БД", e); // выбрасываем исключение, чтобы откатить транзакцию
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
            throw new RuntimeException("Ошибка при сохранении ЗП и Чека в БД", e); // выбрасываем исключение, чтобы откатить транзакцию
        }
    } // Сохранить ЗП за Лида

    @Transactional
    protected void saveZpManager(Order order){ // Сохранить ЗП Менеджера в БД
        try {
            Zp managerZp = new Zp();
            managerZp.setFio(order.getManager().getUser().getFio());
            managerZp.setSum(order.getSum().multiply(order.getManager().getUser().getCoefficient()));
            managerZp.setOrderId(order.getId());
            managerZp.setUserId(order.getManager().getUser().getId());
            managerZp.setProfessionId(order.getManager().getId());
            managerZp.setAmount(order.getAmount());
            managerZp.setActive(true);
            zpRepository.save(managerZp);
        } catch (Exception e){
            throw new RuntimeException("Ошибка при сохранении ЗП и Чека в БД", e); // выбрасываем исключение, чтобы откатить транзакцию
        }

    } // Сохранить ЗП Менеджера в БД
    @Transactional
    protected void saveZpWorker(Order order){ // Сохранить ЗП Работника в БД
        try {
            Zp workerZp = new Zp();
            workerZp.setFio(order.getWorker().getUser().getFio());
            workerZp.setSum(order.getSum().multiply(order.getWorker().getUser().getCoefficient()));
            workerZp.setOrderId(order.getId());
            workerZp.setUserId(order.getWorker().getUser().getId());
            workerZp.setProfessionId(order.getWorker().getId());
            workerZp.setAmount(order.getAmount());
            workerZp.setActive(true);
            zpRepository.save(workerZp);
        } catch (Exception e){
            throw new RuntimeException("Ошибка при сохранении ЗП и Чека в БД", e); // выбрасываем исключение, чтобы откатить транзакцию
        }

    } // Сохранить ЗП Работника в БД
    @Transactional
    protected void saveZpMarketolog(Lead lead){ // Сохранить ЗП Маркетолога в БД
        try {
            Zp marketologZp = new Zp();
            marketologZp.setFio(lead.getMarketolog().getUser().getFio());
            marketologZp.setSum(new BigDecimal("1000.00").multiply(lead.getMarketolog().getUser().getCoefficient()));
            marketologZp.setUserId(lead.getMarketolog().getUser().getId());
            marketologZp.setOrderId(0L);
            marketologZp.setProfessionId(lead.getMarketolog().getId());
            marketologZp.setAmount(1);
            marketologZp.setActive(true);
            zpRepository.save(marketologZp);
        } catch (Exception e){
            throw new RuntimeException("Ошибка при сохранении ЗП и Чека в БД", e); // выбрасываем исключение, чтобы откатить транзакцию
        }

    } // Сохранить ЗП Маркетолога в БД

    @Transactional
    protected void saveZpOperator(Lead lead){ // Сохранить ЗП Оператора в БД
        try {
            Zp operatorZp = new Zp();
            operatorZp.setFio(lead.getOperator().getUser().getFio());
            operatorZp.setSum(new BigDecimal("1000.00").multiply(lead.getOperator().getUser().getCoefficient()));
            operatorZp.setUserId(lead.getOperator().getUser().getId());
            operatorZp.setProfessionId(lead.getOperator().getId());
            operatorZp.setOrderId(0L);
            operatorZp.setAmount(1);
            operatorZp.setActive(true);
            zpRepository.save(operatorZp);
        } catch (Exception e){
            throw new RuntimeException("Ошибка при сохранении ЗП и Чека в БД", e); // выбрасываем исключение, чтобы откатить транзакцию
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
