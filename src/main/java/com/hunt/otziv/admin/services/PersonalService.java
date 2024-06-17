package com.hunt.otziv.admin.services;

import com.hunt.otziv.admin.dto.personal_stat.StatDTO;
import com.hunt.otziv.admin.dto.personal_stat.UserLKDTO;
import com.hunt.otziv.admin.dto.personal_stat.UserStatDTO;
import com.hunt.otziv.admin.dto.presonal.ManagersListDTO;
import com.hunt.otziv.admin.dto.presonal.MarketologsListDTO;
import com.hunt.otziv.admin.dto.presonal.OperatorsListDTO;
import com.hunt.otziv.admin.dto.presonal.WorkersListDTO;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Marketolog;
import com.hunt.otziv.u_users.model.Operator;
import com.hunt.otziv.u_users.model.Worker;

import java.security.Principal;
import java.time.LocalDate;
import java.util.List;

public interface PersonalService {
    List<WorkersListDTO> gerWorkers();
    List<WorkersListDTO> gerWorkersToAndCount();
    List<MarketologsListDTO> getMarketologs();
    List<MarketologsListDTO> getMarketologsAndCount();
    List<ManagersListDTO> getManagers();
    List<ManagersListDTO> getManagersAndCount();
    List<OperatorsListDTO> gerOperators();
    List<OperatorsListDTO> gerOperatorsAndCount();
    StatDTO getStats();
    UserStatDTO getWorkerReviews(String login);
    List<MarketologsListDTO> getMarketologsToManager(Manager manager);

    List<WorkersListDTO> gerWorkersToManager(Manager manager);

    List<OperatorsListDTO> gerOperatorsToManager(Manager manager);
    UserLKDTO getUserLK(Principal principal);
    StatDTO getStats2(LocalDate localDate, Principal principal);
    UserStatDTO getWorkerReviews2(String login, LocalDate localDate);

    List<Manager> findAllManagersWorkers(List<Manager> managerList);

    List<ManagersListDTO> getManagersAndCountToDate(LocalDate localdate);
    List<MarketologsListDTO> getMarketologsAndCountToDate(LocalDate localdate);
    List<WorkersListDTO> gerWorkersToAndCountToDate(LocalDate localdate);
    List<OperatorsListDTO> gerOperatorsAndCountToDate(LocalDate localdate);

    List<ManagersListDTO> getManagersToOwner(List<Manager> managerList);
    List<WorkersListDTO>  getWorkersToOwner(List<Worker> allWorkers);
    List<MarketologsListDTO> getMarketologsToOwner(List<Marketolog> allMarketologs);
    List<OperatorsListDTO> gerOperatorsToOwner(List<Operator> allOperators);



    List<OperatorsListDTO> getOperatorsAndCountToOwner(List<Operator> allOperators);
    List<ManagersListDTO> getManagersAndCountToOwner(List<Manager> managerList);
    List<MarketologsListDTO> getMarketologsAndCountToOwner(List<Marketolog> allMarketologs);
    List<WorkersListDTO>  getWorkersToAndCountToOwner(List<Worker> allWorkers);
}
