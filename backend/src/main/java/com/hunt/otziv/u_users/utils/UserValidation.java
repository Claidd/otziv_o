package com.hunt.otziv.u_users.utils;

import com.hunt.otziv.u_users.dto.RegistrationUserDTO;
import com.hunt.otziv.u_users.repository.UserRepository;
import org.springframework.stereotype.Component;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;

import java.util.Objects;
// КЛАСС ДЛЯ ВАЛИДАЦИИ ДАННЫХ ФОРМЫ РЕГИСТРАЦИИ
@Component
public class UserValidation implements Validator {

    private final UserRepository userRepository;

    public UserValidation(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public boolean supports(Class<?> clazz) {
        return RegistrationUserDTO.class.equals(clazz);
    }

    @Override
    public void validate(Object target, Errors errors) {

        /*Проверяем на имеющийся username в базе*/
        RegistrationUserDTO userDto = (RegistrationUserDTO) target;
        if (hasText(userDto.getUsername()) && userRepository.findByUsername(userDto.getUsername()).isPresent()){
            errors.rejectValue("username", "", "Такой username уже занят другим пользователем");
        }


        /*Проверяем на имеющийся мейл в базе*/
        if (hasText(userDto.getEmail()) && userRepository.findByEmail(userDto.getEmail()) != null){
            errors.rejectValue("email", "", "Такой email уже занят другим пользователем");
        }


        /*Проверяем на совпадение паролей*/
        if (!Objects.equals(userDto.getPassword(), userDto.getMatchingPassword())){
            errors.rejectValue("password", "", "Пароли не совпадают");
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
