package com.hunt.otziv.z_zp.services;

import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.z_zp.dto.CheckDTO;
import com.hunt.otziv.z_zp.model.PaymentCheck;
import org.springframework.data.util.Pair;

import java.math.BigDecimal;
import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface PaymentCheckService {

    List<PaymentCheck> findAll();

    List<PaymentCheck> findAllToDate(LocalDate localDate);

    List<PaymentCheck> findAllToDateByOwner(LocalDate localDate, Set<Manager> managerList);

    List<PaymentCheck> getAllWorkerPaymentToDate(Long managerId, LocalDate firstDayOfMonth, LocalDate lastDayOfMonth);

    /**
     * Новый batch-метод для менеджеров.
     */
    Map<Long, BigDecimal> getActiveManagerPaymentSums(Set<Long> managerIds, LocalDate startDate, LocalDate endDate);

    /**
     * На будущее: batch-метод для работников.
     */
    Map<Long, BigDecimal> getActiveWorkerPaymentSums(Set<Long> workerIds, LocalDate startDate, LocalDate endDate);

    /**
     * Оставлен для обратной совместимости.
     */
    Map<Long, BigDecimal> getManagerPaymentSums(Set<Long> managerIds, LocalDate startDate, LocalDate endDate);

    Map<String, Pair<Long, Long>> getAllPaymentToMonth(LocalDate firstDayOfMonth, LocalDate lastDayOfMonth);

    List<CheckDTO> getAllCheckDTO();

    boolean save(Order order);

    List<PaymentCheck> findAllToDateByUserIds(LocalDate localDate, Set<Long> userIds);

    List<PaymentCheck> findAllToDateByOwnerIds(LocalDate localDate, List<Long> managerIds);


}
