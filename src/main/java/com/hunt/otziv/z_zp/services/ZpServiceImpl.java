package com.hunt.otziv.z_zp.services;

import com.hunt.otziv.l_lead.model.Lead;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.u_users.model.*;
import com.hunt.otziv.u_users.repository.MarketologRepository;
import com.hunt.otziv.u_users.repository.OperatorRepository;
import com.hunt.otziv.u_users.repository.WorkerRepository;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.u_users.services.service.WorkerService;
import com.hunt.otziv.z_zp.dto.ManagerZpAggregate;
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
public class ZpServiceImpl implements ZpService {

    private final ZpRepository zpRepository;
    private final UserService userService;

    @Override
    public List<Zp> getAllWorkerZp(String login) {
        LocalDate localDate = LocalDate.now();
        Long userId = userService.findByUserName(login).orElseThrow().getId();
        return zpRepository.getAllWorkerZp(userId, localDate);
    }

    @Override
    public List<Zp> getAllWorkerZp(Long userId) {
        LocalDate localDate = LocalDate.now();
        return zpRepository.getAllWorkerZp(userId, localDate);
    }

    @Override
    public List<Zp> getAllWorkerZpToDate(String login, LocalDate firstDayOfMonth, LocalDate lastDayOfMonth) {
        Long userId = userService.findByUserName(login).orElseThrow().getId();
        return zpRepository.getAllWorkerZp(userId, firstDayOfMonth, lastDayOfMonth);
    }

    @Override
    public List<Zp> getAllWorkerZpToDate(Long userId, LocalDate firstDayOfMonth, LocalDate lastDayOfMonth) {
        return zpRepository.getAllWorkerZp(userId, firstDayOfMonth, lastDayOfMonth);
    }

    @Override
    public List<Zp> findAll() {
        return zpRepository.findAll();
    }

    @Override
    public List<Zp> findAllToDate(LocalDate localDate) {
        LocalDate localDate2 = localDate.minusYears(1);
        return zpRepository.findAllToDate(localDate, localDate2);
    }

    @Override
    public List<Zp> findAllToDateByUser(LocalDate localDate, Long userId) {
        LocalDate localDate2 = localDate.minusYears(1);
        return zpRepository.findAllToDateByUser(localDate, localDate2, userId);
    }

    @Override
    public Map<String, Pair<String, Long>> getAllZpToMonthToTelegram(LocalDate firstDayOfMonth, LocalDate lastDayOfMonth) {
        return zpRepository.findAllUsersWithZpToDate(firstDayOfMonth, lastDayOfMonth)
                .stream()
                .filter(obj -> {
                    String role = (String) obj[2];
                    return "ROLE_MANAGER".equals(role) || "ROLE_WORKER".equals(role);
                })
                .sorted(
                        Comparator.comparing((Object[] obj) -> {
                                    String role = (String) obj[2];
                                    return rolePriority(role);
                                })
                                .thenComparing(obj -> ((BigDecimal) obj[1]).longValue(), Comparator.reverseOrder())
                )
                .collect(Collectors.toMap(
                        obj -> (String) obj[0],
                        obj -> Pair.of((String) obj[2], ((BigDecimal) obj[1]).longValue()),
                        (e1, e2) -> e1,
                        LinkedHashMap::new
                ));
    }

    @Override
    public Map<String, Quadruple<String, Long, Long, Long>> getAllZpToMonth(LocalDate firstDayOfMonth, LocalDate lastDayOfMonth) {
        return zpRepository.findAllUsersWithZpToDate(firstDayOfMonth, lastDayOfMonth)
                .stream()
                .sorted(
                        Comparator.comparing((Object[] obj) -> {
                                    String role = (String) obj[2];
                                    return rolePriority(role);
                                })
                                .thenComparing(obj -> ((Number) obj[1]).longValue(), Comparator.reverseOrder())
                )
                .collect(Collectors.toMap(
                        obj -> (String) obj[0],
                        obj -> Quadruple.of(
                                (String) obj[2],
                                ((Number) obj[1]).longValue(),
                                ((Number) obj[3]).longValue(),
                                ((Long) obj[4])
                        ),
                        (e1, e2) -> e1,
                        LinkedHashMap::new
                ));
    }

    private int rolePriority(String role) {
        if ("ROLE_MANAGER".equals(role)) {
            return 1;
        }
        if ("ROLE_WORKER".equals(role)) {
            return 2;
        }
        if ("ROLE_OPERATOR".equals(role)) {
            return 3;
        }
        if ("ROLE_MARKETOLOG".equals(role)) {
            return 4;
        }
        return 5;
    }

    @Override
    public List<Zp> findAllToDateByOwner(LocalDate localDate, Set<Manager> managerList) {
        if (localDate == null || managerList == null || managerList.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> managerIds = managerList.stream()
                .map(Manager::getId)
                .filter(Objects::nonNull)
                .toList();

        return findAllToDateByOwnerIds(localDate, managerIds);
    }

    @Override
    public Map<Long, ManagerZpAggregate> getManagerAggregates(Set<Long> userIds, LocalDate startDate, LocalDate endDate) {
        return getUserAggregates(userIds, startDate, endDate);
    }

    @Override
    public Map<Long, ManagerZpAggregate> getUserAggregates(Set<Long> userIds, LocalDate startDate, LocalDate endDate) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }

        return zpRepository.aggregateUserZpByUserIds(userIds, startDate, endDate)
                .stream()
                .collect(Collectors.toMap(
                        row -> (Long) row[0],
                        row -> new ManagerZpAggregate(
                                (BigDecimal) row[1],
                                (Long) row[2],
                                ((Number) row[3]).longValue()
                        )
                ));
    }

    @Override
    public List<ZpDTO> getAllZpDTO() {
        return toDTOList(zpRepository.findAll());
    }

    @Override
    @Transactional
    public boolean save(Order order) {
        try {
            saveZpManager(order);
            saveZpWorker(order);
            return true;
        } catch (Exception e) {
            log.error("Ошибка при сохранении ЗП и Чека в БД", e);
            throw new RuntimeException("Ошибка при сохранении ЗП и Чека в БД", e);
        }
    }

    @Override
    @Transactional
    public boolean saveLeadZp(Lead lead) {
        try {
            saveZpMarketolog(lead);
            saveZpOperator(lead);
            return true;
        } catch (Exception e) {
            throw new RuntimeException("Ошибка при сохранении ЗП и Чека в БД", e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<Zp> findAllToDateByUserIds(LocalDate localDate, Set<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyList();
        }

        LocalDate firstDayOfMonth = localDate.withDayOfMonth(1);
        LocalDate lastDayOfMonth = localDate.withDayOfMonth(localDate.lengthOfMonth());

        return zpRepository.findAllToDateByUserIds(firstDayOfMonth, lastDayOfMonth, userIds);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Zp> findAllToDateByOwnerIds(LocalDate localDate, List<Long> managerIds) {
        if (localDate == null || managerIds == null || managerIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> relevantUserIds = userService.findAllRelevantUserIdsForManagerIds(managerIds);
        if (relevantUserIds == null || relevantUserIds.isEmpty()) {
            return Collections.emptyList();
        }

        LocalDate firstDayOfMonth = localDate.withDayOfMonth(1);
        LocalDate lastDayOfMonth = localDate.withDayOfMonth(localDate.lengthOfMonth());

        return zpRepository.findAllToDateByUserIds(firstDayOfMonth, lastDayOfMonth, relevantUserIds);
    }

    @Transactional
    protected void saveZpManager(Order order) {
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
        } catch (Exception e) {
            throw new RuntimeException("Ошибка при сохранении ЗП менеджера", e);
        }
    }

    @Transactional
    protected void saveZpWorker(Order order) {
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
        } catch (Exception e) {
            throw new RuntimeException("Ошибка при сохранении ЗП работника", e);
        }
    }

    @Transactional
    protected void saveZpMarketolog(Lead lead) {
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
        } catch (Exception e) {
            throw new RuntimeException("Ошибка при сохранении ЗП маркетолога", e);
        }
    }

    @Transactional
    protected void saveZpOperator(Lead lead) {
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
        } catch (Exception e) {
            throw new RuntimeException("Ошибка при сохранении ЗП оператора", e);
        }
    }

    private List<ZpDTO> toDTOList(List<Zp> zpList) {
        return zpList.stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    private ZpDTO toDTO(Zp zp) {
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
    }

    private Zp toEntity(ZpDTO zpDTO) {
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
    }
}
