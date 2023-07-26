package com.hunt.otziv.l_lead.services;

import com.hunt.otziv.a_login.dto.RegistrationUserDTO;
import com.hunt.otziv.a_login.model.User;
import com.hunt.otziv.a_login.repository.UserRepository;
import com.hunt.otziv.l_lead.dto.LeadDTO;
import com.hunt.otziv.l_lead.model.Lead;
import com.hunt.otziv.l_lead.model.LeadStatus;
import com.hunt.otziv.l_lead.repository.LeadsRepository;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Slf4j
public class LeadServiceImpl implements LeadService{

    private final LeadsRepository leadsRepository;
    private final UserRepository userRepository;

    public LeadServiceImpl(LeadsRepository leadsRepository, UserRepository userRepository) {
        this.leadsRepository = leadsRepository;
        this.userRepository = userRepository;
    }

    // Создание нового пользователя "Лида" - начало
    public Lead save(LeadDTO leadDTO, String username){
        log.info("3. Заходим в создание нового юзера и проверяем совпадение паролей");

        Lead lead = Lead.builder()
                .telephoneLead(changeNumberPhone(leadDTO.getTelephoneLead()))
                .cityLead(leadDTO.getCityLead())
                .commentsLead(leadDTO.getCommentsLead())
                .lidStatus(LeadStatus.NEW.title)
                .operator(userRepository.findByUsername(username).orElseThrow())

                .build();
        log.info("5. Юзер успешно создан");
//        this.save(user);
        System.out.println(lead.getLidStatus());
        return leadsRepository.save(lead);
    }
    // Создание нового пользователя "Клиент" - конец

    // Метод конвертации из класса Lead в класс LeadDTO
    public LeadDTO convertFromLead(Lead lead) {
        log.info("Перевод лида в дто");
        LeadDTO leadDTO = new LeadDTO();
        leadDTO.setId(lead.getId());
        leadDTO.setTelephoneLead(lead.getTelephoneLead());
        leadDTO.setCityLead(lead.getCityLead());
        leadDTO.setCommentsLead(lead.getCommentsLead());
        leadDTO.setLidStatus(lead.getLidStatus());
        leadDTO.setCreateDate(lead.getCreateDate());
        leadDTO.setUpdateStatus(lead.getUpdateStatus());
        leadDTO.setDateNewTry(lead.getDateNewTry());

        // Обратите внимание, что здесь мы присваиваем идентификатор пользователя вместо всего объекта User
        // Если нужно больше данных о пользователе, то можно добавить соответствующие поля в LeadDTO
        leadDTO.setOperatorId(lead.getOperator() != null ? lead.getOperator().getId() : null);

        return leadDTO;
    }

    // Взять всех юзеров - начало
    @Override
    public List<LeadDTO> getAllLeads(String status) {
        log.info("Берем все юзеров");
        return leadsRepository.findAllByLidStatus(status).stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    // Взять всех юзеров - конец

    //    =============================== СМЕНА СТАТУСОВ - НАЧАЛО =========================================

    // меняем статус с нового на отправленное - начало
    @Override
    @Transactional
    public void changeStatusLeadOnSend(Long leadId) {
        Lead lead = findByLeadId(leadId).orElseThrow(() -> new UsernameNotFoundException(
                String.format("Пользоваттель '%s' не найден", leadId)
        ));
        lead.setLidStatus("Отправленный");
        lead.setUpdateStatus(LocalDate.now());
        leadsRepository.save(lead);
    }
    // меняем статус с нового на отправленное - конец

    // меняем статус с отправленное на напоминание - начало
    @Override
    @Transactional
    public void changeStatusLeadOnReSend(Long leadId) {
        Lead lead = findByLeadId(leadId).orElseThrow(() -> new UsernameNotFoundException(
                String.format("Пользоваттель '%s' не найден", leadId)
        ));
        lead.setLidStatus("Напоминание");
        lead.setUpdateStatus(LocalDate.now());
        leadsRepository.save(lead);
    }
    // меняем статус с отправленное на напоминание - конец

    // меняем статус с напоминание на К рассылке - начало
    @Override
    @Transactional
    public void changeStatusLeadOnArchive(Long leadId) {
        Lead lead = findByLeadId(leadId).orElseThrow(() -> new UsernameNotFoundException(
                String.format("Пользоваттель '%s' не найден", leadId)
        ));
        lead.setLidStatus("К рассылке");
        lead.setUpdateStatus(LocalDate.now());
        leadsRepository.save(lead);
    }
    // меняем статус с напоминание на К рассылке - конец

    // меняем статус с К рассылке на В работе - начало
    @Override
    @Transactional
    public void changeStatusLeadOnInWork(Long leadId) {
        Lead lead = findByLeadId(leadId).orElseThrow(() -> new UsernameNotFoundException(
                String.format("Пользоваттель '%s' не найден", leadId)
        ));
        lead.setLidStatus("В работе");
        lead.setUpdateStatus(LocalDate.now());
        leadsRepository.save(lead);
    }
    // меняем статус с К рассылке на В работе - конец


    // меняем статус с любого на Новый - начало
    @Override
    @Transactional
    public void changeStatusLeadOnNew(Long leadId) {
        Lead lead = findByLeadId(leadId).orElseThrow(() -> new UsernameNotFoundException(
                String.format("Пользоваттель '%s' не найден", leadId)
        ));
        lead.setLidStatus("Новый");
        lead.setUpdateStatus(LocalDate.now());
        leadsRepository.save(lead);
    }
    // меняем статус с любого на Новый - конец

//    =============================== СМЕНА СТАТУСОВ - КОНЕЦ =========================================

    // Метод поиска юзера по имени в БД
    public Optional<Lead> findByLeadId(Long leadId){
        return leadsRepository.findById(leadId);

    }


    // Перевод юзера в дто - начало
    private LeadDTO toDto(Lead lead){
        log.info("Перевод юзера в дто");
        return LeadDTO.builder()
                .id(lead.getId())
                .telephoneLead(lead.getTelephoneLead())
                .cityLead(lead.getCityLead())
                .lidStatus(lead.getLidStatus())
                .commentsLead(lead.getCommentsLead())
                .createDate(lead.getCreateDate())
                .updateStatus(lead.getUpdateStatus())
                .dateNewTry(lead.getDateNewTry())
                .operatorId(lead.getOperator().getId())
                .build();
    }
    // Перевод юзера в дто - конец


    // Вспомогательный метод для корректировки номера телефона
    public String changeNumberPhone(String phone){
        String[] a;
        a = phone.split("9");
        a[0] = "+79";
        String b = a[0] + a[1];
        System.out.println(b);
        return b;
//        userDto.getPhoneNumber().replaceFirst("8", "+7")
    }

}
