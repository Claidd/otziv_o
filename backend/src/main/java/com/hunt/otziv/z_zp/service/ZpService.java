package com.hunt.otziv.z_zp.service;

import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.z_zp.dto.ZpDTO;
import com.hunt.otziv.z_zp.dto.ZpStatView;
import com.hunt.otziv.z_zp.model.Zp;

import java.math.BigDecimal;
import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

public interface ZpService {

    boolean save(Order order);
    boolean save(Order order, BigDecimal sum, int amount);
    List<ZpDTO> getAllZpDTO();
    List<Zp> findAll();
    List<Zp> findAllToDate(LocalDate localDate);
    List<ZpStatView> findStatRowsToDate(LocalDate localDate);

    List<Zp> findAllToDateByOwner(LocalDate localDate, Set<Manager> managerList);
    List<ZpStatView> findStatRowsToDateByOwner(LocalDate localDate, Set<Manager> managerList);

    List<Zp> findAllByOwner(Set<Manager> managerList);

    List<Zp> findAllToDateByUser(LocalDate localDate, Long id);

    BigDecimal sumByUserAndCreated(Long userId, LocalDate created);

    long countByUserAndCreated(Long userId, LocalDate created);
}
