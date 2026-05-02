package com.hunt.otziv.b_bots.utils;

import com.hunt.otziv.b_bots.dto.BotDTO;
import com.hunt.otziv.b_bots.repository.BotsRepository;
import com.hunt.otziv.u_users.dto.RegistrationUserDTO;
import org.springframework.stereotype.Component;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;

// КЛАСС ДЛЯ ВАЛИДАЦИИ ДАННЫХ ФОРМЫ РЕГИСТРАЦИИ
@Component
public class BotValidation implements Validator {

    private final BotsRepository botsRepository;

    public BotValidation(BotsRepository botsRepository) {
        this.botsRepository = botsRepository;
    }

    @Override
    public boolean supports(Class<?> clazz) {
        return RegistrationUserDTO.class.equals(clazz);
    }

    @Override
    public void validate(Object target, Errors errors) {

        /*Проверяем на имеющийся username в базе*/
        BotDTO botDTO = (BotDTO) target;
        if (botsRepository.findByLogin(changeNumberPhone(botDTO.getLogin())).isPresent()){
            errors.rejectValue("login", "", "Такой login уже занят другим пользователем");
        }
    }

    // Вспомогательный метод для корректировки номера телефона
    public String changeNumberPhone(String phone) {
        String[] a = phone.split("9");

        if (a.length >= 2) {
            a[0] = "+79";
            return a[0] + a[1];
        } else {
            // Возвращаем исходный номер телефона, если '9' не найден
            return phone;
        }
    }

}
