package com.hunt.otziv.z_zp.services;

import com.hunt.otziv.admin.model.Quadruple;
import com.hunt.otziv.l_lead.model.Lead;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.z_zp.dto.ManagerZpAggregate;
import com.hunt.otziv.z_zp.dto.ZpDTO;
import com.hunt.otziv.z_zp.model.Zp;
import org.springframework.data.util.Pair;

import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
public interface ZpService {

    List<Zp> getAllWorkerZp(String login);

    List<Zp> getAllWorkerZp(Long userId);

    List<Zp> getAllWorkerZpToDate(String login, LocalDate firstDayOfMonth, LocalDate lastDayOfMonth);

    List<Zp> getAllWorkerZpToDate(Long userId, LocalDate firstDayOfMonth, LocalDate lastDayOfMonth);

    List<Zp> findAll();

    List<Zp> findAllToDate(LocalDate localDate);

    List<Zp> findAllToDateByUser(LocalDate localDate, Long userId);

    Map<String, Pair<String, Long>> getAllZpToMonthToTelegram(LocalDate firstDayOfMonth, LocalDate lastDayOfMonth);

    Map<String, Quadruple<String, Long, Long, Long>> getAllZpToMonth(LocalDate firstDayOfMonth, LocalDate lastDayOfMonth);

    List<Zp> findAllToDateByOwner(LocalDate localDate, Set<Manager> managerList);

    Map<Long, ManagerZpAggregate> getManagerAggregates(Set<Long> userIds, LocalDate startDate, LocalDate endDate);

    Map<Long, ManagerZpAggregate> getUserAggregates(Set<Long> userIds, LocalDate startDate, LocalDate endDate);

    List<ZpDTO> getAllZpDTO();

    boolean save(Order order);

    boolean saveLeadZp(Lead lead);

    List<Zp> findAllToDateByUserIds(LocalDate localDate, Set<Long> userIds);

    List<Zp> findAllToDateByOwnerIds(LocalDate localDate, List<Long> managerIds);
}

