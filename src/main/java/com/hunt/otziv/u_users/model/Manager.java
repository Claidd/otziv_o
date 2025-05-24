package com.hunt.otziv.u_users.model;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.l_lead.model.Lead;
import jakarta.persistence.*;
import lombok.*;

import java.util.Objects;
import java.util.Set;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "managers")
public class Manager {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "manager_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "pay_text")
    private String payText;

    // Начальный текст
    @Column(name = "manager_begin")
    private String beginText;

    // Текст предложения
    @Column(name = "manager_offer")
    private String offerText;

    // Текст предложения 2
    @Column(name = "manager_reminder")
    private String reminderText;

    // Текст о создании группы
    @Column(name = "manager_start")
    private String startText;

    // Клиент Id в Whatsapp
    @Column(name = "client_id")
    private String clientId;

    // Клиент Id в Whatsapp
    @Column(name = "group_id")
    private String groupId;

    @OneToMany(mappedBy = "manager", fetch = FetchType.LAZY)
    private Set<Company> companies;

    @OneToMany(mappedBy = "manager", fetch = FetchType.LAZY)
    @ToString.Exclude
    private Set<Lead> leads;

    @Override
    public int hashCode() {
        return Objects.hash(id); // Используйте только идентификатор для хэширования
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Manager manager = (Manager) o;
        return Objects.equals(id, manager.id); // Сравнивайте только идентификатор
    }

    @Override
    public String toString() {
        return "Manager(id=" + id + ", user=" + user + ")";
    }
}
