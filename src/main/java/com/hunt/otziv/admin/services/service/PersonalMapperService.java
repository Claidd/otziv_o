package com.hunt.otziv.admin.services.service;

import com.hunt.otziv.admin.dto.presonal.ManagersListDTO;
import com.hunt.otziv.admin.dto.presonal.MarketologsListDTO;
import com.hunt.otziv.admin.dto.presonal.OperatorsListDTO;
import com.hunt.otziv.admin.dto.presonal.WorkersListDTO;
import com.hunt.otziv.l_lead.services.serv.LeadService;
import com.hunt.otziv.p_products.services.service.OrderService;
import com.hunt.otziv.r_review.services.ReviewService;
import com.hunt.otziv.u_users.model.*;
import com.hunt.otziv.u_users.services.service.ManagerService;
import com.hunt.otziv.u_users.services.service.MarketologService;
import com.hunt.otziv.u_users.services.service.OperatorService;
import com.hunt.otziv.u_users.services.service.WorkerService;
import com.hunt.otziv.z_zp.dto.ManagerZpAggregate;
import com.hunt.otziv.z_zp.model.PaymentCheck;
import com.hunt.otziv.z_zp.model.Zp;
import com.hunt.otziv.z_zp.services.PaymentCheckService;
import com.hunt.otziv.z_zp.services.ZpService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.Principal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.Principal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.Principal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class PersonalMapperService {

    private final ManagerService managerService;
    private final MarketologService marketologService;
    private final WorkerService workerService;
    private final OperatorService operatorService;
    private final ZpService zpService;
    private final PaymentCheckService paymentCheckService;
    private final LeadService leadService;
    private final ReviewService reviewService;
    private final OrderService orderService;

    private static final String STATUS_IN_WORK = "В работе";
    private static final String STATUS_NEW = "Новый";
    private static final String STATUS_CORRECTION = "Коррекция";
    private static final long DEFAULT_IMAGE_ID = 1L;

    public List<ManagersListDTO> getManagers() {
        return managerService.getAllManagers().stream()
                .map(this::toManagersListDTO)
                .collect(Collectors.toList());
    }

    public List<MarketologsListDTO> getMarketologs() {
        return marketologService.getAllMarketologs().stream()
                .map(this::toMarketologsListDTO)
                .collect(Collectors.toList());
    }

    public List<WorkersListDTO> gerWorkers() {
        return workerService.getAllWorkers().stream()
                .map(this::toWorkersListDTO)
                .collect(Collectors.toList());
    }

    public List<OperatorsListDTO> gerOperators() {
        return operatorService.getAllOperators().stream()
                .map(this::toOperatorsListDTO)
                .collect(Collectors.toList());
    }

    public List<ManagersListDTO> getManagersToManager(Principal principal) {
        return managerService.getAllManagers().stream()
                .filter(p -> Objects.equals(p.getUser().getUsername(), principal.getName()))
                .map(this::toManagersListDTO)
                .collect(Collectors.toList());
    }

    public List<MarketologsListDTO> getMarketologsToManager(Manager manager) {
        return marketologService.getAllMarketologsToManager(manager).stream()
                .map(this::toMarketologsListDTO)
                .collect(Collectors.toList());
    }

    public List<WorkersListDTO> gerWorkersToManager(Manager manager) {
        return workerService.getAllWorkersToManager(manager).stream()
                .map(this::toWorkersListDTO)
                .collect(Collectors.toList());
    }

    public List<OperatorsListDTO> gerOperatorsToManager(Manager manager) {
        return operatorService.getAllOperatorsToManager(manager).stream()
                .map(this::toOperatorsListDTO)
                .collect(Collectors.toList());
    }

    public List<Manager> findAllManagersWorkers(List<Manager> managerList) {
        return managerService.findAllManagersWorkers(managerList);
    }

    public List<ManagersListDTO> getManagersToOwner(List<Manager> managers) {
        return managers.stream().map(this::toManagersListDTO).toList();
    }

    public List<MarketologsListDTO> getMarketologsToOwner(List<Marketolog> allMarketologs) {
        return allMarketologs.stream().map(this::toMarketologsListDTO).toList();
    }

    public List<OperatorsListDTO> gerOperatorsToOwner(List<Operator> allOperators) {
        return allOperators.stream().map(this::toOperatorsListDTO).toList();
    }

    public List<WorkersListDTO> getWorkersToOwner(List<Worker> allWorkers) {
        return allWorkers.stream().map(this::toWorkersListDTO).toList();
    }

    public List<OperatorsListDTO> gerOperatorsToOwner(Manager manager) {
        return operatorService.getAllOperatorsToManager(manager).stream()
                .map(this::toOperatorsListDTO)
                .collect(Collectors.toList());
    }

    @Transactional
    public List<ManagersListDTO> getManagersAndCount() {
        List<Manager> managers = managerService.getAllManagers();
        return buildManagersWithCount(managers, LocalDate.now());
    }

    @Transactional
    public List<MarketologsListDTO> getMarketologsAndCount() {
        List<Marketolog> marketologs = marketologService.getAllMarketologs();
        return buildMarketologsWithCount(marketologs, LocalDate.now());
    }

    @Transactional
    public List<WorkersListDTO> gerWorkersToAndCount() {
        List<Worker> workers = workerService.getAllWorkers();
        return buildWorkersWithCount(workers, LocalDate.now(), true);
    }

    @Transactional
    public List<OperatorsListDTO> gerOperatorsAndCount() {
        List<Operator> operators = operatorService.getAllOperators();
        return buildOperatorsWithCount(operators, LocalDate.now());
    }

    public List<ManagersListDTO> getManagersAndCountToOwner(List<Manager> managers) {
        return buildManagersWithCount(managers, LocalDate.now());
    }

    @Transactional
    public List<MarketologsListDTO> getMarketologsAndCountToOwner(List<Marketolog> allMarketologs) {
        return buildMarketologsWithCount(allMarketologs, LocalDate.now());
    }

    @Transactional
    public List<WorkersListDTO> getWorkersToAndCountToOwner(List<Worker> allWorkers) {
        return buildWorkersWithCount(allWorkers, LocalDate.now(), true);
    }

    @Transactional
    public List<OperatorsListDTO> getOperatorsAndCountToOwner(List<Operator> allOperators) {
        return buildOperatorsWithCount(allOperators, LocalDate.now());
    }

    public List<ManagersListDTO> getManagersAndCountToDate(LocalDate localdate) {
        List<Manager> managers = managerService.getAllManagers();
        return buildManagersWithCount(managers, localdate);
    }

    @Transactional
    public List<MarketologsListDTO> getMarketologsAndCountToDate(LocalDate localdate) {
        List<Marketolog> marketologs = marketologService.getAllMarketologs();
        return buildMarketologsWithCount(marketologs, localdate);
    }

    @Transactional
    public List<WorkersListDTO> gerWorkersToAndCountToDate(LocalDate localdate) {
        List<Worker> workers = workerService.getAllWorkers();
        return buildWorkersWithCount(workers, localdate, false);
    }

    @Transactional
    public List<OperatorsListDTO> gerOperatorsAndCountToDate(LocalDate localdate) {
        List<Operator> operators = operatorService.getAllOperators();
        return buildOperatorsWithCount(operators, localdate);
    }

    public List<ManagersListDTO> getManagersAndCountToDateToOwner(List<Manager> managerList, LocalDate localdate) {
        return buildManagersWithCount(managerList, localdate);
    }

    @Transactional
    public List<MarketologsListDTO> getMarketologsAndCountToDateToOwner(List<Marketolog> marketologList, LocalDate localdate) {
        return buildMarketologsWithCount(marketologList, localdate);
    }

    @Transactional
    public List<WorkersListDTO> gerWorkersToAndCountToDateToOwner(List<Worker> workerList, LocalDate localdate) {
        return buildWorkersWithCount(workerList, localdate, false);
    }

    @Transactional
    public List<OperatorsListDTO> gerOperatorsAndCountToDateToOwner(List<Operator> operatorList, LocalDate localdate) {
        return buildOperatorsWithCount(operatorList, localdate);
    }

    private List<ManagersListDTO> buildManagersWithCount(List<Manager> managers, LocalDate localDate) {
        if (managers == null || managers.isEmpty()) {
            return Collections.emptyList();
        }

        LocalDate firstDayOfMonth = localDate.withDayOfMonth(1);
        LocalDate lastDayOfMonth = localDate.withDayOfMonth(localDate.lengthOfMonth());

        Set<Long> userIds = managers.stream()
                .map(Manager::getUser)
                .map(User::getId)
                .collect(Collectors.toSet());

        Map<Long, ManagerZpAggregate> zpAggregates =
                zpService.getUserAggregates(userIds, firstDayOfMonth, lastDayOfMonth);

        Map<Long, BigDecimal> paymentSums =
                paymentCheckService.getActiveManagerPaymentSums(userIds, firstDayOfMonth, localDate);

        Set<Manager> managerSet = new HashSet<>(managers);
        Map<Long, Long> leadsInWork =
                leadService.getManagerLeadsInWorkCount(managerSet, firstDayOfMonth, localDate);

        List<ManagersListDTO> result = new ArrayList<>();

        for (Manager manager : managers) {
            Long userId = manager.getUser().getId();

            ManagerZpAggregate zpAggregate = zpAggregates.getOrDefault(
                    userId,
                    new ManagerZpAggregate(BigDecimal.ZERO, 0L, 0L)
            );

            BigDecimal paymentSum = paymentSums.getOrDefault(userId, BigDecimal.ZERO);
            Long leadsCount = leadsInWork.getOrDefault(userId, 0L);

            result.add(ManagersListDTO.builder()
                    .id(manager.getId())
                    .userId(userId)
                    .fio(manager.getUser().getFio())
                    .login(manager.getUser().getUsername())
                    .imageId(resolveImageId(manager.getUser()))
                    .sum1Month(zpAggregate.getTotalSum().intValue())
                    .order1Month((int) zpAggregate.getOrderCount())
                    .review1Month((int) zpAggregate.getReviewAmount())
                    .payment1Month(paymentSum.intValue())
                    .leadsInWorkInMonth(leadsCount.intValue())
                    .build());
        }

        return result;
    }

    private List<MarketologsListDTO> buildMarketologsWithCount(List<Marketolog> marketologs, LocalDate localDate) {
        if (marketologs == null || marketologs.isEmpty()) {
            return Collections.emptyList();
        }

        LocalDate firstDayOfMonth = localDate.withDayOfMonth(1);
        LocalDate lastDayOfMonth = localDate.withDayOfMonth(localDate.lengthOfMonth());

        Set<Long> userIds = marketologs.stream()
                .map(Marketolog::getUser)
                .map(User::getId)
                .collect(Collectors.toSet());

        Map<Long, ManagerZpAggregate> zpAggregates =
                zpService.getUserAggregates(userIds, firstDayOfMonth, lastDayOfMonth);

        List<Long> marketologIds = marketologs.stream()
                .map(Marketolog::getId)
                .toList();

        Map<Long, Long> newLeadsMap = leadService.countNewLeadsByMarketologIdsToDate(marketologIds, localDate);
        Map<Long, Long> inWorkLeadsMap = leadService.countInWorkLeadsByMarketologIdsToDate(marketologIds, localDate);

        List<MarketologsListDTO> result = new ArrayList<>();

        for (Marketolog marketolog : marketologs) {
            User user = marketolog.getUser();

            ManagerZpAggregate zpAggregate = zpAggregates.getOrDefault(
                    user.getId(),
                    new ManagerZpAggregate(BigDecimal.ZERO, 0L, 0L)
            );

            Long newListLeads = newLeadsMap.getOrDefault(marketolog.getId(), 0L);
            Long inWorkListLeads = inWorkLeadsMap.getOrDefault(marketolog.getId(), 0L);
            Long percentInWork = safePercent(inWorkListLeads, newListLeads);

            result.add(MarketologsListDTO.builder()
                    .id(marketolog.getId())
                    .userId(user.getId())
                    .fio(user.getFio())
                    .login(user.getUsername())
                    .imageId(resolveImageId(user))
                    .sum1Month(zpAggregate.getTotalSum().intValue())
                    .order1Month((int) zpAggregate.getOrderCount())
                    .review1Month((int) zpAggregate.getReviewAmount())
                    .leadsNew(newListLeads)
                    .leadsInWork(inWorkListLeads)
                    .percentInWork(percentInWork)
                    .build());
        }

        return result;
    }

    private List<WorkersListDTO> buildWorkersWithCount(List<Worker> workers, LocalDate localDate, boolean includeLiveCounters) {
        if (workers == null || workers.isEmpty()) {
            return Collections.emptyList();
        }

        LocalDate firstDayOfMonth = localDate.withDayOfMonth(1);
        LocalDate lastDayOfMonth = localDate.withDayOfMonth(localDate.lengthOfMonth());

        Set<Long> userIds = workers.stream()
                .map(Worker::getUser)
                .map(User::getId)
                .collect(Collectors.toSet());

        Map<Long, ManagerZpAggregate> zpAggregates =
                zpService.getUserAggregates(userIds, firstDayOfMonth, lastDayOfMonth);

        List<Long> workerIds = workers.stream()
                .map(Worker::getId)
                .toList();

        Map<Long, Integer> newOrdersMap = includeLiveCounters
                ? orderService.countOrdersByWorkerIdsAndStatus(workerIds, STATUS_NEW)
                : Map.of();

        Map<Long, Integer> inCorrectMap = includeLiveCounters
                ? orderService.countOrdersByWorkerIdsAndStatus(workerIds, STATUS_CORRECTION)
                : Map.of();

        Map<Long, Integer> publishMap = includeLiveCounters
                ? reviewService.countOrdersByWorkerIdsAndStatusPublish(workerIds, localDate)
                : Map.of();

        Map<Long, Integer> vigulMap = includeLiveCounters
                ? reviewService.countOrdersByWorkerIdsAndStatusVigul(workerIds, localDate)
                : Map.of();

        List<WorkersListDTO> result = new ArrayList<>();

        for (Worker worker : workers) {
            User user = worker.getUser();

            ManagerZpAggregate zpAggregate = zpAggregates.getOrDefault(
                    user.getId(),
                    new ManagerZpAggregate(BigDecimal.ZERO, 0L, 0L)
            );

            WorkersListDTO.WorkersListDTOBuilder builder = WorkersListDTO.builder()
                    .id(worker.getId())
                    .userId(user.getId())
                    .fio(user.getFio())
                    .login(user.getUsername())
                    .imageId(resolveImageId(user))
                    .sum1Month(zpAggregate.getTotalSum().intValue())
                    .order1Month((int) zpAggregate.getOrderCount())
                    .review1Month((int) zpAggregate.getReviewAmount());

            if (includeLiveCounters) {
                builder.newOrder(newOrdersMap.getOrDefault(worker.getId(), 0));
                builder.inCorrect(inCorrectMap.getOrDefault(worker.getId(), 0));
                builder.intVigul(vigulMap.getOrDefault(worker.getId(), 0));
                builder.publish(publishMap.getOrDefault(worker.getId(), 0));
            }

            result.add(builder.build());
        }

        return result;
    }

    private List<OperatorsListDTO> buildOperatorsWithCount(List<Operator> operators, LocalDate localDate) {
        if (operators == null || operators.isEmpty()) {
            return Collections.emptyList();
        }

        LocalDate firstDayOfMonth = localDate.withDayOfMonth(1);
        LocalDate lastDayOfMonth = localDate.withDayOfMonth(localDate.lengthOfMonth());

        Set<Long> userIds = operators.stream()
                .map(Operator::getUser)
                .map(User::getId)
                .collect(Collectors.toSet());

        Map<Long, ManagerZpAggregate> zpAggregates =
                zpService.getUserAggregates(userIds, firstDayOfMonth, lastDayOfMonth);

        List<Long> operatorIds = operators.stream()
                .map(Operator::getId)
                .toList();

        Map<Long, Long> newLeadsMap = leadService.countNewLeadsByOperatorIdsToDate(operatorIds, localDate);
        Map<Long, Long> inWorkLeadsMap = leadService.countInWorkLeadsByOperatorIdsToDate(operatorIds, localDate);

        List<OperatorsListDTO> result = new ArrayList<>();

        for (Operator operator : operators) {
            User user = operator.getUser();

            ManagerZpAggregate zpAggregate = zpAggregates.getOrDefault(
                    user.getId(),
                    new ManagerZpAggregate(BigDecimal.ZERO, 0L, 0L)
            );

            Long newListLeads = newLeadsMap.getOrDefault(operator.getId(), 0L);
            Long inWorkListLeads = inWorkLeadsMap.getOrDefault(operator.getId(), 0L);
            Long percentInWork = safePercent(inWorkListLeads, newListLeads);

            result.add(OperatorsListDTO.builder()
                    .id(operator.getId())
                    .userId(user.getId())
                    .fio(user.getFio())
                    .login(user.getUsername())
                    .imageId(resolveImageId(user))
                    .sum1Month(zpAggregate.getTotalSum().intValue())
                    .order1Month((int) zpAggregate.getOrderCount())
                    .review1Month((int) zpAggregate.getReviewAmount())
                    .leadsNew(newListLeads)
                    .leadsInWork(inWorkListLeads)
                    .percentInWork(percentInWork)
                    .build());
        }

        return result;
    }

    private ManagersListDTO toManagersListDTO(Manager manager) {
        User user = manager.getUser();
        return ManagersListDTO.builder()
                .id(manager.getId())
                .userId(user.getId())
                .fio(user.getFio())
                .login(user.getUsername())
                .imageId(resolveImageId(user))
                .build();
    }

    private MarketologsListDTO toMarketologsListDTO(Marketolog marketolog) {
        User user = marketolog.getUser();
        return MarketologsListDTO.builder()
                .id(marketolog.getId())
                .userId(user.getId())
                .fio(user.getFio())
                .login(user.getUsername())
                .imageId(resolveImageId(user))
                .build();
    }

    private WorkersListDTO toWorkersListDTO(Worker worker) {
        User user = worker.getUser();
        return WorkersListDTO.builder()
                .id(worker.getId())
                .userId(user.getId())
                .fio(user.getFio())
                .login(user.getUsername())
                .imageId(resolveImageId(user))
                .build();
    }

    private OperatorsListDTO toOperatorsListDTO(Operator operator) {
        User user = operator.getUser();
        return OperatorsListDTO.builder()
                .id(operator.getId())
                .userId(user.getId())
                .fio(user.getFio())
                .login(user.getUsername())
                .imageId(resolveImageId(user))
                .build();
    }

    private Long safePercent(Long numerator, Long denominator) {
        if (denominator == null || denominator == 0L) {
            return 0L;
        }
        long safeNumerator = numerator == null ? 0L : numerator;
        return (safeNumerator * 100) / denominator;
    }

    private long resolveImageId(User user) {
        return user.getImage() != null ? user.getImage().getId() : DEFAULT_IMAGE_ID;
    }
}