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

public interface PersonalService {

    UserLKDTO getUserLK(Principal principal);

    StatDTO getStats(LocalDate localDate, User user, String role);

    UserStatDTO getWorkerReviews(User user, LocalDate localDate);

    List<ManagersListDTO> getManagers();

    List<MarketologsListDTO> getMarketologs();

    List<WorkersListDTO> gerWorkers();

    List<OperatorsListDTO> gerOperators();

    List<ManagersListDTO> getManagersToManager(Principal principal);

    List<MarketologsListDTO> getMarketologsToManager(Manager manager);

    List<WorkersListDTO> gerWorkersToManager(Manager manager);

    List<OperatorsListDTO> gerOperatorsToManager(Manager manager);

    List<Manager> findAllManagersWorkers(List<Manager> managerList);

    List<ManagersListDTO> getManagersToOwner(List<Manager> managers);

    List<MarketologsListDTO> getMarketologsToOwner(List<Marketolog> allMarketologs);

    List<OperatorsListDTO> gerOperatorsToOwner(List<Operator> allOperators);

    List<WorkersListDTO> getWorkersToOwner(List<Worker> allWorkers);

    List<OperatorsListDTO> gerOperatorsToOwner(Manager manager);

    List<ManagersListDTO> getManagersAndCount();

    List<MarketologsListDTO> getMarketologsAndCount();

    List<WorkersListDTO> gerWorkersToAndCount();

    List<OperatorsListDTO> gerOperatorsAndCount();

    Map<String, UserData> getPersonalsAndCountToMap();

    String displayResult(Map<String, UserData> result);

    String displayResultToTelegramAdmin(Map<String, UserData> result);

    List<UserData> getPersonalsAndCountToScore(LocalDate localDate);

    Map<String, UserData> getPersonalsAndCountToMapToOwner(Long userId);

    Map<String, UserData> getPersonalsAndCountToMapToManager(Long userId);

    String displayResultToManager(Map<String, UserData> result);

    Map<String, UserData> getPersonalsAndCountToMapToWorker(Long userId);

    String displayResultToWorker(Map<String, UserData> result);

    List<ManagersListDTO> getManagersAndCountToOwner(List<Manager> managers);

    List<MarketologsListDTO> getMarketologsAndCountToOwner(List<Marketolog> allMarketologs);

    List<WorkersListDTO> getWorkersToAndCountToOwner(List<Worker> allWorkers);

    List<OperatorsListDTO> getOperatorsAndCountToOwner(List<Operator> allOperators);

    List<ManagersListDTO> getManagersAndCountToDate(LocalDate localdate);

    List<MarketologsListDTO> getMarketologsAndCountToDate(LocalDate localdate);

    List<WorkersListDTO> gerWorkersToAndCountToDate(LocalDate localdate);

    List<OperatorsListDTO> gerOperatorsAndCountToDate(LocalDate localdate);

    List<ManagersListDTO> getManagersAndCountToDateToOwner(List<Manager> managerList, LocalDate localdate);

    List<MarketologsListDTO> getMarketologsAndCountToDateToOwner(List<Marketolog> marketologList, LocalDate localdate);

    List<WorkersListDTO> gerWorkersToAndCountToDateToOwner(List<Worker> workerList, LocalDate localdate);

    List<OperatorsListDTO> gerOperatorsAndCountToDateToOwner(List<Operator> operatorList, LocalDate localdate);
}