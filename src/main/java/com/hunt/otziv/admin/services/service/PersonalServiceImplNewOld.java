//package com.hunt.otziv.admin.services;
//
//import com.hunt.otziv.admin.dto.personal_stat.StatDTO;
//import com.hunt.otziv.admin.dto.personal_stat.UserLKDTO;
//import com.hunt.otziv.admin.dto.personal_stat.UserStatDTO;
//import com.hunt.otziv.admin.dto.presonal.*;
//import com.hunt.otziv.admin.services.service.PersonalMapperService;
//import com.hunt.otziv.admin.services.service.PersonalReportService;
//import com.hunt.otziv.admin.services.service.PersonalStatsService;
//import com.hunt.otziv.l_lead.services.serv.LeadService;
//import com.hunt.otziv.r_review.services.ReviewService;
//import com.hunt.otziv.u_users.model.*;
//import com.hunt.otziv.u_users.services.service.*;
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.security.core.Authentication;
//import org.springframework.security.core.context.SecurityContextHolder;
//import org.springframework.security.core.userdetails.UserDetails;
//import org.springframework.stereotype.Service;
//import org.springframework.transaction.annotation.Transactional;
//
//import java.security.Principal;
//import java.time.LocalDate;
//import java.util.*;
//
//import org.springframework.security.core.GrantedAuthority;
//
//import java.util.List;
//import java.util.Map;
//
//@Service
//@Slf4j
//@RequiredArgsConstructor
//public class PersonalServiceImpl implements PersonalService {
//
//    private final UserService userService;
//    private final LeadService leadService;
//    private final ReviewService reviewService;
//
//    private final PersonalStatsService personalStatsService;
//    private final PersonalReportService personalReportService;
//    private final PersonalMapperService personalMapperService;
//
//    private static final long DEFAULT_IMAGE_ID = 1L;
//
//    @Override
//    public UserLKDTO getUserLK(User user) {
//        UserLKDTO dto = new UserLKDTO();
//
//        String shortRole = extractShortRole(user);
//        Long userId = user.getId();
//
//        dto.setUsername(user.getUsername());
//        dto.setRole(shortRole);
//        dto.setImage(resolveImageId(user));
//
//        if ("MANAGER".equals(shortRole)) {
//            dto.setLeadCount((int) leadService.countNewLeadsForManagerUserId(userId));
//        } else {
//            dto.setLeadCount(0);
//        }
//
//        if ("WORKER".equals(shortRole)) {
//            dto.setReviewCount(reviewService.countReviewsForWorkerUserId(userId));
//        } else {
//            dto.setReviewCount(0);
//        }
//
//        return dto;
//    }
//
//    @Transactional
//    public StatDTO getStats(LocalDate localDate, User user, String role) {
//        return personalStatsService.getStats(localDate, user, role);
//    }
//
//    public UserStatDTO getWorkerReviews(User user, LocalDate localDate) {
//        return personalStatsService.getWorkerReviews(user, localDate);
//    }
//
//    public List<ManagersListDTO> getManagers() {
//        return personalMapperService.getManagers();
//    }
//
//    public List<MarketologsListDTO> getMarketologs() {
//        return personalMapperService.getMarketologs();
//    }
//
//    public List<WorkersListDTO> gerWorkers() {
//        return personalMapperService.gerWorkers();
//    }
//
//    public List<OperatorsListDTO> gerOperators() {
//        return personalMapperService.gerOperators();
//    }
//
//    public List<ManagersListDTO> getManagersToManager(Principal principal) {
//        return personalMapperService.getManagersToManager(principal);
//    }
//
//    public List<MarketologsListDTO> getMarketologsToManager(Manager manager) {
//        return personalMapperService.getMarketologsToManager(manager);
//    }
//
//    public List<WorkersListDTO> gerWorkersToManager(Manager manager) {
//        return personalMapperService.gerWorkersToManager(manager);
//    }
//
//    public List<OperatorsListDTO> gerOperatorsToManager(Manager manager) {
//        return personalMapperService.gerOperatorsToManager(manager);
//    }
//
//    @Override
//    public List<Manager> findAllManagersWorkers(List<Manager> managerList) {
//        return personalMapperService.findAllManagersWorkers(managerList);
//    }
//
//    public List<ManagersListDTO> getManagersToOwner(List<Manager> managers) {
//        return personalMapperService.getManagersToOwner(managers);
//    }
//
//    public List<MarketologsListDTO> getMarketologsToOwner(List<Marketolog> allMarketologs) {
//        return personalMapperService.getMarketologsToOwner(allMarketologs);
//    }
//
//    @Override
//    public List<OperatorsListDTO> gerOperatorsToOwner(List<Operator> allOperators) {
//        return personalMapperService.gerOperatorsToOwner(allOperators);
//    }
//
//    public List<WorkersListDTO> getWorkersToOwner(List<Worker> allWorkers) {
//        return personalMapperService.getWorkersToOwner(allWorkers);
//    }
//
//    public List<OperatorsListDTO> gerOperatorsToOwner(Manager manager) {
//        return personalMapperService.gerOperatorsToOwner(manager);
//    }
//
//    @Transactional
//    public List<ManagersListDTO> getManagersAndCount() {
//        return personalMapperService.getManagersAndCount();
//    }
//
//    public List<MarketologsListDTO> getMarketologsAndCount() {
//        return personalMapperService.getMarketologsAndCount();
//    }
//
//    @Transactional
//    public List<WorkersListDTO> gerWorkersToAndCount() {
//        return personalMapperService.gerWorkersToAndCount();
//    }
//
//    public List<OperatorsListDTO> gerOperatorsAndCount() {
//        return personalMapperService.gerOperatorsAndCount();
//    }
//
//    public Map<String, UserData> getPersonalsAndCountToMap() {
//        return personalReportService.getPersonalsAndCountToMap();
//    }
//
//    public String displayResult(Map<String, UserData> result) {
//        return personalReportService.displayResult(result);
//    }
//
//    public String displayResultToTelegramAdmin(Map<String, UserData> result) {
//        return personalReportService.displayResultToTelegramAdmin(result);
//    }
//
//    public List<UserData> getPersonalsAndCountToScore(LocalDate localDate) {
//        return personalReportService.getPersonalsAndCountToScore(localDate);
//    }
//
//    @Transactional
//    public Map<String, UserData> getPersonalsAndCountToMapToOwner(Long userId) {
//        return personalReportService.getPersonalsAndCountToMapToOwner(userId);
//    }
//
//    @Transactional
//    public Map<String, UserData> getPersonalsAndCountToMapToManager(Long userId) {
//        return personalReportService.getPersonalsAndCountToMapToManager(userId);
//    }
//
//    public String displayResultToManager(Map<String, UserData> result) {
//        return personalReportService.displayResultToManager(result);
//    }
//
//    @Transactional
//    public Map<String, UserData> getPersonalsAndCountToMapToWorker(Long userId) {
//        return personalReportService.getPersonalsAndCountToMapToWorker(userId);
//    }
//
//    public String displayResultToWorker(Map<String, UserData> result) {
//        return personalReportService.displayResultToWorker(result);
//    }
//
//    public List<ManagersListDTO> getManagersAndCountToOwner(List<Manager> managers) {
//        return personalMapperService.getManagersAndCountToOwner(managers);
//    }
//
//    public List<MarketologsListDTO> getMarketologsAndCountToOwner(List<Marketolog> allMarketologs) {
//        return personalMapperService.getMarketologsAndCountToOwner(allMarketologs);
//    }
//
//    public List<WorkersListDTO> getWorkersToAndCountToOwner(List<Worker> allWorkers) {
//        return personalMapperService.getWorkersToAndCountToOwner(allWorkers);
//    }
//
//    public List<OperatorsListDTO> getOperatorsAndCountToOwner(List<Operator> allOperators) {
//        return personalMapperService.getOperatorsAndCountToOwner(allOperators);
//    }
//
//    public List<ManagersListDTO> getManagersAndCountToDate(LocalDate localdate) {
//        return personalMapperService.getManagersAndCountToDate(localdate);
//    }
//
//    public List<MarketologsListDTO> getMarketologsAndCountToDate(LocalDate localdate) {
//        return personalMapperService.getMarketologsAndCountToDate(localdate);
//    }
//
//    public List<WorkersListDTO> gerWorkersToAndCountToDate(LocalDate localdate) {
//        return personalMapperService.gerWorkersToAndCountToDate(localdate);
//    }
//
//    public List<OperatorsListDTO> gerOperatorsAndCountToDate(LocalDate localdate) {
//        return personalMapperService.gerOperatorsAndCountToDate(localdate);
//    }
//
//    public List<ManagersListDTO> getManagersAndCountToDateToOwner(List<Manager> managerList, LocalDate localdate) {
//        return personalMapperService.getManagersAndCountToDateToOwner(managerList, localdate);
//    }
//
//    public List<MarketologsListDTO> getMarketologsAndCountToDateToOwner(List<Marketolog> marketologList, LocalDate localdate) {
//        return personalMapperService.getMarketologsAndCountToDateToOwner(marketologList, localdate);
//    }
//
//    public List<WorkersListDTO> gerWorkersToAndCountToDateToOwner(List<Worker> workerList, LocalDate localdate) {
//        return personalMapperService.gerWorkersToAndCountToDateToOwner(workerList, localdate);
//    }
//
//    public List<OperatorsListDTO> gerOperatorsAndCountToDateToOwner(List<Operator> operatorList, LocalDate localdate) {
//        return personalMapperService.gerOperatorsAndCountToDateToOwner(operatorList, localdate);
//    }
//
//    private String getRole(Principal principal) {
//        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
//        Object authPrincipal = authentication.getPrincipal();
//
//        if (authPrincipal instanceof UserDetails userDetails) {
//            return userDetails.getAuthorities().stream()
//                    .map(GrantedAuthority::getAuthority)
//                    .findFirst()
//                    .orElse("");
//        }
//
//        return authentication.getAuthorities().stream()
//                .map(GrantedAuthority::getAuthority)
//                .findFirst()
//                .orElse("");
//    }
//
//    private String extractShortRole(User user) {
//        String fullRole = user.getRoles().iterator().next().getName();
//        if (fullRole.startsWith("ROLE_")) {
//            return fullRole.substring("ROLE_".length());
//        }
//        return fullRole;
//    }
//
//    private long resolveImageId(User user) {
//        return user.getImage() != null ? user.getImage().getId() : DEFAULT_IMAGE_ID;
//    }
//}
//

