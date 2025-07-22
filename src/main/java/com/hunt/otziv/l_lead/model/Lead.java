package com.hunt.otziv.l_lead.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Marketolog;
import com.hunt.otziv.u_users.model.Operator;
import com.hunt.otziv.u_users.model.User;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties({"hibernateLazyInitializer"})
@Table(name = "leads")
@NamedEntityGraph(
        name = "Lead.detail",
        attributeNodes = {
                @NamedAttributeNode("manager"),
                @NamedAttributeNode("operator"),
                @NamedAttributeNode("marketolog")
        }
)
public class Lead {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    //    телефон нового лида, который откликнулся на рассылку
    @Column(name = "telephone_lead", length = 20, nullable = false, unique = true)
    private String telephoneLead;

    //    город по которому шла рассылка
    @Column(name = "city_lead", length = 50, nullable = false)
    private String cityLead;

    //    город по которому шла рассылка
    @Column(name = "comments_lead", length = 2000)
    private String commentsLead;

    @Column(name = "lid_status", length = 30)
    private String lidStatus;

    //    время создания пользователя
    @Column(name = "create_date", nullable = false)
    private LocalDate createDate;

    //    дата и время обновления статуса
    @Column(name = "update_status")
    private LocalDateTime updateStatus;

    //    дата и время нового отправления предложения
    @Column(name = "date_new_try")
    private LocalDate dateNewTry;

    //    привязка юзера-оператора
    @ManyToOne (fetch = FetchType.LAZY)
    @JoinColumn(name = "operator_id", unique = false, nullable = true)
    private Operator operator;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "manager_id")
    private Manager manager;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "marketolog_id")
    private Marketolog marketolog;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "telephone_id")
    private Telephone telephone;

    //    высылали предложение или нет
    @Column(name = "leads_offer")
    private boolean offer;

    // Новое поле — когда пользователь последний раз был онлайн
    @Column(name = "last_seen")
    private LocalDateTime lastSeen;

    // Геттеры и сеттеры

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createDate = now.toLocalDate();
        updateStatus = now;
        dateNewTry = now.toLocalDate();
    }

    @PreUpdate
    protected void updateTimestamps() {
        updateStatus = LocalDateTime.now();
        if (createDate == null) {
            createDate = LocalDate.now();
        }
        if (dateNewTry == null) {
            dateNewTry = LocalDate.now();
        }
    }


}
