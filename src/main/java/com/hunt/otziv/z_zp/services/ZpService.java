package com.hunt.otziv.z_zp.services;

import com.hunt.otziv.l_lead.model.Lead;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.z_zp.dto.ZpDTO;
import com.hunt.otziv.z_zp.model.Zp;

import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

public interface ZpService {

    boolean save(Order order);
    List<ZpDTO> getAllZpDTO();
    boolean saveLeadZp(Lead lead);
    List<Zp> getAllWorkerZp(String login);
    List<Zp> findAll();
    List<Zp> findAllToDate(LocalDate localDate);
    List<Zp> getAllWorkerZpToDate(String login, LocalDate firstDayOfMonth, LocalDate lastDayOfMonth);

    List<Zp> findAllToDateByOwner(LocalDate localDate, Set<Manager> managerList);

    List<Zp> findAllToDateByUser(LocalDate localDate, Long id);
}
