package com.hunt.otziv.a_login.utils;

import com.hunt.otziv.a_login.dto.RegistrationUserDTO;
import com.hunt.otziv.a_login.repository.UserRepository;
import org.springframework.stereotype.Component;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;

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
        if (userRepository.findByUsername(userDto.getUsername()).isPresent()){
            errors.rejectValue("username", "", "Такой username уже занят другим пользователем");
        }
        /*Проверяем на имеющийся мейл в базе*/
        if (userRepository.findByEmail(userDto.getEmail()) != null){
            errors.rejectValue("email", "", "Такой email уже занят другим пользователем");
        }
        /*Проверяем на совпадение паролей*/
        if (!userDto.getPassword().equals(userDto.getMatchingPassword())){
            errors.rejectValue("password", "", "Пароли не совпадают");
        }
    }
}
