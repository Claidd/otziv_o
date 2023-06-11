package com.hunt.otziv.a_login.dto;

import com.hunt.otziv.a_login.model.Role;
import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserDTO {
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
    @Size (min = 10, max = 12)
    private String phoneNumber;
    private boolean active;

}
