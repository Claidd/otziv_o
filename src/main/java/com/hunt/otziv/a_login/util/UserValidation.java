package com.hunt.otziv.a_login.util;

import com.hunt.otziv.a_login.dto.UserDTO;
import com.hunt.otziv.a_login.model.User;
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
        return UserDTO.class.equals(clazz);
    }

    @Override
    public void validate(Object target, Errors errors) {
        UserDTO userDto = (UserDTO) target;
        if (userRepository.findByEmail(userDto.getEmail()) != null){
            errors.rejectValue("email", "", "Такой email уже занят другим пользователем");
        }
        /*Проверяем на совпадение паролей*/
        if (!userDto.getPassword().equals(userDto.getMatchingPassword())){
            errors.rejectValue("password", "", "Пароли не совпадают");
        }
    }
}
