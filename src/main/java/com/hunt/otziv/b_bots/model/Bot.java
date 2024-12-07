package com.hunt.otziv.b_bots.model;

import com.hunt.otziv.c_cities.model.City;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "bots")
public class Bot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "bot_id")
    private Long id;

    //    логин бота
    @Column(name = "bot_login", nullable = false)
    private String login;

    //    установленный пароль для бота (должен меняться)
    @Column(name = "bot_password", nullable = false)
    private String password;

    //    фамилия и инициалы бота
    @Column(name = "bot_fio", nullable = false)
    private String fio;

    //    указатель активности бота: активен в архиве
    @Column(name = "bot_active")
    private boolean active;

    //    счетчик количеста опубликованных с бота отзывов
    @Column(name = "bot_counter")
    private int counter;

    //    указатель статуса бота: заблокирован или готов к работе
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bot_status")
    private StatusBot status;

    //    каждый бот имеет Работника, который его добавлял
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "bot_worker")
    private Worker worker;

    //    за каждым ботом закреплен город
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "bot_city_id")
    private City botCity;

}
