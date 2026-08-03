package com.hunt.otziv.l_lead.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.hunt.otziv.u_users.model.Operator;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TelephoneDTO {

    // ID телефона
    private Long id;

    // Номер телефона Яндекс, ВК, Озон, Вайлдбериес
    private String number;

    // ФИО аккаунта в соц. сетях
    private String fio;

    // Дата рождения
    private LocalDate birthday;

    // Кол-во разрешенных отправляемых
    private int amountAllowed;

    // Кол-во отправленных
    private int amountSent;

    // Длительность блокировки
    private int blockTime;

    // Таймер блокировки
    private LocalDateTime timer;



    //    Логин Гугла
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String googleLogin;

    //    Пароль Гугл
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String googlePassword;

    //    Пароль Авито
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String avitoPassword;

    //    Логин Mail
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String mailLogin;

    //    Пароль Mail
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String mailPassword;




    //    время создания пользователя
    private LocalDate createDate;

    //    дата и время обновления статуса
    private LocalDateTime updateStatus;

    // Назначенный за телефоном Оператор
    private Operator operator;

    //    url аккаунта для взятия фото
    private String foto_instagram;

    //    указатель активности бота: активен в архиве
    private boolean active;

}
