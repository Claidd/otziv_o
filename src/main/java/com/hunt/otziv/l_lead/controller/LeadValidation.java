package com.hunt.otziv.l_lead.controller;

import com.hunt.otziv.l_lead.dto.LeadDTO;
import com.hunt.otziv.l_lead.repository.LeadsRepository;
import org.springframework.stereotype.Component;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;

// КЛАСС ДЛЯ ВАЛИДАЦИИ ДАННЫХ ФОРМЫ РЕГИСТРАЦИИ
@Component
public class LeadValidation implements Validator {

    private final LeadsRepository leadsRepository;

    public LeadValidation(LeadsRepository leadsRepository) {
        this.leadsRepository = leadsRepository;
    }


    @Override
    public boolean supports(Class<?> clazz) {
        return LeadDTO.class.equals(clazz);
    }

    @Override
    public void validate(Object target, Errors errors) {

        /*Проверяем на имеющийся телефон в базе*/
        LeadDTO leadDTO = (LeadDTO) target;
        if (leadsRepository.findByTelephoneLead(changeNumberPhone(leadDTO.getTelephoneLead())).isPresent()){
            errors.rejectValue("telephoneLead", "", "Такой номер телефона уже есть в базе");
        }

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
