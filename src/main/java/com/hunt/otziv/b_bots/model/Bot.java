package com.hunt.otziv.b_bots.model;

import com.hunt.otziv.r_review.model.Review;
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
    @Column(name = "id")
    private Long id;
    @Column(name = "login", nullable = false)

    //    логин бота
    private String login;
    @Column(name = "password", nullable = false)

    //    установленный пароль для бота (должен меняться)
    private String password;

    //    фамилия и инициалы бота
    @Column(name = "fio", nullable = false)
    private String fio;

    //    счетчик количеста опубликованных с бота отзывов
    @Column(name = "counter")
    private int counter;

    //    указатель активности бота: активен в архиве
    @Column(name = "active")
    private boolean active;

    //    указатель статуса бота: заблокирован или готов к работе
    @Column(name = "status")
    private String status;

    //    каждый бот имеет спикок опубликованных с него отзывов
    @OneToMany(mappedBy = "bot")
    List<Review> reviewList;

//    @Enumerated(EnumType.STRING)
//    @Column(name = "role")
//    private Role roles;

}
