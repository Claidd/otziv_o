package com.hunt.otziv.l_lead.services;

import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.l_lead.dto.LeadDTO;
import com.hunt.otziv.l_lead.model.Lead;
import org.springframework.data.domain.Page;

import java.security.Principal;
import java.util.List;
import java.util.Optional;

public interface LeadService {
    //    =============================== СОЗДАНИЕ И ОБНОВЛЕНИЕ =========================================
    // перевод лида в дто
    LeadDTO convertFromLead(Lead lead);
    // сохранение нового лида
    Lead save(LeadDTO leadDTO, String id);
    // метод обнолвдения данных лида
    void updateProfile(LeadDTO leadDTO, Long id);
    // метод поиска лидав по id
    Optional<Lead> findByIdAndToUpdate(Long id);
    public LeadDTO findById(Long id);
    Optional<User> findByFio(String operator);


    //    =============================== ВЫВОД ЛИДОВ ПО СПИСКАМ И СТАТУСАМ =========================================
    // метод вывода всех лидов
    Page<LeadDTO> getAllLeads(String status, String keyword, Principal principal, int pageNumber, int pageSize);
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

    //    =============================== СМЕНА СТАТУСОВ - КОНЕЦ =========================================
}
