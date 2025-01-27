package com.hunt.otziv.z_zp.services;

import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.z_zp.dto.CheckDTO;
import com.hunt.otziv.z_zp.model.PaymentCheck;

import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

public interface PaymentCheckService {

    boolean save(Order order);
    List<CheckDTO> getAllCheckDTO();
    List<PaymentCheck> findAll();

    List<PaymentCheck> findAllToDate(LocalDate localDate);

    List<PaymentCheck> findAllToDateByOwner(LocalDate localDate, Set<Manager> managerList);

    List<PaymentCheck> getAllWorkerPaymentToDate(Long id, LocalDate firstDayOfMonth, LocalDate lastDayOfMonth);
}
