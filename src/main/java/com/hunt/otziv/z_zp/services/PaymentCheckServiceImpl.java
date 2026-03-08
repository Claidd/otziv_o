package com.hunt.otziv.z_zp.services;

import com.hunt.otziv.c_companies.services.CompanyService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.services.service.ManagerService;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.z_zp.dto.CheckDTO;
import com.hunt.otziv.z_zp.model.PaymentCheck;
import com.hunt.otziv.z_zp.repository.PaymentCheckRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.util.Pair;
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
public class PaymentCheckServiceImpl implements PaymentCheckService {

    private final PaymentCheckRepository paymentCheckRepository;
    private final CompanyService companyService;
    private final UserService userService;

    @Override
    public List<PaymentCheck> findAll() {
        return paymentCheckRepository.findAll();
    }

    @Override
    public List<PaymentCheck> findAllToDate(LocalDate localDate) {
        LocalDate localDate2 = localDate.minusYears(1);
        return paymentCheckRepository.findAllToDate(localDate, localDate2);
    }

    @Override
    public List<PaymentCheck> findAllToDateByOwner(LocalDate localDate, Set<Manager> managerList) {
        LocalDate localDate2 = localDate.minusYears(1);
        List<Long> managerListLong = managerList.stream()
                .map(Manager::getUser)
                .map(User::getId)
                .toList();

        return paymentCheckRepository.findAllToDateByManagers(localDate, localDate2, managerListLong);
    }

    @Override
    public List<PaymentCheck> getAllWorkerPaymentToDate(Long managerId, LocalDate firstDayOfMonth, LocalDate lastDayOfMonth) {
        return paymentCheckRepository.getAllWorkerPayments(managerId, firstDayOfMonth, lastDayOfMonth);
    }

    @Override
    public Map<Long, BigDecimal> getActiveManagerPaymentSums(Set<Long> managerIds, LocalDate startDate, LocalDate endDate) {
        if (managerIds == null || managerIds.isEmpty()) {
            return Map.of();
        }

        return paymentCheckRepository.aggregateActiveManagerPayments(managerIds, startDate, endDate)
                .stream()
                .collect(Collectors.toMap(
                        row -> (Long) row[0],
                        row -> (BigDecimal) row[1]
                ));
    }

    @Override
    public Map<Long, BigDecimal> getActiveWorkerPaymentSums(Set<Long> workerIds, LocalDate startDate, LocalDate endDate) {
        if (workerIds == null || workerIds.isEmpty()) {
            return Map.of();
        }

        return paymentCheckRepository.aggregateActiveWorkerPayments(workerIds, startDate, endDate)
                .stream()
                .collect(Collectors.toMap(
                        row -> (Long) row[0],
                        row -> (BigDecimal) row[1]
                ));
    }

    @Override
    public Map<Long, BigDecimal> getManagerPaymentSums(Set<Long> managerIds, LocalDate startDate, LocalDate endDate) {
        // backward compatibility
        return getActiveManagerPaymentSums(managerIds, startDate, endDate);
    }

    @Override
    public Map<String, Pair<Long, Long>> getAllPaymentToMonth(LocalDate firstDayOfMonth, LocalDate lastDayOfMonth) {
        Map<String, Long> check = paymentCheckRepository.findAllToDateToMap(firstDayOfMonth, lastDayOfMonth)
                .stream()
                .collect(Collectors.toMap(
                        obj -> (String) obj[0],
                        obj -> ((BigDecimal) obj[1]).longValue(),
                        Long::sum,
                        LinkedHashMap::new
                ));

        Map<String, Pair<Long, Long>> result = new LinkedHashMap<>();

        for (Map.Entry<String, Long> entry : check.entrySet()) {
            result.put(entry.getKey(), Pair.of(entry.getValue(), 0L));
        }

        return result;
    }

//    @Override
//    public Map<String, Pair<Long, Long>> getAllPaymentToMonth(LocalDate firstDayOfMonth, LocalDate lastDayOfMonth) {
//        Map<String, Long> check = paymentCheckRepository.findAllToDateToMap(firstDayOfMonth, lastDayOfMonth)
//                .stream()
//                .collect(Collectors.toMap(
//                        obj -> (String) obj[0],
//                        obj -> ((BigDecimal) obj[1]).longValue(),
//                        Long::sum,
//                        LinkedHashMap::new
//                ));
//
//        Map<String, Long> newCompanies = companyService.getAllNewCompanies(firstDayOfMonth, lastDayOfMonth);
//
//        Map<String, Pair<Long, Long>> result = new LinkedHashMap<>();
//
//        for (Map.Entry<String, Long> entry : check.entrySet()) {
//            String fio = entry.getKey();
//            Long totalSum = entry.getValue();
//            Long newCompaniesCount = newCompanies.getOrDefault(fio, 0L);
//            result.put(fio, Pair.of(totalSum, newCompaniesCount));
//        }
//
//        for (Map.Entry<String, Long> entry : newCompanies.entrySet()) {
//            String fio = entry.getKey();
//            result.putIfAbsent(fio, Pair.of(0L, entry.getValue()));
//        }
//
//        return result;
//    }

    @Override
    public List<CheckDTO> getAllCheckDTO() {
        return toDTOList(paymentCheckRepository.findAll());
    }

    @Override
    @Transactional
    public boolean save(Order order) {
        try {
            saveCheckCompany(order);
            return true;
        } catch (Exception e) {
            log.error("Ошибка при сохранении PaymentCheck", e);
            return false;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<PaymentCheck> findAllToDateByUserIds(LocalDate localDate, Set<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyList();
        }

        LocalDate firstDayOfMonth = localDate.withDayOfMonth(1);
        LocalDate lastDayOfMonth = localDate.withDayOfMonth(localDate.lengthOfMonth());

        return paymentCheckRepository.findAllToDateByUserIds(firstDayOfMonth, lastDayOfMonth, userIds);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PaymentCheck> findAllToDateByOwnerIds(LocalDate localDate, List<Long> managerIds) {
        if (localDate == null || managerIds == null || managerIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> relevantUserIds = userService.findAllRelevantUserIdsForManagerIds(managerIds);
        if (relevantUserIds == null || relevantUserIds.isEmpty()) {
            return Collections.emptyList();
        }

        LocalDate firstDayOfMonth = localDate.withDayOfMonth(1);
        LocalDate lastDayOfMonth = localDate.withDayOfMonth(localDate.lengthOfMonth());

        return paymentCheckRepository.findAllToDateByRelevantUserIds(
                firstDayOfMonth,
                lastDayOfMonth,
                relevantUserIds
        );
    }

    @Transactional
    protected void saveCheckCompany(Order order) {
        log.info("Зашли в создание чека");

        PaymentCheck paymentCheck = new PaymentCheck();
        paymentCheck.setTitle(order.getCompany().getTitle());
        paymentCheck.setCompanyId(order.getCompany().getId());
        paymentCheck.setSum(order.getSum());
        paymentCheck.setOrderId(order.getId());
        paymentCheck.setManagerId(order.getManager().getUser().getId());

        // ВАЖНО: исправление бага — здесь должен быть worker, а не manager
        paymentCheck.setWorkerId(order.getWorker().getUser().getId());

        paymentCheck.setActive(true);

        paymentCheckRepository.save(paymentCheck);
        log.info("Чек сохранен");
    }

    private List<CheckDTO> toDTOList(List<PaymentCheck> paymentCheckList) {
        return paymentCheckList.stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    private CheckDTO toDTO(PaymentCheck paymentCheck) {
        CheckDTO checkDTO = new CheckDTO();
        checkDTO.setId(paymentCheck.getId());
        checkDTO.setTitle(paymentCheck.getTitle());
        checkDTO.setCompanyId(paymentCheck.getCompanyId());
        checkDTO.setOrderId(paymentCheck.getOrderId());
        checkDTO.setManagerId(paymentCheck.getManagerId());
        checkDTO.setWorkerId(paymentCheck.getWorkerId());
        checkDTO.setCreated(paymentCheck.getCreated());
        checkDTO.setActive(paymentCheck.isActive());
        checkDTO.setSum(paymentCheck.getSum());
        return checkDTO;
    }

    private PaymentCheck toEntity(CheckDTO checkDTO) {
        PaymentCheck paymentCheck = new PaymentCheck();
        paymentCheck.setTitle(checkDTO.getTitle());
        paymentCheck.setCompanyId(checkDTO.getCompanyId());
        paymentCheck.setOrderId(checkDTO.getOrderId());
        paymentCheck.setManagerId(checkDTO.getManagerId());
        paymentCheck.setWorkerId(checkDTO.getWorkerId());
        paymentCheck.setCreated(checkDTO.getCreated());
        paymentCheck.setActive(checkDTO.isActive());
        paymentCheck.setSum(checkDTO.getSum());
        return paymentCheck;
    }
}
