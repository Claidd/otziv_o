package com.hunt.otziv.a_login.dto;

import com.hunt.otziv.a_login.model.Role;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RegistrationUserDTO {
    @NotEmpty(message = "Имя не может быть пустым")
    private String username;
    @NotEmpty (message = "Пароль не может быть пустым")
    private String password;
    @NotEmpty (message = "Повторный пароль может быть пустым")
    private String matchingPassword;
    @NotEmpty (message = "email не может быть пустым")
    @Email (message = "Некорректный email")
    private String email;
    @NotEmpty (message = "Номер телефона не может быть пустым")
    @Pattern(regexp = "^(\\+7|7|8)?[\\s\\-]?\\(?[489][0-9]{2}\\)?[\\s\\-]?[0-9]{3}[\\s\\-]?[0-9]{2}[\\s\\-]?[0-9]{2}$", message = "Неверное количество цифр: Укажите номер правильно")
    @Size (min = 10, max = 12)
    private String phoneNumber;
    private boolean active;
    private Collection<Role> roles;

    public RegistrationUserDTO(Long id, String username, String password) {
    }


//    /^(\+7|7|8)?[\s\-]?\(?[489][0-9]{2}\)?[\s\-]?[0-9]{3}[\s\-]?[0-9]{2}[\s\-]?[0-9]{2}$/



}
