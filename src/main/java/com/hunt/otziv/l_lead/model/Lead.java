package com.hunt.otziv.l_lead.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.hunt.otziv.a_login.model.User;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties({"hibernateLazyInitializer"})
@Table(name = "leads")
public class Lead {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    //    телефон нового лида, который откликнулся на рассылку
    @Column(name = "telephone_lead")
    private String telephoneLead;

    //    город по которому шла рассылка
    @Column(name = "city_lead")
    private String cityLead;

    //    город по которому шла рассылка
    @Column(name = "comments_lead")
    private String commentsLead;

    //    текущий статус лида ЕНАМ?????
//    @Enumerated(EnumType.STRING)
    @Column(name = "lid_status")
    private String lidStatus;

    //    время создания пользователя
    @Temporal(TemporalType.DATE)
    @Column(name = "create_date")
    private LocalDate createDate;

    //    дата и время обновления статуса
    @Temporal(TemporalType.DATE)
    @Column(name = "update_status")
    private LocalDate updateStatus;

    //    дата и время нового отправления предложения
    @Temporal(TemporalType.DATE)
    @Column(name = "date_new_try")
    private LocalDate dateNewTry;

    //    привязка юзера-оператора
//    @MapsId
//    @OneToOne( fetch = FetchType.LAZY)
//    @JoinColumn(name = "operator_id", unique = false, nullable = true)
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "operator_id", unique = false, nullable = true)
    private User operator;


    // Геттеры и сеттеры

    @PrePersist
    protected void onCreate() {
        createDate = LocalDate.now();
        updateStatus = LocalDate.now();
        dateNewTry = LocalDate.now();
        System.out.println(LocalDate.now().plusDays(10));
    }


}
