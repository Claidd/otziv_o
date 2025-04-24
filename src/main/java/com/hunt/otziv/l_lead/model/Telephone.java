package com.hunt.otziv.l_lead.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.hunt.otziv.u_users.model.Operator;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties({"hibernateLazyInitializer"})
@Table(name = "telephones")
public class Telephone {

    // ID телефона
    @Id
    @Column(name = "telephone_id")
    private Long id;

    // Номер телефона Яндекс, ВК, Озон, Вайлдбериес
    @Column(name = "telephone_number")
    private String number;

    // ФИО аккаунта в соц. сетях
    @Column(name = "telephone_fio")
    private String fio;

    // Назначенный за телефоном Оператор
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "telephone_operator", unique = false, nullable = true)
    private Operator telephoneOperator;

    // Список лидов для оператора
    @OneToMany(mappedBy = "telephone", fetch = FetchType.LAZY)
    private Set<Lead> telephoneOperatorLids;


    // Список токенов устройства, привязанных к телефону
    @OneToMany(mappedBy = "telephone", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<DeviceToken> deviceTokens = new HashSet<>();


    // Кол-во разрешенных отправляемых
    @Column(name = "telephone_amount_allowed")
    private int amountAllowed;

    // Кол-во отправленных
    @Column(name = "telephone_amount_sent")
    private int amountSent;

    // Длительность блокировки
    @Column(name = "telephone_block_time")
    private int blockTime;

    // Таймер блокировки
    @Column(name = "telephone_timer")
    private LocalDateTime timer;




    //    Логин Гугла
    @Column(name = "telephone_google_login")
    private String googleLogin;

    //    Пароль Гугл
    @Column(name = "telephone_google_password")
    private String googlePassword;

    //    Пароль Авито
    @Column(name = "telephone_avito_password")
    private String avitoPassword;

    //    Логин Mail
    @Column(name = "telephone_mail_login")
    private String mailLogin;

    //    Пароль Mail
    @Column(name = "telephone_mail_password")
    private String mailPassword;




    //    время создания пользователя
    @Column(name = "telephone_create_date", nullable = false)
    private LocalDate createDate;

    //    дата и время обновления статуса
    @Column(name = "telephone_update_status")
    private LocalDateTime updateStatus;


    //    url аккаунта для взятия фото
    @Column(name = "telephone_foto_instagram")
    private String foto_instagram;

    //    указатель активности бота: активен в архиве
    @Column(name = "telephone_active")
    private boolean active;


}
