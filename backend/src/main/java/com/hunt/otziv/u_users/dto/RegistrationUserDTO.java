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
    @Size(max = 100)
    @Pattern(regexp = "^[^\\p{Cc}\\p{Zl}\\p{Zp}]*$")
    private String username;
    @NotEmpty (message = "Пароль не может быть пустым")
    @Size(min = 12, max = 128, message = "Пароль должен содержать от 12 до 128 символов")
    @Pattern(
            regexp = "^(?=.*\\p{Ll})(?=.*\\p{Lu})(?=.*\\p{N})(?=.*[^\\p{L}\\p{N}\\s])[^\\r\\n]{12,128}$",
            message = "Пароль должен содержать строчную и заглавную буквы, цифру и специальный символ"
    )
    private String password;
    @NotEmpty (message = "Повторный пароль может быть пустым")
    @Size(min = 12, max = 128)
    private String matchingPassword;
    @NotEmpty (message = "ФИО не может быть пустым")
    @Size(max = 255)
    @Pattern(regexp = "^[^\\p{Cc}\\p{Zl}\\p{Zp}]*$")
    private String fio;
    @NotEmpty (message = "email не может быть пустым")
    @Email (message = "Некорректный email")
    @Size(max = 255)
    @Pattern(regexp = "^[^\\p{Cc}\\p{Zl}\\p{Zp}]*$")
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
    private Set<Marketolog> marketologs;
    private BigDecimal coefficient;
    private Image image;


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
