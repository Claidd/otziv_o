package com.hunt.otziv.c_companies.util;

import com.hunt.otziv.c_companies.dto.CompanyDTO;
import com.hunt.otziv.c_companies.services.FilialService;
import org.springframework.stereotype.Component;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;

// КЛАСС ДЛЯ ВАЛИДАЦИИ ДАННЫХ ФОРМЫ РЕГИСТРАЦИИ
@Component
public class CompanyValidation implements Validator {

    private final FilialService filialService;

    public CompanyValidation(FilialService filialService) {
        this.filialService = filialService;
    }

    @Override
    public boolean supports(Class<?> clazz) {
        return CompanyDTO.class.equals(clazz);
    }

    @Override
    public void validate(Object target, Errors errors) {

        /*Проверяем на имеющийся телефон в базе*/
        CompanyDTO companyDTO = (CompanyDTO) target;

        if (filialService.findFilialByUrl(companyDTO.getFilial().getUrl()) != null){
            errors.rejectValue("filial.url", "", "Такой url филиала уже есть в базе");
        }

    }
}
