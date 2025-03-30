package com.hunt.otziv.admin.services;

import com.hunt.otziv.admin.dto.personal_stat.StatDTO;
import com.hunt.otziv.admin.dto.personal_stat.UserLKDTO;
import com.hunt.otziv.admin.dto.personal_stat.UserStatDTO;
import com.hunt.otziv.admin.dto.presonal.*;
import com.hunt.otziv.u_users.model.*;

import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface PersonalService {
    List<WorkersListDTO> gerWorkers();
    List<WorkersListDTO> gerWorkersToAndCount();
    List<MarketologsListDTO> getMarketologs();
    List<MarketologsListDTO> getMarketologsAndCount();
    List<ManagersListDTO> getManagers();
    List<ManagersListDTO> getManagersAndCount();
    List<OperatorsListDTO> gerOperators();
    List<OperatorsListDTO> gerOperatorsAndCount();
//    StatDTO getStats();
    UserStatDTO getWorkerReviews(User user, LocalDate localDate);
    List<MarketologsListDTO> getMarketologsToManager(Manager manager);

    List<WorkersListDTO> gerWorkersToManager(Manager manager);

    List<OperatorsListDTO> gerOperatorsToManager(Manager manager);
    UserLKDTO getUserLK(Principal principal);
//    StatDTO getStats2(LocalDate localDate, Principal principal, String userRole);
//    UserStatDTO getWorkerReviews2(String login, LocalDate localDate);

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

    StatDTO getStats(LocalDate date, User user, String userRole);

    Object getManagersAndCountToDateToOwner(List<Manager> managerList, LocalDate date);

    Object getMarketologsAndCountToDateToOwner(List<Marketolog> allMarketologs, LocalDate date);

    Object gerWorkersToAndCountToDateToOwner(List<Worker> allWorkers, LocalDate date);

    Object gerOperatorsAndCountToDateToOwner(List<Operator> allOperators, LocalDate date);

    Map<String, UserData> getPersonalsAndCountToMap();


    String displayResult(Map<String, UserData> personalsAndCountToMap);


    List<UserData> getPersonalsAndCountToScore(LocalDate localDate);
}
