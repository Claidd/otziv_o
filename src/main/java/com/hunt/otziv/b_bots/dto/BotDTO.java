package com.hunt.otziv.b_bots.dto;

import com.hunt.otziv.b_bots.model.StatusBot;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BotDTO {


    private Long id;
    @NotEmpty (message = "Номер телефона не может быть пустым")
    @Pattern(regexp = "^(\\+7|7|8)?[\\s\\-]?\\(?[489][0-9]{2}\\)?[\\s\\-]?[0-9]{3}[\\s\\-]?[0-9]{2}[\\s\\-]?[0-9]{2}$", message = "Неверное количество цифр: Укажите номер правильно")
    @Size(min = 11, max = 12)
    private String login;
    @NotEmpty(message = "Пароль не может быть пустым")
    private String password;
    @NotEmpty(message = "Логин не может быть пустым")
    private String fio;

    private boolean active;

    private int counter;

    private String status;

    private Worker worker;
}
