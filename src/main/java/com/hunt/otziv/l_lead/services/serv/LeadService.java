package com.hunt.otziv.l_lead.services.serv;

import com.hunt.otziv.l_lead.dto.LeadDtoTransfer;
import com.hunt.otziv.l_lead.dto.LeadMonthStats;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Marketolog;
import com.hunt.otziv.u_users.model.Operator;
import com.hunt.otziv.l_lead.dto.LeadDTO;
import com.hunt.otziv.l_lead.model.Lead;
import org.springframework.data.domain.Page;
import org.springframework.data.util.Pair;

import java.security.Principal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;


public interface LeadService {

    Lead save(LeadDTO leadDTO, String username);

    void updateProfile(LeadDTO leadDTO, Long id);

    void markOfferSentAndPublish(Long leadId);


    Map<Long, Long> getManagerLeadsInWorkCount(Set<Manager> managerList, LocalDate firstDayOfMonth, LocalDate lastDayOfMonth);

    Page<LeadDTO> getAllLeads(String status, String keywords, Principal principal, int pageNumber, int pageSize);

    Page<LeadDTO> getAllLeadsToWork(String status, String keywords, Principal principal, int pageNumber, int pageSize);

    Page<LeadDTO> getAllLeadsToOperator(Long telephoneId, String status, String keywords, Principal principal, int pageNumber, int pageSize);


    Page<LeadDTO> getAllLeadsToDateReSend(String status, String keywords, Principal principal, int pageNumber, int pageSize);

    Page<LeadDTO> getAllLeadsNoStatus(String keywords, Principal principal, int pageNumber, int pageSize);

    void changeStatusLeadOnSendAndTelephone(Long leadId);

    void changeStatusLeadOnSend(Long leadId);

    void changeStatusLeadToWork(Long leadId, String newComment);

    void changeCountToOperator(Long leadId);

    void changeStatusLeadOnReSend(Long leadId);

    void changeStatusLeadOnArchive(Long leadId);

    void changeStatusLeadOnInWork(Long leadId);

    void changeStatusLeadOnNew(Long leadId);


    LeadDTO findById(Long leadId);

    Optional<Lead> findByIdOptional(Long leadId);

    Optional<Lead> findByIdAndToUpdate(Long id);

    Optional<Lead> getByTelephoneLead(String telephoneNumber);

    void saveLead(Lead lead);

    int countNewLeadsByClient(Long telephoneId, String status);

    LeadDtoTransfer findByIdToTransfer(Long leadId);

    void saveOrUpdateByTelephoneLead(Lead incomingLead);

    Map<Long, Long> countNewLeadsByOperatorIdsToDate(List<Long> operatorIds, LocalDate localDate);

    Map<Long, Long> countInWorkLeadsByOperatorIdsToDate(List<Long> operatorIds, LocalDate localDate);

    Map<Long, Long> countNewLeadsByMarketologIdsToDate(List<Long> marketologIds, LocalDate localDate);

    Map<Long, Long> countInWorkLeadsByMarketologIdsToDate(List<Long> marketologIds, LocalDate localDate);

    Map<String, Pair<Long, Long>> getAllLeadsToMonth(String statusInWork, LocalDate firstDayOfMonth, LocalDate lastDayOfMonth);

    Map<String, Long> getAllLeadsToMonthToManager(String status, LocalDate firstDayOfMonth, LocalDate lastDayOfMonth);

    List<Lead> findModifiedSince(LocalDateTime since);

    List<Long> getAllLeadsByDate(LocalDate localDate);

    List<Long> getAllLeadsByDateToOwner(LocalDate localDate, Set<Manager> managerList);

    List<Long> getAllLeadsByDateAndStatus(LocalDate localDate, String status);

    List<Long> getAllLeadsByDateAndStatusToOwner(LocalDate localDate, String status, Set<Manager> managerList);

    long countNewLeadsForManagerUserId(Long userId);

    LeadMonthStats getLeadMonthStatsForManagers(Set<Manager> managerList, String inWorkStatus, LocalDate currentDate);

    LeadMonthStats getLeadMonthStatsForManagerIds(List<Long> managerIds, String statusInWork, LocalDate localDate);
}