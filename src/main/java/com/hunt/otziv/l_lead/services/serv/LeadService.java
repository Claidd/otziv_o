package com.hunt.otziv.l_lead.services.serv;

import com.hunt.otziv.l_lead.dto.LeadDtoTransfer;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Marketolog;
import com.hunt.otziv.u_users.model.Operator;
import com.hunt.otziv.u_users.model.User;
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
    //    =============================== СОЗДАНИЕ И ОБНОВЛЕНИЕ =========================================
    // сохранение нового лида
    Lead save(LeadDTO leadDTO, String id);
    // метод обнолвдения данных лида
    void updateProfile(LeadDTO leadDTO, Long id);
    // метод поиска лидав по id
    Optional<Lead> findByIdAndToUpdate(Long id);
    LeadDTO findById(Long id);
    Optional<Lead> findByIdOptional(Long leadId);
    Optional<User> findByFio(String operator);
    List<Long> getAllLeadsByDate(LocalDate localDate);
    List<Long> getAllLeadsByDateAndStatus(LocalDate localDate, String status);
    void changeStatusLeadOnSendAndTelephone(Long leadId);

    //    =============================== ВЫВОД ЛИДОВ ПО СПИСКАМ И СТАТУСАМ =========================================
    // метод вывода всех лидов
    Page<LeadDTO> getAllLeads(String status, String keyword, Principal principal, int pageNumber, int pageSize);
    // метод вывода всех новых лидов по телефону
    Page<LeadDTO> getAllLeadsToOperator(Long telephoneId, String status, String keyword, Principal principal, int pageNumber, int pageSize);
    // метод смены статуса и выборки по дате. Проверка равна ли дата или больше
    Page<LeadDTO> getAllLeadsToDateReSend(String status, String keywords, Principal principal, int pageNumber, int pageSize);
    // метод поиска одного лида по id и перевод его в дто
    Page<LeadDTO> getAllLeadsNoStatus(String keywords, Principal principal, int pageNumber, int pageSize);


    //    =============================== СМЕНА СТАТУСОВ - НАЧАЛО =========================================
    // меняем статус с нового на отправленное
    void changeStatusLeadOnSend(Long leadId);
    // меняем статус с нового на напоминание
    void changeStatusLeadOnReSend(Long leadId);
    // меняем статус с напоминание на К рассылке
    void changeStatusLeadOnArchive(Long leadId);
    // меняем статус с К рассылке на В работе
    void changeStatusLeadOnInWork (Long leadId);
    // меняем статус с любого на Новый
    void changeStatusLeadOnNew (Long leadId);
    Long findAllByLidListNew(Marketolog marketolog);
    Long findAllByLidListNew(Operator operator);

    List<Lead> findAllByLidListStatus(String name);
    Long findAllByLidListStatusInWork(Marketolog marketolog);

    Long findAllByLidListStatusInWork(Operator operator);
    Long findAllByLidListStatusInWorkToDate(Marketolog marketolog, LocalDate localDate);
    Long findAllByLidListStatusInWorkToDate(Operator operator, LocalDate localDate);
    Long findAllByLidListNewToDate(Marketolog marketolog, LocalDate localDate);
    Long findAllByLidListNewToDate(Operator operator, LocalDate localDate);

    List<Long> getAllLeadsByDateAndStatusToOwner(LocalDate localDate, String status, Set<Manager> managerList);

    List<Long> getAllLeadsByDateToOwner(LocalDate localDate, Set<Manager> managerList);

    List<Long> getAllLeadsByDateAndStatusToOwnerForTelegram(LocalDate localDate, String status, Set<Manager> managerList);

    Map<String, Pair<Long, Long>> getAllLeadsToMonth(String statusInWork, LocalDate firstDayOfMonth, LocalDate lastDayOfMonth);

    Map<String, Long> getAllLeadsToMonthToManager(String status, LocalDate firstDayOfMonth, LocalDate lastDayOfMonth);

    void changeCountToOperator(Long leadId);

    Optional<Lead> getByTelephoneLead(String telephoneNumber);

    void saveLead(Lead lead);

    int countNewLeadsByClient(Long telephoneId, String status);

    LeadDtoTransfer findByIdToTransfer(Long leadId);

    List<Lead> findModifiedSince(LocalDateTime since);

    void saveOrUpdateByTelephoneLead(Lead incomingLead);

    void changeStatusLeadToWork(Long leadId, String commentsLead);

    Page<LeadDTO> getAllLeadsToWork(String title, String keyword, Principal principal, int pageNumber, int pageSize);

    Page<LeadDTO> getAllLeadsToOperatorAll(Long operatorID, String keyword, Principal principal, int pageNumber, int i);


    //    =============================== СМЕНА СТАТУСОВ - КОНЕЦ =========================================
}
