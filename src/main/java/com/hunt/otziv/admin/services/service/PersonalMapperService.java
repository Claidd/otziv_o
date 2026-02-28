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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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
        return marketologService.getAllMarketologs().stream()
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
        return managerService.getAllManagers().stream()
                .map(this::toManagersListDTOAndCount)
                .collect(Collectors.toList());
    }

    public List<MarketologsListDTO> getMarketologsAndCount() {
        return marketologService.getAllMarketologs().stream()
                .map(this::toMarketologsListDTOAndCount)
                .collect(Collectors.toList());
    }

    @Transactional
    public List<WorkersListDTO> gerWorkersToAndCount() {
        return workerService.getAllWorkers().stream()
                .map(this::toWorkersListDTOAndCount)
                .collect(Collectors.toList());
    }

    public List<OperatorsListDTO> gerOperatorsAndCount() {
        return operatorService.getAllOperators().stream()
                .map(this::toOperatorsListDTOAndCount)
                .collect(Collectors.toList());
    }

    public List<ManagersListDTO> getManagersAndCountToOwner(List<Manager> managers) {
        return managers.stream().map(this::toManagersListDTOAndCount).toList();
    }

    public List<MarketologsListDTO> getMarketologsAndCountToOwner(List<Marketolog> allMarketologs) {
        return allMarketologs.stream().map(this::toMarketologsListDTOAndCount).toList();
    }

    public List<WorkersListDTO> getWorkersToAndCountToOwner(List<Worker> allWorkers) {
        return allWorkers.stream().map(this::toWorkersListDTOAndCount).toList();
    }

    public List<OperatorsListDTO> getOperatorsAndCountToOwner(List<Operator> allOperators) {
        return allOperators.stream().map(this::toOperatorsListDTOAndCount).toList();
    }

    public List<ManagersListDTO> getManagersAndCountToDate(LocalDate localdate) {
        return managerService.getAllManagers().stream()
                .map(manager -> toManagersListDTOAndCountToDate(manager, localdate))
                .collect(Collectors.toList());
    }

    public List<MarketologsListDTO> getMarketologsAndCountToDate(LocalDate localdate) {
        return marketologService.getAllMarketologs().stream()
                .map(marketolog -> toMarketologsListDTOAndCountToDate(marketolog, localdate))
                .collect(Collectors.toList());
    }

    public List<WorkersListDTO> gerWorkersToAndCountToDate(LocalDate localdate) {
        return workerService.getAllWorkers().stream()
                .map(worker -> toWorkersListDTOAndCountToDate(worker, localdate))
                .collect(Collectors.toList());
    }

    public List<OperatorsListDTO> gerOperatorsAndCountToDate(LocalDate localdate) {
        return operatorService.getAllOperators().stream()
                .map(operator -> toOperatorsListDTOAndCountToDate(operator, localdate))
                .collect(Collectors.toList());
    }

    public List<ManagersListDTO> getManagersAndCountToDateToOwner(List<Manager> managerList, LocalDate localdate) {
        return managerList.stream()
                .map(manager -> toManagersListDTOAndCountToDate(manager, localdate))
                .collect(Collectors.toList());
    }

    public List<MarketologsListDTO> getMarketologsAndCountToDateToOwner(List<Marketolog> marketologList, LocalDate localdate) {
        return marketologList.stream()
                .map(marketolog -> toMarketologsListDTOAndCountToDate(marketolog, localdate))
                .collect(Collectors.toList());
    }

    public List<WorkersListDTO> gerWorkersToAndCountToDateToOwner(List<Worker> workerList, LocalDate localdate) {
        return workerList.stream()
                .map(worker -> toWorkersListDTOAndCountToDate(worker, localdate))
                .collect(Collectors.toList());
    }

    public List<OperatorsListDTO> gerOperatorsAndCountToDateToOwner(List<Operator> operatorList, LocalDate localdate) {
        return operatorList.stream()
                .map(operator -> toOperatorsListDTOAndCountToDate(operator, localdate))
                .collect(Collectors.toList());
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

    private ManagersListDTO toManagersListDTOAndCount(Manager manager) {
        LocalDate localDate = LocalDate.now();
        LocalDate firstDayOfMonth = localDate.withDayOfMonth(1);
        LocalDate lastDayOfMonth = localDate.withDayOfMonth(localDate.lengthOfMonth());

        List<Zp> zps = zpService.getAllWorkerZp(manager.getUser().getUsername());
        BigDecimal sum30 = sumZp(zps);

        List<PaymentCheck> pcs = paymentCheckService.getAllWorkerPaymentToDate(
                manager.getUser().getId(),
                firstDayOfMonth,
                lastDayOfMonth
        );
        BigDecimal sum30Payments = sumPaymentChecks(pcs);

        Set<Manager> managerList = new HashSet<>();
        managerList.add(manager);

        List<Long> inWorkLeadList = leadService.getAllLeadsByDateAndStatusToOwnerForTelegram(
                localDate,
                STATUS_IN_WORK,
                managerList
        );

        return ManagersListDTO.builder()
                .id(manager.getId())
                .userId(manager.getUser().getId())
                .fio(manager.getUser().getFio())
                .login(manager.getUser().getUsername())
                .imageId(resolveImageId(manager.getUser()))
                .sum1Month(sum30.intValue())
                .order1Month(zps.size())
                .review1Month(sumZpAmount(zps))
                .payment1Month(sum30Payments.intValue())
                .leadsInWorkInMonth(inWorkLeadList.size())
                .build();
    }

    private MarketologsListDTO toMarketologsListDTOAndCount(Marketolog marketolog) {
        List<Zp> zps = zpService.getAllWorkerZp(marketolog.getUser().getUsername());
        BigDecimal sum30 = sumZp(zps);

        Long newListLeads = leadService.findAllByLidListNew(marketolog);
        Long inWorkListLeads = leadService.findAllByLidListStatusInWork(marketolog);
        Long percentInWork = safePercent(inWorkListLeads, newListLeads);

        return MarketologsListDTO.builder()
                .id(marketolog.getId())
                .userId(marketolog.getUser().getId())
                .fio(marketolog.getUser().getFio())
                .login(marketolog.getUser().getUsername())
                .imageId(resolveImageId(marketolog.getUser()))
                .sum1Month(sum30.intValue())
                .order1Month(zps.size())
                .review1Month(sumZpAmount(zps))
                .leadsNew(newListLeads)
                .leadsInWork(inWorkListLeads)
                .percentInWork(percentInWork)
                .build();
    }

    private WorkersListDTO toWorkersListDTOAndCount(Worker worker) {
        LocalDate localDate = LocalDate.now();

        List<Zp> zps = zpService.getAllWorkerZp(worker.getUser().getUsername());
        BigDecimal sum30 = sumZp(zps);

        int newOrderInt = orderService.countOrdersByWorkerAndStatus(worker, STATUS_NEW);
        int inCorrectInt = orderService.countOrdersByWorkerAndStatus(worker, STATUS_CORRECTION);
        int inVigulInt = reviewService.countOrdersByWorkerAndStatusVigul(worker, localDate);
        int inPublishInt = reviewService.countOrdersByWorkerAndStatusPublish(worker, localDate);

        return WorkersListDTO.builder()
                .id(worker.getId())
                .userId(worker.getUser().getId())
                .fio(worker.getUser().getFio())
                .login(worker.getUser().getUsername())
                .imageId(resolveImageId(worker.getUser()))
                .sum1Month(sum30.intValue())
                .order1Month(zps.size())
                .review1Month(sumZpAmount(zps))
                .newOrder(newOrderInt)
                .inCorrect(inCorrectInt)
                .intVigul(inVigulInt)
                .publish(inPublishInt)
                .build();
    }

    private OperatorsListDTO toOperatorsListDTOAndCount(Operator operator) {
        List<Zp> zps = zpService.getAllWorkerZp(operator.getUser().getUsername());
        BigDecimal sum30 = sumZp(zps);

        Long newListLeads = leadService.findAllByLidListNew(operator);
        Long inWorkListLeads = leadService.findAllByLidListStatusInWork(operator);
        Long percentInWork = safePercent(inWorkListLeads, newListLeads);

        return OperatorsListDTO.builder()
                .id(operator.getId())
                .userId(operator.getUser().getId())
                .fio(operator.getUser().getFio())
                .login(operator.getUser().getUsername())
                .imageId(resolveImageId(operator.getUser()))
                .sum1Month(sum30.intValue())
                .order1Month(zps.size())
                .review1Month(sumZpAmount(zps))
                .leadsNew(newListLeads)
                .leadsInWork(inWorkListLeads)
                .percentInWork(percentInWork)
                .build();
    }

    private ManagersListDTO toManagersListDTOAndCountToDate(Manager manager, LocalDate localDate) {
        LocalDate firstDayOfMonth = localDate.withDayOfMonth(1);
        LocalDate lastDayOfMonth = localDate.withDayOfMonth(localDate.lengthOfMonth());

        List<Zp> zps = zpService.getAllWorkerZpToDate(manager.getUser().getUsername(), firstDayOfMonth, lastDayOfMonth);
        BigDecimal sum30 = sumZp(zps);

        List<PaymentCheck> pcs = paymentCheckService.getAllWorkerPaymentToDate(
                manager.getUser().getId(),
                firstDayOfMonth,
                localDate
        );
        BigDecimal sum30Payments = sumPaymentChecks(pcs);

        return ManagersListDTO.builder()
                .id(manager.getId())
                .userId(manager.getUser().getId())
                .fio(manager.getUser().getFio())
                .login(manager.getUser().getUsername())
                .imageId(resolveImageId(manager.getUser()))
                .sum1Month(sum30.intValue())
                .order1Month(zps.size())
                .review1Month(sumZpAmount(zps))
                .payment1Month(sum30Payments.intValue())
                .build();
    }

    private MarketologsListDTO toMarketologsListDTOAndCountToDate(Marketolog marketolog, LocalDate localDate) {
        LocalDate firstDayOfMonth = localDate.withDayOfMonth(1);
        LocalDate lastDayOfMonth = localDate.withDayOfMonth(localDate.lengthOfMonth());

        List<Zp> zps = zpService.getAllWorkerZpToDate(marketolog.getUser().getUsername(), firstDayOfMonth, lastDayOfMonth);
        BigDecimal sum30 = sumZp(zps);

        Long newListLeads = leadService.findAllByLidListNewToDate(marketolog, localDate);
        Long inWorkListLeads = leadService.findAllByLidListStatusInWorkToDate(marketolog, localDate);
        Long percentInWork = safePercent(inWorkListLeads, newListLeads);

        return MarketologsListDTO.builder()
                .id(marketolog.getId())
                .userId(marketolog.getUser().getId())
                .fio(marketolog.getUser().getFio())
                .login(marketolog.getUser().getUsername())
                .imageId(resolveImageId(marketolog.getUser()))
                .sum1Month(sum30.intValue())
                .order1Month(zps.size())
                .review1Month(sumZpAmount(zps))
                .leadsNew(newListLeads)
                .leadsInWork(inWorkListLeads)
                .percentInWork(percentInWork)
                .build();
    }

    private WorkersListDTO toWorkersListDTOAndCountToDate(Worker worker, LocalDate localDate) {
        LocalDate firstDayOfMonth = localDate.withDayOfMonth(1);
        LocalDate lastDayOfMonth = localDate.withDayOfMonth(localDate.lengthOfMonth());

        List<Zp> zps = zpService.getAllWorkerZpToDate(worker.getUser().getUsername(), firstDayOfMonth, lastDayOfMonth);
        BigDecimal sum30 = sumZp(zps);

        return WorkersListDTO.builder()
                .id(worker.getId())
                .userId(worker.getUser().getId())
                .fio(worker.getUser().getFio())
                .login(worker.getUser().getUsername())
                .imageId(resolveImageId(worker.getUser()))
                .sum1Month(sum30.intValue())
                .order1Month(zps.size())
                .review1Month(sumZpAmount(zps))
                .build();
    }

    private OperatorsListDTO toOperatorsListDTOAndCountToDate(Operator operator, LocalDate localDate) {
        LocalDate firstDayOfMonth = localDate.withDayOfMonth(1);
        LocalDate lastDayOfMonth = localDate.withDayOfMonth(localDate.lengthOfMonth());

        List<Zp> zps = zpService.getAllWorkerZpToDate(operator.getUser().getUsername(), firstDayOfMonth, lastDayOfMonth);
        BigDecimal sum30 = sumZp(zps);

        Long newListLeads = leadService.findAllByLidListNewToDate(operator, localDate);
        Long inWorkListLeads = leadService.findAllByLidListStatusInWorkToDate(operator, localDate);
        Long percentInWork = safePercent(inWorkListLeads, newListLeads);

        return OperatorsListDTO.builder()
                .id(operator.getId())
                .userId(operator.getUser().getId())
                .fio(operator.getUser().getFio())
                .login(operator.getUser().getUsername())
                .imageId(resolveImageId(operator.getUser()))
                .sum1Month(sum30.intValue())
                .order1Month(zps.size())
                .review1Month(sumZpAmount(zps))
                .leadsNew(newListLeads)
                .leadsInWork(inWorkListLeads)
                .percentInWork(percentInWork)
                .build();
    }

    private BigDecimal sumZp(List<Zp> zps) {
        return zps.stream()
                .map(Zp::getSum)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private int sumZpAmount(List<Zp> zps) {
        return zps.stream()
                .mapToInt(Zp::getAmount)
                .sum();
    }

    private BigDecimal sumPaymentChecks(List<PaymentCheck> checks) {
        return checks.stream()
                .map(PaymentCheck::getSum)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
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
