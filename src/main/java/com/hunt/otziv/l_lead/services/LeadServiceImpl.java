package com.hunt.otziv.l_lead.services;

import com.hunt.otziv.a_login.repository.UserRepository;
import com.hunt.otziv.l_lead.dto.LeadDTO;
import com.hunt.otziv.l_lead.model.Lead;
import com.hunt.otziv.l_lead.model.LeadStatus;
import com.hunt.otziv.l_lead.repository.LeadsRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
                .telephoneLead(leadDTO.getTelephoneLead())
                .cityLead(leadDTO.getCityLead())
                .commentsLead(leadDTO.getCommentsLead())
                .lidStatus(LeadStatus.SUNDAY.title)
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
