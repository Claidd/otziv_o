package com.hunt.otziv.u_users.dto;

import com.hunt.otziv.u_users.model.*;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RegistrationUserDTO {
    private Long id;
    @NotEmpty(message = "Имя не может быть пустым")
    private String username;
    @NotEmpty (message = "Пароль не может быть пустым")
    private String password;
    @NotEmpty (message = "Повторный пароль может быть пустым")
    private String matchingPassword;
    @NotEmpty (message = "ФИО не может быть пустым")
    private String fio;
    @NotEmpty (message = "email не может быть пустым")
    @Email (message = "Некорректный email")
    private String email;
    @NotEmpty (message = "Номер телефона не может быть пустым")
    @Pattern(regexp = "^(\\+7|7|8)?[\\s\\-]?\\(?[489][0-9]{2}\\)?[\\s\\-]?[0-9]{3}[\\s\\-]?[0-9]{2}[\\s\\-]?[0-9]{2}$", message = "Неверное количество цифр: Укажите номер правильно")
    @Size (min = 11, max = 12)
    private String phoneNumber;
    private boolean active;
    private Collection<Role> roles;
    private LocalDate createTime;
    private Manager manager;
    private Set<Operator> operators;
    private Set<Manager> managers;
    private Set<Worker> workers;
    private BigDecimal coefficient;


    public RegistrationUserDTO(Long id, String username, String password) {
    }

    public boolean isActive() {
        return active;
    }

    public Collection<Role> getRoles() {
        return roles;
    }

    //    /^(\+7|7|8)?[\s\-]?\(?[489][0-9]{2}\)?[\s\-]?[0-9]{3}[\s\-]?[0-9]{2}[\s\-]?[0-9]{2}$/



}
