package com.hunt.otziv.l_lead.services;

import com.hunt.otziv.a_login.dto.RegistrationUserDTO;
import com.hunt.otziv.a_login.model.User;
import com.hunt.otziv.l_lead.dto.LeadDTO;
import com.hunt.otziv.l_lead.model.Lead;

import java.security.Principal;
import java.util.List;

public interface LeadService {

    LeadDTO convertFromLead(Lead lead);

    Lead save(LeadDTO leadDTO, String id);

    List<LeadDTO> getAllLeads(String status, String keyword);


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
